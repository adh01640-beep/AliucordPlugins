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
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBypassing = AtomicBoolean(false)

    override fun start(context: Context) {
        try {
            val restApiClass = Class.forName("com.discord.utilities.rest.RestAPI")
            val targetMethods = restApiClass.declaredMethods.filter { 
                it.name == "postInviteCode" 
            }

            for (method in targetMethods) {
                patcher.patch(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBypassing.get()) {
                            return
                        }

                        val inviteCode = when (val arg = param.args[0]) {
                            is String -> arg
                            else -> {
                                try {
                                    val getCode = arg.javaClass.getMethod("getCode")
                                    getCode.invoke(arg) as? String
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        } ?: return

                        param.result = null

                        checkVerificationAndProceed(inviteCode) {
                            proceedOriginalJoin(param.thisObject, method, param.args)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            logger.error("Failed to patch postInviteCode", e)
        }
    }

    private fun checkVerificationAndProceed(inviteCode: String, onNoForm: () -> Unit) {
        Utils.threadPool.execute {
            try {
                val inviteUrl = "https://discord.com/api/v9/invites/$inviteCode"
                val inviteRes = Http.Request(inviteUrl, "GET")
                    .setHeader("Authorization", authToken)
                    .execute()

                if (inviteRes.statusCode in 200..299) {
                    val inviteJson = JSONObject(inviteRes.text())
                    val guild = inviteJson.optJSONObject("guild")
                    val guildId = guild?.optString("id")

                    if (guildId != null) {
                        val verifyUrl = "https://discord.com/api/v9/guilds/$guildId/member-verification"
                        val verifyRes = Http.Request(verifyUrl, "GET")
                            .setHeader("Authorization", authToken)
                            .execute()

                        if (verifyRes.statusCode in 200..299) {
                            val verifyJson = JSONObject(verifyRes.text())
                            val formFields = verifyJson.optJSONArray("form_fields") ?: JSONArray()

                            if (formFields.length() > 0) {
                                mainHandler.post {
                                    val manager = Utils.appActivity?.supportFragmentManager
                                    if (manager != null) {
                                        val sheet = ServerApplicationSheet(guildId, formFields, inviteCode, this)
                                        sheet.show(manager, "ServerApplicationSheet")
                                    } else {
                                        onNoForm()
                                    }
                                }
                                return@execute
                            }
                        }
                    }
                }
                onNoForm()
            } catch (e: Exception) {
                onNoForm()
            }
        }
    }

    private fun proceedOriginalJoin(instance: Any, method: java.lang.reflect.Method, args: Array<Any?>) {
        mainHandler.post {
            try {
                isBypassing.set(true)
                method.invoke(instance, *args)
            } catch (e: Exception) {
                logger.error("Failed to invoke original join", e)
            } finally {
                isBypassing.set(false)
            }
        }
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
                    val joinUrl = "https://discord.com/api/v9/invites/$inviteCode"
                    Http.Request(joinUrl, "POST")
                        .setHeader("Authorization", authToken)
                        .execute()
                }
            } catch (e: Exception) {}
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
