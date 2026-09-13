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

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start(context: Context) {
        val inviteJoinHelper = Class.forName("com.discord.widgets.guilds.invite.InviteJoinHelper")
        val methods = inviteJoinHelper.declaredMethods.filter { it.name == "joinViaInvite" }

        for (method in methods) {
            patcher.patch(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val inviteObj = param.args[0] ?: return
                        val getCode = inviteObj.javaClass.getMethod("getCode")
                        val getGuild = inviteObj.javaClass.getMethod("getGuild")
                        
                        val inviteCode = getCode.invoke(inviteObj) as? String ?: return
                        val guildObj = getGuild.invoke(inviteObj) ?: return
                        
                        val getId = guildObj.javaClass.getMethod("getId")
                        val guildId = (getId.invoke(guildObj) as? Number)?.toLong()?.toString() ?: return

                        param.result = null
                        fetchAndShowApplication(inviteCode, guildId)
                    } catch (e: Exception) {}
                }
            })
        }
    }

    private fun fetchAndShowApplication(inviteCode: String, guildId: String) {
        Utils.threadPool.execute {
            try {
                val url = "https://discord.com/api/v9/guilds/$guildId/member-verification"
                val response = Http.Request(url, "GET")
                    .setHeader("Authorization", authToken)
                    .execute()

                if (response.statusCode in 200..299) {
                    val json = JSONObject(response.text())
                    val formFields = json.optJSONArray("form_fields") ?: JSONArray()

                    if (formFields.length() > 0) {
                        mainHandler.post {
                            val manager = Utils.appActivity?.supportFragmentManager ?: return@post
                            val sheet = ServerApplicationSheet(guildId, formFields, inviteCode, this)
                            sheet.show(manager, "ServerApplicationSheet")
                        }
                    } else {
                        joinGuild(inviteCode)
                    }
                } else {
                    joinGuild(inviteCode)
                }
            } catch (e: Exception) {
                joinGuild(inviteCode)
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
                    joinGuild(inviteCode)
                }
            } catch (e: Exception) {}
        }
    }

    private fun joinGuild(inviteCode: String) {
        Utils.threadPool.execute {
            try {
                val url = "https://discord.com/api/v9/invites/$inviteCode"
                Http.Request(url, "POST")
                    .setHeader("Authorization", authToken)
                    .execute()
            } catch (e: Exception) {}
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
