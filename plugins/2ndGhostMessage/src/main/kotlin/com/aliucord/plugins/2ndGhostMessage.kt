package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI.CommandResult
import com.aliucord.entities.Plugin
import com.aliucord.utils.DimenUtils
import com.discord.api.commands.ApplicationCommandType
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var autoDeleteEnabled = false
    private var activationSnowflake = 0L

    override fun start(context: Context) {
        registerAutoMessageCommand()
        registerAutoDeleteCommands()
        hookIncomingMessages()
    }

    private fun registerAutoMessageCommand() {
        val arguments = listOf(
            Utils.createCommandOption(
                ApplicationCommandType.STRING,
                "type",
                "Action type (type 'delete' or 'edit')",
                null,
                true
            )
        )

        commands.registerCommand(
            "automessage",
            "Send a message and instantly delete or edit it",
            arguments
        ) { ctx ->
            val type = ctx.getRequiredString("type").lowercase()
            val channelId = ctx.channelId
            val activityContext = Utils.appActivity

            if (activityContext == null) {
                return@registerCommand CommandResult("Error: App activity not found.", null, false)
            }

            if (type == "delete") {
                mainHandler.post { showDeleteDialog(activityContext, channelId) }
                CommandResult("Opening Delete Dialog...", null, false)
            } else if (type == "edit") {
                mainHandler.post { showEditDialog(activityContext, channelId) }
                CommandResult("Opening Edit Dialog...", null, false)
            } else {
                CommandResult("Invalid type. Please type 'delete' or 'edit'.", null, false)
            }
        }
    }

    private fun registerAutoDeleteCommands() {
        commands.registerCommand(
            "autodeleteon",
            "Automatically delete ALL messages and images you send from now on."
        ) {
            autoDeleteEnabled = true
            val adjustedTime = System.currentTimeMillis() - 60000L
            activationSnowflake = (adjustedTime - 1420070400000L) shl 22
            CommandResult("✅ Auto-delete is ON. New messages & attachments will be deleted.", null, false)
        }

        commands.registerCommand(
            "autodeleteoff",
            "Turn off automatic deletion."
        ) {
            autoDeleteEnabled = false
            CommandResult("❌ Auto-delete is OFF.", null, false)
        }
    }

    private fun hookIncomingMessages() {
        val storeMessagesClass = StoreStream.getMessages()::class.java
        // تتبع الإنشاء والتحديث لضمان اصطياد الصور بعد اكتمال الرفع
        val methods = storeMessagesClass.declaredMethods.filter { 
            it.name == "handleMessageCreate" || it.name == "handleMessageUpdate" 
        }
        
        for (method in methods) {
            patcher.patch(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!autoDeleteEnabled) return

                    try {
                        val arg = param.args.firstOrNull() ?: return
                        
                        // استخراج الرسايل سواء كانت قائمة (List) أو رسالة واحدة
                        val messages = if (arg is Iterable<*>) arg.toList() else listOf(arg)

                        for (message in messages) {
                            if (message == null) continue
                            val messageClass = message::class.java
                            
                            // التأكد إن ده أوبجكت رسالة فعلاً
                            val getAuthorMethod = try {
                                messageClass.getMethod("getAuthor")
                            } catch (e: Exception) { continue }
                            
                            val author = getAuthorMethod.invoke(message) ?: continue
                            
                            val authorIdRaw = try {
                                author::class.java.getMethod("getId").invoke(author)
                            } catch (e: Exception) { continue }
                            
                            val authorId = authorIdRaw.toString().toLongOrNull() ?: continue
                            val myId = StoreStream.getUsers().me.id.toString().toLongOrNull() ?: continue
                            
                            if (authorId == myId) {
                                val msgIdRaw = messageClass.getMethod("getId").invoke(message)
                                val msgId = msgIdRaw.toString().toLongOrNull() ?: continue
                                
                                if (msgId > activationSnowflake) {
                                    val channelIdRaw = messageClass.getMethod("getChannelId").invoke(message)
                                    val channelId = channelIdRaw.toString().toLongOrNull() ?: continue
                                    
                                    // تمرير الطلب لدالة الحذف مع إعطاءها 3 محاولات (Retries)
                                    deleteMessageById(channelId, msgId.toString(), 3)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // تجاهل الأخطاء العابرة
                    }
                }
            })
        }
    }

    // دالة حذف مجهزة بنظام انتظار ومحاولات لتخطي تأخير رفع الصور والمقاطع
    private fun deleteMessageById(channelId: Long, msgId: String, retries: Int) {
        Utils.threadPool.execute {
            try {
                Thread.sleep(1500) // انتظار ثانية ونصف لضمان وصول الرسالة للسيرفر
                val url = "https://discord.com/api/v9/channels/$channelId/messages/$msgId"
                val response = Http.Request(url, "DELETE")
                    .setHeader("Authorization", authToken)
                    .execute()

                // لو السيرفر رفض (مثلاً لسه بتترفع) وعندنا محاولات باقية، نجرب تاني
                if (response.statusCode !in 200..299 && retries > 0) {
                    deleteMessageById(channelId, msgId, retries - 1)
                }
            } catch (e: Exception) {
                if (retries > 0) {
                    deleteMessageById(channelId, msgId, retries - 1)
                }
            }
        }
    }

    private fun showDeleteDialog(context: Context, channelId: Long) {
        val input = EditText(context).apply {
            hint = "Message to send and immediately delete..."
        }
        AlertDialog.Builder(context)
            .setTitle("Ghost Delete")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    sendAndDelete(channelId, text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(context: Context, channelId: Long) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = DimenUtils.dpToPx(16)
            setPadding(padding, padding, padding, padding)
        }

        val inputBefore = EditText(context).apply { hint = "Message BEFORE edit..." }
        val inputAfter = EditText(context).apply { hint = "Message AFTER edit..." }

        layout.addView(inputBefore)
        layout.addView(inputAfter)

        AlertDialog.Builder(context)
            .setTitle("Ghost Edit")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val before = inputBefore.text.toString()
                val after = inputAfter.text.toString()
                if (before.isNotEmpty() && after.isNotEmpty()) {
                    sendAndEdit(channelId, before, after)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendAndDelete(channelId: Long, text: String) {
        Utils.threadPool.execute {
            try {
                val url = "https://discord.com/api/v9/channels/$channelId/messages"
                val body = mapOf("content" to text)

                val response = Http.Request(url, "POST")
                    .setHeader("Authorization", authToken)
                    .executeWithJson(body)

                if (response.statusCode in 200..299) {
                    val responseText = response.text()
                    if (!responseText.isNullOrEmpty()) {
                        val msgId = JSONObject(responseText).getString("id")
                        Http.Request("$url/$msgId", "DELETE")
                            .setHeader("Authorization", authToken)
                            .execute()
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in Ghost Delete", e)
            }
        }
    }

    private fun sendAndEdit(channelId: Long, before: String, after: String) {
        Utils.threadPool.execute {
            try {
                val url = "https://discord.com/api/v9/channels/$channelId/messages"
                val bodyBefore = mapOf("content" to before)

                val response = Http.Request(url, "POST")
                    .setHeader("Authorization", authToken)
                    .executeWithJson(bodyBefore)

                if (response.statusCode in 200..299) {
                    val responseText = response.text()
                    if (!responseText.isNullOrEmpty()) {
                        val msgId = JSONObject(responseText).getString("id")
                        val bodyAfter = mapOf("content" to after)

                        Http.Request("$url/$msgId", "PATCH")
                            .setHeader("Authorization", authToken)
                            .executeWithJson(bodyAfter)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in Ghost Edit", e)
            }
        }
    }

    override fun stop(context: Context) {
        commands.unregisterAll()
        patcher.unpatchAll()
    }
}

