package com.aliucord.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    init {
        settingsTab = SettingsTab(ApplicationsSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val authToken: String
        get() = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBypassing = AtomicBoolean(false)

    override fun start(context: Context) {
        try {
            val restAPI = Class.forName("com.discord.utilities.rest.RestAPI")
            val targetMethods = restAPI.declaredMethods.filter { 
                it.name == "postInviteCode" || it.name == "joinGuild" 
            }

            for (method in targetMethods) {
                patcher.patch(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBypassing.get()) return

                        try {
                            val firstArg = param.args.firstOrNull() ?: return
                            var inviteCode = ""
                            var guildId = ""
                            var guildName = "Unknown Server"

                            if (param.method.name == "joinGuild") {
                                guildId = firstArg.toString()
                            } else if (firstArg is String) {
                                inviteCode = firstArg
                            } else if (firstArg.javaClass.name.endsWith("ModelInvite")) {
                                val getCode = firstArg.javaClass.getMethod("getCode")
                                inviteCode = getCode.invoke(firstArg) as? String ?: return

                                val getGuild = firstArg.javaClass.getMethod("getGuild")
                                val guildObj = getGuild.invoke(firstArg)
                                if (guildObj != null) {
                                    val getId = guildObj.javaClass.getMethod("getId")
                                    guildId = (getId.invoke(guildObj) as? Number)?.toLong()?.toString() ?: ""
                                    try {
                                        guildName = guildObj.javaClass.getMethod("getName").invoke(guildObj) as? String ?: "Unknown Server"
                                    } catch (e: Exception) {}
                                }
                            } else {
                                return
                            }

                            param.result = Observable.create(Observable.OnSubscribe<Any> { subscriber ->
                                Utils.threadPool.execute {
                                    try {
                                        var finalGuildId = guildId
                                        var finalGuildName = guildName

                                        if (finalGuildId.isEmpty() && inviteCode.isNotEmpty()) {
                                            val inviteUrl = "https://discord.com/api/v9/invites/$inviteCode"
                                            val invRes = Http.Request(inviteUrl, "GET").execute()
                                            if (invRes.statusCode in 200..299) {
                                                val invJson = JSONObject(invRes.text())
                                                val gObj = invJson.optJSONObject("guild")
                                                if (gObj != null) {
                                                    finalGuildId = gObj.optString("id")
                                                    finalGuildName = gObj.optString("name", "Unknown Server")
                                                }
                                            }
                                        }

                                        if (finalGuildId.isNotEmpty()) {
                                            val verifyUrl = "https://discord.com/api/v9/guilds/$finalGuildId/member-verification"
                                            val res = Http.Request(verifyUrl, "GET")
                                                .setHeader("Authorization", authToken)
                                                .execute()

                                            if (res.statusCode in 200..299) {
                                                val json = JSONObject(res.text())
                                                val formFields = json.optJSONArray("form_fields")

                                                if (formFields != null && formFields.length() > 0) {
                                                    mainHandler.post {
                                                        val manager = Utils.appActivity?.supportFragmentManager
                                                        if (manager != null) {
                                                            val sheet = ServerApplicationSheet(finalGuildId, finalGuildName, formFields, inviteCode, this@ServerApplicationFix)
                                                            sheet.show(manager, "ServerApplicationSheet")
                                                        }
                                                    }
                                                    subscriber.onError(Exception("Requires Application"))
                                                    return@execute
                                                }
                                            }
                                        }

                                        isBypassing.set(true)
                                        try {
                                            val targetMtd = param.method as java.lang.reflect.Method
                                            val origObs = targetMtd.invoke(param.thisObject, *param.args) as Observable<Any>
                                            origObs.subscribe(
                                                { result -> subscriber.onNext(result) },
                                                { error -> subscriber.onError(error) },
                                                { subscriber.onCompleted() }
                                            )
                                        } catch (e: Exception) {
                                            subscriber.onError(e)
                                        } finally {
                                            isBypassing.set(false)
                                        }

                                    } catch (e: Exception) {
                                        subscriber.onError(e)
                                    }
                                }
                            })
                        } catch (e: Exception) {
                            logger.error("Hook extraction error", e)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            logger.error("Failed to hook RestAPI", e)
        }
    }

    fun submitApplication(guildId: String, guildName: String, inputs: List<Pair<JSONObject, EditText>>, inviteCode: String) {
        Utils.threadPool.execute {
            try {
                val responses = JSONArray()
                for (input in inputs) {
                    val field = input.first
                    val value = input.second.text.toString()
                    val responseObj = JSONObject().apply {
                        put("field_type", field.optString("field_type"))
                        put("response", value)
                    }
                    responses.put(responseObj)
                }

                val body = JSONObject().apply {
                    put("form_fields", responses)
                }

                val url = "https://discord.com/api/v9/guilds/$guildId/requests/@me"
                val res = Http.Request(url, "POST")
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)

                if (res.statusCode in 200..299) {
                    trackApplication(guildId, guildName)
                    if (inviteCode.isNotEmpty()) {
                        joinGuildDirect(inviteCode)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun joinGuildDirect(inviteCode: String) {
        Utils.threadPool.execute {
            try {
                val joinUrl = "https://discord.com/api/v9/invites/$inviteCode"
                Http.Request(joinUrl, "POST")
                    .setHeader("Authorization", authToken)
                    .execute()
            } catch (e: Exception) {}
        }
    }

    private fun trackApplication(guildId: String, guildName: String) {
        try {
            val current = JSONArray(settings.getString("applications", "[]"))
            val updated = JSONArray()
            for (i in 0 until current.length()) {
                val entry = current.getJSONObject(i)
                if (entry.optString("id") != guildId) updated.put(entry)
            }
            updated.put(JSONObject().apply {
                put("id", guildId)
                put("name", guildName)
            })
            settings.setString("applications", updated.toString())
        } catch (e: Exception) {}
    }

    fun getTrackedApplications(): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(settings.getString("applications", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.optString("id") to o.optString("name")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchApplicationStatus(guildId: String): String {
        return try {
            val res = Http.Request("https://discord.com/api/v9/guilds/$guildId/requests/@me", "GET")
                .setHeader("Authorization", authToken)
                .execute()

            if (!res.ok()) return "Unknown (HTTP ${res.statusCode})"

            val json = JSONObject(res.text())
            when (json.optString("status").uppercase()) {
                "STARTED", "SUBMITTED" -> "Pending"
                "APPROVED" -> "Accepted"
                "REJECTED" -> "Declined"
                else -> json.optString("status").ifBlank { "Unknown" }
            }
        } catch (e: Exception) {
            "Error checking status"
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
