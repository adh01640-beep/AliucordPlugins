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
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    // Grab the auth token directly from Discord's internal provider
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
            // Subtract 1 min just in case our phone's clock is slightly ahead of Discord's servers.
            // If we don't do this, the snowflake might be too new and we end up ignoring our own messages.
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
        val methods = storeMessagesClass.declaredMethods.filter { 
            it.name == "handleMessageCreate" || it.name == "handleMessageUpdate" 
        }
        
        for (method in methods) {
            patcher.patch(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!autoDeleteEnabled) return

                    try {
                        val arg = param.args.firstOrNull() ?: return
                        val messages = if (arg is Iterable<*>) arg.toList() else listOf(arg)

                        for (item in messages) {
                            if (item == null) continue
                            
                            // Dumping the object to JSON and parsing it back.
                            // Kinda hacky, but saves us from reflection hell and random crashes on different Discord versions.
                            val jsonStr = Utils.gson.toJson(item)
                            val jsonObj = JSONObject(jsonStr)
                            
                            val msgObj = jsonObj.optJSONObject("message") ?: jsonObj
                            
                            val authorObj = msgObj.optJSONObject("author")
                            val authorId = authorObj?.optString("id")?.toLongOrNull() ?: 0L
                            val myId = StoreStream.getUsers().me.id

                            // Double check if it's actually our message
                            if (authorId == myId && authorId != 0L) {
                                val channelId = msgObj.optString("channelId").toLongOrNull() ?: 0L
                                if (channelId != 0L) {
                                    // Trigger the fetch & delete cycle. 4 retries give images enough time to upload (~6s total)
                                    deleteLatestMessagesFromMe(channelId, 4)
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Just swallow it so we don't crash the UI thread
                    }
                }
            })
        }
    }

    // The magic trick: Discord creates fake "local" IDs for instant UI updates.
    // Deleting those local IDs obviously fails on the server.
    // Workaround: Wait a bit, fetch our recent msgs from the API to get the REAL server IDs, then wipe them.
    private fun deleteLatestMessagesFromMe(channelId: Long, retries: Int) {
        Utils.threadPool.execute {
            try {
                Thread.sleep(1500) // Let the server process the message first
                val url = "https://discord.com/api/v9/channels/$channelId/messages?limit=10"
                val response = Http.Request(url, "GET")
                    .setHeader("Authorization", authToken)
                    .execute()

                if (response.statusCode in 200..299) {
                    val responseText = response.text()
                    if (responseText.isNullOrEmpty()) return@execute
                    
                    val msgs = JSONArray(responseText)
                    val myId = StoreStream.getUsers().me.id.toString()
                    var foundAndDeleted = false
                    
                    for (i in 0 until msgs.length()) {
                        val msg = msgs.getJSONObject(i)
                        val author = msg.optJSONObject("author") ?: continue
                        val authorId = author.optString("id")
                        
                        if (authorId == myId) {
                            val msgId = msg.optString("id")
                            val msgIdLong = msgId.toLongOrNull() ?: 0L
                            
                            // Only delete if it was sent after we turned the feature on
                            if (msgIdLong > activationSnowflake) {
                                val delUrl = "https://discord.com/api/v9/channels/$channelId/messages/$msgId"
                                Http.Request(delUrl, "DELETE")
                                    .setHeader("Authorization", authToken)
                                    .execute()
                                foundAndDeleted = true
                            }
                        }
                    }
                    
                    // If we didn't find it (maybe it's a huge image still uploading), try again
                    if (!foundAndDeleted && retries > 0) {
                        deleteLatestMessagesFromMe(channelId, retries - 1)
                    }
                } else if (retries > 0) {
                    deleteLatestMessagesFromMe(channelId, retries - 1)
                }
            } catch (e: Exception) {
                if (retries > 0) {
                    deleteLatestMessagesFromMe(channelId, retries - 1)
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

                // Fire the POST request and instantly DELETE it once we get the real ID back
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

                        // Using PATCH directly since the Http wrapper handles it fine
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

