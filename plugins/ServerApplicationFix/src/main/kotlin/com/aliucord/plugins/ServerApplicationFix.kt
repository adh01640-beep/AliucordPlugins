package com.aliucord.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.utilities.rest.RestAPI
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start(context: Context) {
        hookInviteButtons()
    }

    private fun hookInviteButtons() {
        val targetListeners = listOf(
            "com.discord.widgets.guilds.invite.WidgetGuildInvite\$onViewBound\$1",
            "com.discord.widgets.guilds.invite.WidgetGuildInvite\$configureLoadedUI\$onAcceptClick\$1"
        )

        for (className in targetListeners) {
            try {
                val clazz = Class.forName(className)
                patcher.patch(clazz, "onClick", arrayOf(View::class.java), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val instance = param.thisObject
                            val inviteObj = extractModelInvite(instance) ?: return

                            val getCode = inviteObj.javaClass.getMethod("getCode")
                            val getGuild = inviteObj.javaClass.getMethod("getGuild")

                            val inviteCode = getCode.invoke(inviteObj) as? String ?: return
                            val guildObj = getGuild.invoke(inviteObj) ?: return

                            val getId = guildObj.javaClass.getMethod("getId")
                            val guildId = (getId.invoke(guildObj) as? Number)?.toLong()?.toString() ?: return

                            Utils.threadPool.execute {
                                val formFields = checkVerificationForm(guildId)
                                if (formFields != null && formFields.length() > 0) {
                                    param.result = null
                                    mainHandler.post {
                                        val manager = Utils.appActivity?.supportFragmentManager
                                        if (manager != null) {
                                            val sheet = ServerApplicationSheet(guildId, formFields, inviteCode, this@ServerApplicationFix)
                                            sheet.show(manager, "ServerApplicationSheet")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error intercepting invite button click", e)
                        }
                    }
                })
            } catch (e: ClassNotFoundException) {
                // الفئة غير موجودة بهذا الاسم في الإصدار الحالي
            } catch (e: Exception) {
                logger.error("Failed to patch $className", e)
            }
        }
    }

    private fun extractModelInvite(instance: Any): Any? {
        val fields = instance.javaClass.declaredFields
        for (field in fields) {
            field.isAccessible = true
            val value = field.get(instance)
            if (value != null && value.javaClass.name.contains("ModelInvite")) {
                return value
            }
        }

        // فحص الكلاس الخارجي (this$0) في حال كان Listener كائناً داخلياً
        try {
            val outerField = instance.javaClass.getDeclaredField("this$0")
            outerField.isAccessible = true
            val outerInstance = outerField.get(instance) ?: return null
            for (field in outerInstance.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(outerInstance)
                if (value != null && value.javaClass.name.contains("ModelInvite")) {
                    return value
                }
            }
        } catch (e: Exception) {}

        return null
    }

    private fun checkVerificationForm(guildId: String): JSONArray? {
        return try {
            val url = "https://discord.com/api/v9/guilds/$guildId/member-verification"
            val res = Http.Request(url, "GET")
                .setHeader("Authorization", authToken)
                .execute()

            if (res.statusCode in 200..299) {
                val json = JSONObject(res.text())
                json.optJSONArray("form_fields")
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
                    joinGuildDirect(inviteCode)
                }
            } catch (e: Exception) {
                logger.error("Failed to submit application", e)
            }
        }
    }

    private fun joinGuildDirect(inviteCode: String) {
        Utils.threadPool.execute {
            try {
                val joinUrl = "https://discord.com/api/v9/invites/$inviteCode"
                Http.Request(joinUrl, "POST")
                    .setHeader("Authorization", authToken)
                    .execute()
            } catch (e: Exception) {
                logger.error("Failed to join invite", e)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
