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
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.utils.DimenUtils
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.input.WidgetChatInputEditText
import org.json.JSONObject
import java.lang.Exception

@AliucordPlugin(requiresRestart = false)
class GhostMessage : Plugin() {

    companion object {
        private const val MAX_CLIMB = 6
    }

    // Both endpoints need the current user's token in the Authorization header.
    // RestAPI.AppHeadersProvider is a Java-style singleton on Discord's side, so
    // the token must be fetched via the explicit getter, not as a Kotlin property.
    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()

    override fun start(context: Context) {
        // WidgetChatInput.onViewBound(View) fires with the ROOT view of the whole
        // chat input screen (edit text + attach/emoji/send buttons all included).
        // This is the confirmed real hook point - WidgetChatInputEditText itself
        // does NOT have an onViewBound method, only onKey/onEditorAction.
        patcher.after<WidgetChatInput>("onViewBound", View::class.java) { (_, root: View) ->
            // Locate the actual EditText inside the fragment's view tree
            val editText = findEditText(root) ?: run {
                logger.error("GhostMessage: could not find WidgetChatInputEditText in the chat input view tree", null)
                return@after
            }

            // From the EditText, climb up until we find the row that already
            // holds more than one child - that's the real icon row (attach,
            // emoji, send, etc.), since the EditText's direct parent is usually
            // just a thin auto-grow wrapper.
            val parentLayout = findIconRow(editText) ?: return@after

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

            // Add the button at the start of the row, next to the other icons
            parentLayout.addView(ghostBtn, 0)
        }
    }

    // Recursively search the view tree for the chat input EditText instance
    private fun findEditText(view: View): WidgetChatInputEditText? {
        if (view is WidgetChatInputEditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findEditText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // Walk up the view tree from the EditText until a ViewGroup with more than
    // one child is found (the actual icon row), instead of assuming a fixed depth.
    private fun findIconRow(start: View): ViewGroup? {
        var current: View = start
        repeat(MAX_CLIMB) {
            val parent = current.parent as? ViewGroup ?: return null
            if (parent.childCount > 1) return parent
            current = parent
        }
        logger.error("GhostMessage: could not locate the chat input icon row after climbing $MAX_CLIMB levels", null)
        return null
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
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)

                if (response.statusCode in 200..299) {
                    val msgId = JSONObject(response.text()).getString("id")
                    // Immediately delete the message using its ID
                    Http.Request("$url/$msgId", "DELETE")
                        .setHeader("Authorization", authToken)
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
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(bodyBefore)

                if (response.statusCode in 200..299) {
                    val msgId = JSONObject(response.text()).getString("id")
                    val bodyAfter = JSONObject().put("content", after).toString()

                    // Immediately edit it to the real (second) message
                    Http.Request("$url/$msgId", "PATCH")
                        .setHeader("Authorization", authToken)
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
