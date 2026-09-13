package com.aliucord.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin(requiresRestart = false)
class ServerApplicationFix : Plugin() {

    init {
        settingsTab = SettingsTab(ApplicationsSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isBypassing = AtomicBoolean(false)

    private fun createObservable(onSubscribe: (Any) -> Unit): Any {
        val onSubscribeClass = Class.forName("rx.Observable\$OnSubscribe")
        val proxy = Proxy.newProxyInstance(onSubscribeClass.classLoader, arrayOf(onSubscribeClass)) { _, method, args ->
            if (method.name == "call" && args != null && args.isNotEmpty()) onSubscribe(args[0])
            null
        }
        return Class.forName("rx.Observable").getMethod("create", onSubscribeClass).invoke(null, proxy)!!
    }

    private fun subscriberOnNext(subscriber: Any, value: Any?) {
        subscriber.javaClass.getMethod("onNext", Any::class.java).invoke(subscriber, value)
    }

    private fun subscriberOnError(subscriber: Any, error: Throwable) {
        subscriber.javaClass.getMethod("onError", Throwable::class.java).invoke(subscriber, error)
    }

    private fun subscriberOnCompleted(subscriber: Any) {
        subscriber.javaClass.getMethod("onCompleted").invoke(subscriber)
    }

    private fun subscribeToObservable(
        observable: Any,
        onNext: (Any?) -> Unit,
        onError: (Throwable) -> Unit,
        onCompleted: () -> Unit,
    ) {
        val action1Class = Class.forName("rx.functions.Action1")
        val action0Class = Class.forName("rx.functions.Action0")

        val onNextProxy = Proxy.newProxyInstance(action1Class.classLoader, arrayOf(action1Class)) { _, method, args ->
            if (method.name == "call") onNext(if (args != null && args.isNotEmpty()) args[0] else null)
            null
        }
        val onErrorProxy = Proxy.newProxyInstance(action1Class.classLoader, arrayOf(action1Class)) { _, method, args ->
            if (method.name == "call" && args != null && args.isNotEmpty()) onError(args[0] as Throwable)
            null
        }
        val onCompletedProxy = Proxy.newProxyInstance(action0Class.classLoader, arrayOf(action0Class)) { _, method, _ ->
            if (method.name == "call") onCompleted()
            null
        }

        observable.javaClass
            .getMethod("subscribe", action1Class, action1Class, action0Class)
            .invoke(observable, onNextProxy, onErrorProxy, onCompletedProxy)
    }

    override fun start(context: Context) {
        try {
            val postInviteCode = RestAPI::class.java.getDeclaredMethod(
                "postInviteCode",
                ModelInvite::class.java,
                String::class.java,
                RestAPIParams.InviteCode::class.java,
            )

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

                        val guildName = try {
                            guildObj.javaClass.getMethod("getName").invoke(guildObj) as? String
                        } catch (e: Exception) {
                            null
                        } ?: "Unknown Server"

                        param.result = createObservable { subscriber ->
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
                                                    val sheet = ServerApplicationSheet(guildId, guildName, formFields, inviteCode, this@ServerApplicationFix)
                                                    sheet.show(manager, "ServerApplicationSheet")
                                                }
                                            }
                                            subscriberOnError(subscriber, Exception("Requires Application"))
                                            return@execute
                                        }
                                    }

                                    isBypassing.set(true)
                                    try {
                                        val targetMethod = param.method as Method
                                        val origObs = targetMethod.invoke(param.thisObject, *param.args)!!
                                        subscribeToObservable(
                                            origObs,
                                            onNext = { result -> subscriberOnNext(subscriber, result) },
                                            onError = { error -> subscriberOnError(subscriber, error) },
                                            onCompleted = { subscriberOnCompleted(subscriber) },
                                        )
                                    } catch (e: Exception) {
                                        subscriberOnError(subscriber, e)
                                    } finally {
                                        isBypassing.set(false)
                                    }

                                } catch (e: Exception) {
                                    subscriberOnError(subscriber, e)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        } catch (e: Exception) {}
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
