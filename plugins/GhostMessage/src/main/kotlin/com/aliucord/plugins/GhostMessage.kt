package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.utils.DimenUtils
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.chat.input.WidgetChatInputEditText
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    override fun start(context: Context) {
        // Inject a button into the chat input box
        patcher.after(WidgetChatInputEditText::class.java, "onViewBound", View::class.java) { param ->
            val view = param.args[0] as View

            // Grab the parent container that holds the input field and side buttons
            val parentLayout = view.parent as? ViewGroup ?: return@after

            // Avoid adding the button twice if this callback fires more than once
            if (parentLayout.findViewWithTag<View>("ghost_btn") != null) return@after

            val ghostBtn = ImageButton(context).apply {
                tag = "ghost_btn"
                // Built-in Android trash icon, no custom resource needed
                setImageDrawable(context.getDrawable(android.R.drawable.ic_menu_delete))
                setBackgroundColor(Color.TRANSPARENT)

                // Size and spacing for the button
                layoutParams = LinearLayout.LayoutParams(
                    DimenUtils.dpToPx(40),
                    DimenUtils.dpToPx(40)
                ).apply {
                    setMargins(0, 0, DimenUtils.dpToPx(8), 0)
                }

                setOnClickListener {
                    val channelId = StoreStream.getChannelsSelected().id
                    if (channelId != 0L) {
                        showModeSelectionDialog(context, channelId)
                    } else {
                        Utils.showToast("Cannot determine current channel.")
                    }
                }
            }

            // Add the button at the start of the container, next to the other buttons
            parentLayout.addView(ghostBtn, 0)
        }
    }

    private fun showModeSelectionDialog(context: Context, channelId: Long) {
        val options = arrayOf("Instant Delete", "Instant Edit")
        AlertDialog.Builder(context)
            .setTitle("Ghost Message Mode")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showDeleteDialog(context, channelId)
                    1 -> showEditDialog(context, channelId)
                }
            }
            .show()
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
                val body = JSONObject().put("content", text).toString()

                // Send the message
                val response = Http.Request(url, "POST")
                    .setHeader("Authorization", RestAPI.AppHeadersProvider.authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)

                if (response.statusCode in 200..299) {
                    val msgId = JSONObject(response.text()).getString("id")
                    // Immediately delete the message using its ID
                    Http.Request("$url/$msgId", "DELETE")
                        .setHeader("Authorization", RestAPI.AppHeadersProvider.authToken)
                        .execute()
                } else {
                    logger.error("Failed to send message for Ghost Delete: ${response.statusCode}", null)
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
                val bodyBefore = JSONObject().put("content", before).toString()

                // Send the first (decoy) message
                val response = Http.Request(url, "POST")
                    .setHeader("Authorization", RestAPI.AppHeadersProvider.authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(bodyBefore)

                if (response.statusCode in 200..299) {
                    val msgId = JSONObject(response.text()).getString("id")
                    val bodyAfter = JSONObject().put("content", after).toString()

                    // Immediately edit it to the real (second) message
                    Http.Request("$url/$msgId", "PATCH")
                        .setHeader("Authorization", RestAPI.AppHeadersProvider.authToken)
                        .setHeader("Content-Type", "application/json")
                        .executeWithJson(bodyAfter)
                } else {
                    logger.error("Failed to send message for Ghost Edit: ${response.statusCode}", null)
                }
            } catch (e: Exception) {
                logger.error("Error in Ghost Edit", e)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
