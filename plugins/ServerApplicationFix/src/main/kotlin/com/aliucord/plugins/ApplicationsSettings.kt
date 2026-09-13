package com.aliucord.plugins

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.aliucord.fragments.SettingsPage

class ApplicationsSettings(private val plugin: ServerApplicationFix) : SettingsPage() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        setActionBarTitle("Server Applications")
        setActionBarSubtitle("Status of servers you've applied to")

        val context = view.context
        val layout = getLinearLayout()

        val applications = plugin.getTrackedApplications()

        if (applications.isEmpty()) {
            layout.addView(TextView(context).apply {
                text = "You haven't applied to any servers yet."
                val padding = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            })
            return
        }

        for ((guildId, guildName) in applications) {
            val row = TextView(context).apply {
                text = "$guildName — Checking..."
                val paddingH = (16 * context.resources.displayMetrics.density).toInt()
                val paddingV = (12 * context.resources.displayMetrics.density).toInt()
                setPadding(paddingH, paddingV, paddingH, paddingV)
            }
            layout.addView(row)

            plugin.threadPool.execute {
                val status = plugin.fetchApplicationStatus(guildId)
                Handler(Looper.getMainLooper()).post {
                    row.text = "$guildName — $status"
                }
            }
        }
    }
}
