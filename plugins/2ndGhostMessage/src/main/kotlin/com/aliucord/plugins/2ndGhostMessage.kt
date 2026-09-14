package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI.CommandResult
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.api.commands.ApplicationCommandType
import com.discord.utilities.rest.RestAPI
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val deleteDelay: Long
        get() = settings.getLong("delete_delay", 500L)

    private val editDelay: Long
        get() = settings.getLong("edit_delay", 600L)

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.PAGE)
    }

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

            mainHandler.post {
                val activity = Utils.appActivity
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    if (type == "delete") {
                        showDeleteDialog(activity, channelId)
                    } else if (type == "edit") {
                        showEditDialog(activity, channelId)
                    }
                } else {
                    Utils.showToast("Activity is not available, please try again.")
                }
            }

            if (type == "delete" || type == "edit") {
                CommandResult(null, null, false)
            } else {
                CommandResult("Invalid type. Please choose 'delete' or 'edit'.", null, false)
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

                val response = Http.Request(url, "POST")
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)

                if (response.statusCode in 200..299) {
                    val responseText = response.text()
                    if (!responseText.isNullOrEmpty()) {
                        val msgId = JSONObject(responseText).getString("id")
                        Thread.sleep(deleteDelay)

                        Http.Request("$url/$msgId", "DELETE")
                            .setHeader("Authorization", authToken)
                            .setHeader("Content-Type", "application/json")
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
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(bodyBefore)

                if (response.statusCode in 200..299) {
                    val responseText = response.text()
                    if (!responseText.isNullOrEmpty()) {
                        val msgId = JSONObject(responseText).getString("id")
                        val bodyAfter = mapOf("content" to after)

                        Thread.sleep(editDelay)

                        Http.Request("$url/$msgId", "PATCH")
                            .setHeader("Authorization", authToken)
                            .setHeader("Content-Type", "application/json")
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

    class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {
        override fun onViewBound(view: View) {
            super.onViewBound(view)
            setPadding(0)

            val ctx = view.context

            val deleteInput = TextInput(ctx, "Delete Delay (ms)").apply {
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setText(settings.getLong("delete_delay", 500L).toString())
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val value = editText.text.toString().toLongOrNull() ?: 500L
                        settings.setLong("delete_delay", value)
                    }
                }
            }

            val editInput = TextInput(ctx, "Edit Delay (ms)").apply {
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setText(settings.getLong("edit_delay", 600L).toString())
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val value = editText.text.toString().toLongOrNull() ?: 600L
                        settings.setLong("edit_delay", value)
                    }
                }
            }

            addView(deleteInput)
            addView(editInput)
        }
    }
}
