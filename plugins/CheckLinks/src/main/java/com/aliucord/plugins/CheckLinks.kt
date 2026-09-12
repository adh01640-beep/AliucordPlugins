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
                        val url = param.args.firstOrNull { 
                            it is String && (it.startsWith("http://") || it.startsWith("https://")) 
                        } as? String ?: return

                        if (url.contains("discord.com/channels") || url.contains("discordapp.com/channels")) {
                            return
                        }

                        param.result = null 
                        
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

        if (apiKey == "") {
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
                    // إظهار نافذة تأكيد عند فشل الفحص بدلاً من فتح الرابط مباشرة
                    AlertDialog.Builder(context)
                        .setTitle("Scan Failed")
                        .setMessage("Could not retrieve scan results from VirusTotal for this link. Do you still want to open it?\n\n$url")
                        .setPositiveButton("Open") { _, _ -> openUrl(context, url) }
                        .setNegativeButton("Cancel", null)
                        .show()
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

        val displayMessage = if (text.length == 0) "No per-engine details available." else text

        AlertDialog.Builder(context)
            .setTitle("Engine Results")
            .setMessage(displayMessage)
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
                if (key.length > 0) {
                    prefs.edit().putString(PREF_API_KEY, key).apply()
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

