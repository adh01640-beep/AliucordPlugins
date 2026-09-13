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
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // متغيرات التحكم في الحذف التلقائي
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
            // إنشاء Snowflake بناءً على الوقت الحالي لمنع حذف الرسائل القديمة عند التمرير
            activationSnowflake = (System.currentTimeMillis() - 1420070400000L) shl 22
            CommandResult("✅ Auto-delete is now ON. Every new message or attachment you send will be deleted immediately.", null, false)
        }

        commands.registerCommand(
            "autodeleteoff",
            "Turn off automatic deletion."
        ) {
            autoDeleteEnabled = false
            CommandResult("❌ Auto-delete is now OFF.", null, false)
        }
    }

    private fun hookIncomingMessages() {
        val storeMessagesClass = StoreStream.getMessages().javaClass
        val methods = storeMessagesClass.declaredMethods.filter { it.name == "handleMessageCreate" }
        
        for (method in methods) {
            // نستخدم method.parameterTypes لتجنب أي مشاكل في الـ Signatures بين إصدارات ديسكورد
            patcher.after(storeMessagesClass, method.name, method.parameterTypes) { param ->
                if (!autoDeleteEnabled) return@after

                try {
                    val message = param.args.firstOrNull() ?: return@after
                    
                    // استخدام الـ Reflection لضمان الوصول للخصائص بغض النظر عن نوع كلاس الرسالة
                    val getAuthorMethod = message.javaClass.getMethod("getAuthor")
                    val author = getAuthorMethod.invoke(message) ?: return@after
                    
                    val getAuthorIdMethod = author.javaClass.getMethod("getId")
                    val authorId = getAuthorIdMethod.invoke(author) as? Long ?: return@after
                    
                    val myId = StoreStream.getUsers().me.id
                    
                    // إذا كانت الرسالة مرسلة من حسابك أنت
                    if (authorId == myId) {
                        val getIdMethod = message.javaClass.getMethod("getId")
                        val msgId = getIdMethod.invoke(message) as? Long ?: return@after
                        
                        // نتحقق أن الرسالة جديدة (تم إرسالها بعد تفعيل الأمر) وليس رسالة قديمة يتم تحميلها
                        if (msgId > activationSnowflake) {
                            val getChannelIdMethod = message.javaClass.getMethod("getChannelId")
                            val channelId = getChannelIdMethod.invoke(message) as? Long ?: return@after
                            
                            deleteMessageById(channelId, msgId.toString())
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل الأخطاء الصامتة الناتجة عن أنواع مختلفة من الحزم
                }
            }
        }
    }

    private fun deleteMessageById(channelId: Long, msgId: String) {
        Utils.threadPool.execute {
            try {
                val url = "https://discord.com/api/v9/channels/$channelId/messages/$msgId"
                Http.Request(url, "DELETE")
                    .setHeader("Authorization", authToken)
                    .execute()
            } catch (e: Exception) {
                logger.error("AutoDelete Failed", e)
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
                    val msgId = JSONObject(response.text()).getString("id")
                    Http.Request("$url/$msgId", "DELETE")
                        .setHeader("Authorization", authToken)
                        .execute()
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
                    val msgId = JSONObject(response.text()).getString("id")
                    val bodyAfter = mapOf("content" to after)

                    Http.Request("$url/$msgId", "POST")
                        .setHeader("Authorization", authToken)
                        .setHeader("X-HTTP-Method-Override", "PATCH")
                        .executeWithJson(bodyAfter)
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

