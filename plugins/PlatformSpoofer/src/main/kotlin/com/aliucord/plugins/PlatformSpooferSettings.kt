package com.aliucord.plugins

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button

class PlatformSpooferSettings(private val plugin: PlatformSpoofer) : SettingsPage() {

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        setActionBarTitle("Platform Spoofer")

        val ctx = view.context
        val layout = getLinearLayout()
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()

        val statusText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(padding, padding, padding, padding * 2)
            text = "Your current platform (${plugin.settings.getString("platform", "Mobile")})"
        }
        layout.addView(statusText)

        val platforms = listOf(
            Triple("Mobile", "Mobile", "ic_phonelink_24dp"),
            Triple("Computer", "Computer", "ic_desktop_windows_24dp"),
            Triple("Web", "Web", "ic_public_24dp")
        )

        for ((id, name, iconName) in platforms) {
            val btn = Button(ctx).apply {
                text = name
                
                val iconRes = Utils.getResId(iconName, "drawable")
                if (iconRes != 0) {
                    val drawable = ContextCompat.getDrawable(ctx, iconRes)
                    drawable?.setTint(Color.WHITE)
                    setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
                    compoundDrawablePadding = padding
                }

                setOnClickListener {
                    plugin.settings.setString("platform", id)
                    statusText.text = "Your current platform ($id)"
                    Utils.showToast("Saved! Restart Discord to apply changes.")
                }
            }
            
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(padding, 0, padding, padding)
            }
            
            layout.addView(btn, params)
        }
    }
}
