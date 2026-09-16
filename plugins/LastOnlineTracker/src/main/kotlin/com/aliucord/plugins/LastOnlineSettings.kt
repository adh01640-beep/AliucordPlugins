package com.aliucord.plugins

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.aliucord.fragments.SettingsPage

class LastOnlineSettings(private val plugin: LastOnlineTracker) : SettingsPage() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        setActionBarTitle("Last Online Tracker")

        val context = view.context
        val layout = getLinearLayout()
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        layout.addView(TextView(context).apply {
            text = "Timestamp format (Java SimpleDateFormat pattern):"
            setPadding(padding, padding, padding, 0)
        })

        layout.addView(EditText(context).apply {
            setPadding(padding, padding / 2, padding, padding)
            setText(plugin.settings.getString("timestamp_format", plugin.defaultFormat))
            hint = plugin.defaultFormat
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val text = s?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        plugin.settings.setString("timestamp_format", text)
                    }
                }
            })
        })
    }
}
