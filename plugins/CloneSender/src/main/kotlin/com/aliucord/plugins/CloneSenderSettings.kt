package com.aliucord.plugins

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream

class CloneSenderSettings(private val plugin: CloneSender) : SettingsPage() {

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        setActionBarTitle("CloneSender Settings")

        val ctx = view.context
        val layout = getLinearLayout()
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()

        val myCurrentUserId = StoreStream.getUsers().me?.id?.toString() ?: "Unknown"

        val infoLabel = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Current Account ID: $myCurrentUserId\n\nEnter the authorized Account ID for this clone instance. If the ID here doesn't match this account, commands will be ignored."
            setPadding(padding, padding, padding, padding / 2)
        }
        layout.addView(infoLabel)

        val idInput = EditText(ctx).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#72767D"))
            hint = "e.g. 1001222848716738570"
            setText(plugin.settings.getString("bound_account_id", ""))
            setPadding(padding, padding, padding, padding)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val input = s?.toString()?.trim().orEmpty()
                    plugin.settings.setString("bound_account_id", input)
                }
            })
        }
        layout.addView(idInput)
    }
}
