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
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    init {
        settingsTab = SettingsTab(ApplicationsSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val authToken: String
        get() = com.discord.utilities.rest.RestAPI.AppHeadersProvider.INSTANCE.authToken

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBypassing = AtomicBoolean(false)

    override fun start(context: Context) {
        try {
            val joinGuildMethod = Class.forName("com.discord.widgets.guilds.join.GuildJoinHelperKt")
                .declaredMethods.firstOrNull { it.name == "joinGuild" }

            if (joinGuildMethod != null) {
                patcher.patch(joinGuildMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBypassing.get()) return

                        val guildId = param.args[1] as? Long ?: return
                        param.result = null

                        Utils.threadPool.execute {
                            try {
                                val url = "https://discord.com/api/v9/guilds/$guildId/member-verification?with_guild=false"
                                val res = Http.Request(url, "GET")
                                    .setHeader("Authorization", authToken)
                                    .execute()

                                if (res.statusCode in 200..299) {
                                    val json = JSONObject(res.text())
                                    val formFields = json.optJSONArray("form_fields")

                                    if (formFields != null && formFields.length() > 0) {
                                        val guildName = fetchGuildName(guildId)
                                        mainHandler.post {
                                            val manager = Utils.appActivity?.supportFragmentManager
                                            if (manager != null) {
                                                val sheet = ServerApplicationSheet(
                                                    guildId.toString(),
                                                    guildName,
                                                    formFields,
                                                    this@ServerApplicationFix
                                                )
                                                sheet.show(manager, "ServerApplicationSheet")
                                            } else {
                                                Utils.showToast("Cannot open application form")
                                            }
                                        }
                                        return@execute
                                    }
                                }

                                mainHandler.post {
                                    triggerOriginalJoin(param)
                                }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    triggerOriginalJoin(param)
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
        }
    }

    private fun triggerOriginalJoin(param: XC_MethodHook.MethodHookParam) {
        isBypassing.set(true)
        try {
            (param.method as Method).invoke(null, *param.args)
        } catch (e: Throwable) {
        } finally {
            isBypassing.set(false)
        }
    }

    private fun fetchGuildName(guildId: Long): String {
        return try {
            val res = Http.Request("https://discord.com/api/v9/guilds/$guildId/preview", "GET")
                .setHeader("Authorization", authToken)
                .execute()
            if (res.statusCode in 200..299) JSONObject(res.text()).optString("name", "Server") else "Server"
        } catch (e: Exception) {
            "Server"
        }
    }

    fun submitApplication(
        guildId: String,
        guildName: String,
        inputs: List<Pair<JSONObject, EditText>>
    ) {
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
                var res = Http.Request(url, "PUT")
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)

                if (res.statusCode !in 200..299) {
                    res = Http.Request(url, "POST")
                        .setHeader("Authorization", authToken)
                        .setHeader("Content-Type", "application/json")
                        .executeWithJson(body)
                }

                mainHandler.post {
                    if (res.statusCode in 200..299) {
                        trackApplication(guildId, guildName)
                        Utils.showToast("Application submitted successfully")
                    } else {
                        Utils.showToast("Failed to submit application")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Utils.showToast("An error occurred")
                }
            }
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

            if (!res.ok()) return "Unknown"

            val json = JSONObject(res.text())
            when (json.optString("status").uppercase()) {
                "STARTED", "SUBMITTED" -> "Pending"
                "APPROVED" -> "Accepted"
                "REJECTED" -> "Declined"
                else -> json.optString("status").ifBlank { "Unknown" }
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
