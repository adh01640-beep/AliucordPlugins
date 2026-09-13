package com.aliucord.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.utilities.rest.RestAPI
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBypassing = AtomicBoolean(false)

    override fun start(context: Context) {
        try {
            val restAPI = Class.forName("com.discord.utilities.rest.RestAPI")
            val postInviteCode = restAPI.declaredMethods.find { 
                it.name == "postInviteCode" && it.parameterTypes.size == 3 
            }

            if (postInviteCode != null) {
                patcher.patch(postInviteCode, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBypassing.get()) return

                        try {
                            val inviteObj = param.args[0] ?: return
                            val getCode = inviteObj.javaClass.getMethod("getCode")
                            val getGuild = inviteObj.javaClass.getMethod("getGuild")

                            val inviteCode = getCode.invoke(inviteObj) as? String ?: return
                            val guildObj = getGuild.invoke(inviteObj) ?: return

                            val getId = guildObj.javaClass.getMethod("getId")
                            val guildId = (getId.invoke(guildObj) as? Number)?.toLong()?.toString() ?: return

                            param.result = Observable.create<Any> { subscriber ->
                                Utils.threadPool.execute {
                                    try {
                                        val url = "https://discord.com/api/v9/guilds/$guildId/member-verification"
                                        val res = Http.Request(url, "GET")
                                            .setHeader("Authorization", authToken)
                                            .execute()

                                        if (res.statusCode in 200..299) {
                                            val json = JSONObject(res.text())
                                            val formFields = json.optJSONArray("form_fields")

                                            if (formFields != null && formFields.length() > 0) {
                                                mainHandler.post {
                                                    val manager = Utils.appActivity?.supportFragmentManager
                                                    if (manager != null) {
                                                        val sheet = ServerApplicationSheet(guildId, formFields, inviteCode, this@ServerApplicationFix)
                                                        sheet.show(manager, "ServerApplicationSheet")
                                                    }
                                                }
                                                subscriber.onError(Exception("Requires Application"))
                                                return@execute
                                            }
                                        }

                                        isBypassing.set(true)
                                        try {
                                            val origObs = param.method.invoke(param.thisObject, *param.args) as Observable<Any>
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
                            }
                        } catch (e: Exception) {}
                    }
                })
            }
        } catch (e: Exception) {}
    }

    fun submitApplication(guildId: String, inputs: List<Pair<JSONObject, EditText>>, inviteCode: String) {
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
                    joinGuildDirect(inviteCode)
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

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
