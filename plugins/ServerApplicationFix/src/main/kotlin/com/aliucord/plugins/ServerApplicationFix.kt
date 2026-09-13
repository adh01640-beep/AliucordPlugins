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
        hookInviteSheet()
    }

    private fun hookInviteSheet() {
        try {
            val widgetGuildInviteClass = Class.forName("com.discord.widgets.guilds.invite.WidgetGuildInvite")
            
            // هوك على دالة تهيئة الواجهة بعد جلب بيانات الدعوة
            val targetMethods = widgetGuildInviteClass.declaredMethods.filter { 
                it.name == "configureLoadedUI" || it.name == "configureUI" 
            }

            for (method in targetMethods) {
                patcher.patch(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val widgetInstance = param.thisObject
                            
                            // استخراج الزر f2424b من getBinding()
                            val getBindingMethod = widgetInstance.javaClass.getMethod("getBinding")
                            val bindingObj = getBindingMethod.invoke(widgetInstance) ?: return
                            
                            val btnField = bindingObj.javaClass.getDeclaredField("b")
                            btnField.isAccessible = true
                            val joinButton = btnField.get(bindingObj) as? View ?: return

                            // استخراج كائن ModelInvite الممرر للدالة أو المخزن في الـ Widget
                            var inviteObj: Any? = null
                            for (arg in param.args) {
                                if (arg != null && arg.javaClass.name.contains("ModelInvite")) {
                                    inviteObj = arg
                                    break
                                }
                            }

                            if (inviteObj == null) {
                                // محاولة إيجاده داخل حقول الـ Widget
                                for (f in widgetInstance.javaClass.declaredFields) {
                                    f.isAccessible = true
                                    val v = f.get(widgetInstance)
                                    if (v != null && v.javaClass.name.contains("ModelInvite")) {
                                        inviteObj = v
                                        break
                                    }
                                }
                            }

                            if (inviteObj == null) return

                            val getCode = inviteObj.javaClass.getMethod("getCode")
                            val getGuild = inviteObj.javaClass.getMethod("getGuild")

                            val inviteCode = getCode.invoke(inviteObj) as? String ?: return
                            val guildObj = getGuild.invoke(inviteObj) ?: return

                            val getId = guildObj.javaClass.getMethod("getId")
                            val guildId = (getId.invoke(guildObj) as? Number)?.toLong()?.toString() ?: return

                            // استبدال مستمع النقر الأصلي بمستمع البلوقن
                            val originalClickListener = getOriginalClickListener(joinButton)

                            joinButton.setOnClickListener { v ->
                                Utils.threadPool.execute {
                                    val formFields = checkVerificationForm(guildId)
                                    if (formFields != null && formFields.length() > 0) {
                                        mainHandler.post {
                                            val manager = Utils.appActivity?.supportFragmentManager
                                            if (manager != null) {
                                                val sheet = ServerApplicationSheet(guildId, formFields, inviteCode, this@ServerApplicationFix)
                                                sheet.show(manager, "ServerApplicationSheet")
                                            }
                                        }
                                    } else {
                                        mainHandler.post {
                                            originalClickListener?.onClick(v)
                                        }
                                    }
                                }
                            }

                        } catch (e: Exception) {
                            logger.error("Error setting custom listener on join button", e)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            logger.error("Failed to hook WidgetGuildInvite", e)
        }
    }

    private fun getOriginalClickListener(view: View): View.OnClickListener? {
        return try {
            val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo")
            getListenerInfo.isAccessible = true
            val listenerInfo = getListenerInfo.invoke(view)
            val mOnClickListener = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
            mOnClickListener.isAccessible = true
            mOnClickListener.get(listenerInfo) as? View.OnClickListener
        } catch (e: Exception) {
            null
        }
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
            } else null
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
