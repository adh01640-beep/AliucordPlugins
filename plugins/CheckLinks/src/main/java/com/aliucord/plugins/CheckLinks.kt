package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = false)
class CheckLinks : Plugin() {

    companion object {
        private val logger = Logger("CheckLinks")
        private const val PREFS_NAME = "CheckLinksPrefs"
        private const val PREF_API_KEY = "vt_api_key"
    }

    private val cache = ConcurrentHashMap<String, VtResult>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private val apiKey: String
        get() = prefs.getString(PREF_API_KEY, "") ?: ""

    override fun start(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val uriHandlerClass = Class.forName("com.discord.utilities.uri.UriHandler")
            
            // Universal Hook: Catch ALL routing methods (handle, handle$default, openUrl, etc.)
            val methods = uriHandlerClass.declaredMethods.filter { 
                it.name.startsWith("handle") || it.name.startsWith("openUrl")
            }

            if (methods.isEmpty()) {
                logger.error("No URL handling methods found in UriHandler!", null)
                return
            }

            for (method in methods) {
                patcher.patch(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Dynamically find the URL argument regardless of the method signature
                        val url = param.args.firstOrNull { 
                            it is String && (it.startsWith("http://") || it.startsWith("https://")) 
                        } as? String ?: return

                        // Ignore Discord internal deep links (e.g. mentions, channels)
                        if (url.contains("discord.com/channels") || url.contains("discordapp.com/channels")) {
                            return
                        }

                        // Halt Discord's default behavior completely
                        param.result = null 
                        
                        // Use AppActivity to ensure Dialogs don't crash the WindowManager
                        val activityContext = Utils.appActivity ?: param.args.firstOrNull { it is Context } as? Context ?: return

                        mainHandler.post {
                            handleLinkClick(activityContext, url)
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            logger.error("Failed to initialize CheckLinks hook", e)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        cache.clear()
    }

    private fun handleLinkClick(context: Context, url: String) {
        val cached = cache[url]
        if (cached != null) {
            showResultDialog(context, url, cached)
            return
        }

        if (apiKey.isBlank()) {
            promptForApiKey(context) { handleLinkClick(context, url) }
            return
        }

        Toast.makeText(context, "Checking link with VirusTotal...", Toast.LENGTH_SHORT).show()

        Utils.threadPool.execute {
            val result = try {
                VirusTotalApi.check(url, apiKey)
            } catch (e: Throwable) {
                logger.error("Failed to check URL $url", e)
                null
            }

            mainHandler.post {
                if (result == null) {
                    Toast.makeText(context, "Couldn't check link, opening anyway", Toast.LENGTH_SHORT).show()
                    openUrl(context, url)
                } else {
                    cache[url] = result
                    showResultDialog(context, url, result)
                }
            }
        }
    }

    private fun showResultDialog(context: Context, url: String, result: VtResult) {
        val message = buildString {
            if (result.totalEngines == 0) {
                append("No engines have flagged this link yet — it may be too new or unscanned.\n\n")
            } else {
                append("${result.safePercent}% safe (${result.totalEngines} engines checked)\n\n")
                append("Malicious: ${result.malicious}\n")
                append("Suspicious: ${result.suspicious}\n")
                append("Harmless: ${result.harmless}\n")
                append("Undetected: ${result.undetected}\n\n")
            }
            append(url)
        }

        AlertDialog.Builder(context)
            .setTitle("Link Safety Check")
            .setMessage(message)
            .setPositiveButton("Open") { _, _ -> openUrl(context, url) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Details") { _, _ -> showDetailsDialog(context, result) }
            .show()
    }

    private fun showDetailsDialog(context: Context, result: VtResult) {
        val text = result.entries.joinToString("\n") { "${it.engine}: ${it.category}" }

        AlertDialog.Builder(context)
            .setTitle("Engine Results")
            .setMessage(text.ifBlank { "No per-engine details available." })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptForApiKey(context: Context, onSaved: () -> Unit) {
        val input = EditText(context).apply {
            hint = "VirusTotal API key"
        }
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            setPadding(padding, padding, padding, padding)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle("VirusTotal API Key Required")
            .setMessage("Get a free key at virustotal.com, then paste it below. You only need to do this once.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit().putString(PREF_API_KEY, key).apply()
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
