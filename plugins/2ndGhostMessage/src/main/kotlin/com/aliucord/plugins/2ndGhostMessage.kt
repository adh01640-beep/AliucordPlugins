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
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    // user authtoken it will be used on message edit/delete/send
    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start(context: Context) {
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
                CommandResult("working (:", null, false)
            } else if (type == "edit") {
                mainHandler.post { showEditDialog(activityContext, channelId) }
                CommandResult("working (:", null, false)
            } else {
                CommandResult("invalid type please write 'delete' or 'edit'.", null, false)
            }
        }
    }

    private fun showDeleteDialog(context: Context, channelId: Long) {
        val input = EditText(context).apply {
            hint = "Message to send and immediately delete"
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

        val inputBefore = EditText(context).apply { hint = "Message BEFORE edit here" }
        val inputAfter = EditText(context).apply { hint = "Message AFTER edit here" }

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

                // send it then wipe it right away
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

                        // patch it quick
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
    }
}

