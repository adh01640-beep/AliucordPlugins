package com.aliucord.plugins

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button
import com.discord.views.CheckedSetting

class InstantFinishSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Instant Finish Quests Settings")

        val ctx = view.context

        val autoFinishToggle = Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Auto Finish Quests", "Automatically completes available quests in the background.")
        autoFinishToggle.isChecked = settings.getBool("auto_finish", false)
        autoFinishToggle.setOnCheckedListener {
            settings.setBool("auto_finish", it)
        }
        linearLayout.addView(autoFinishToggle)

        val streamHeader = TextView(ctx).apply {
            text = "Stream Quests Bypass Configuration"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(Utils.dpToPx(16), Utils.dpToPx(16), Utils.dpToPx(16), Utils.dpToPx(8))
        }
        linearLayout.addView(streamHeader)

        linearLayout.addView(createInput(ctx, "Voice Channel ID", "voice_id"))
        linearLayout.addView(createInput(ctx, "Server ID", "server_id"))
        linearLayout.addView(createInput(ctx, "Alt Account Token", "alt_token"))
    }

    private fun createInput(ctx: android.content.Context, hintText: String, key: String): EditText {
        return EditText(ctx).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#72767D"))
            hint = hintText
            setText(settings.getString(key, ""))
            setPadding(Utils.dpToPx(16), Utils.dpToPx(16), Utils.dpToPx(16), Utils.dpToPx(16))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    settings.setString(key, s?.toString()?.trim() ?: "")
                }
            })
        }
    }
}
