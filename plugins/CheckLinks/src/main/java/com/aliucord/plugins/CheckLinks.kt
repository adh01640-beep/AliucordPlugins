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

            for (method in methods) {
                patcher.patch(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // سحب الرابط الحي من البارامترات سواء كان String أو Uri
                        val urlStr = param.args.firstOrNull { 
                            it is String && (it.startsWith("http://") || it.startsWith("https://")) 
                        } as? String
                        
                        val uriObj = param.args.firstOrNull { it is Uri } as? Uri
                        val finalUrl = urlStr ?: uriObj?.toString() ?: return

                        // تجاهل روابط ديسكورد الداخلية
                        if (finalUrl.contains("discord.com/channels") || finalUrl.contains("discordapp.com/channels")) {
                            return
                        }

                        // سحب الشاشة الحالية بدقة لتفادي الكراش
                        val currentContext = param.args.firstOrNull { it is Context } as? Context ?: Utils.appActivity ?: return

                        // إيقاف فتح الرابط الافتراضي
                        param.result = null 

                        mainHandler.post {
                            handleLinkClick(currentContext, finalUrl)
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

        try {
            Toast.makeText(context, "Checking link with VirusTotal...", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {}

        Utils.threadPool.execute {
            val result = try {
                VirusTotalApi.check(url, apiKey)
            } catch (e: Throwable) {
                logger.error("Failed to check URL $url", e)
                null
            }

            mainHandler.post {
                if (result == null) {
                    try {
                        AlertDialog.Builder(context)
                            .setTitle("Scan Failed")
                            .setMessage("Could not retrieve scan results from VirusTotal for this link. Do you still want to open it?\n\n$url")
                            .setPositiveButton("Open") { _, _ -> openUrl(context, url) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } catch (e: Throwable) {
                        openUrl(context, url) // خطة بديلة لو الشاشة ماتت
                    }
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

        try {
            AlertDialog.Builder(context)
                .setTitle("Link Safety Check")
                .setMessage(message)
                .setPositiveButton("Open") { _, _ -> openUrl(context, url) }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Details") { _, _ -> showDetailsDialog(context, result) }
                .show()
        } catch (e: Throwable) {
            openUrl(context, url) // خطة بديلة لعدم تجميد الرابط
        }
    }

    private fun showDetailsDialog(context: Context, result: VtResult) {
        val text = result.entries.joinToString("\n") { "${it.engine}: ${it.category}" }
        val displayMessage = if (text.length == 0) "No per-engine details available." else text

        try {
            AlertDialog.Builder(context)
                .setTitle("Engine Results")
                .setMessage(displayMessage)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Throwable) {}
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            try {
                // محاولة أخيرة بـ AppContext الأساسي
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Utils.appContext.startActivity(fallbackIntent)
            } catch (e2: Throwable) {
                logger.error("Completely failed to open link", e2)
            }
        }
    }

    private fun promptForApiKey(context: Context, onSaved: () -> Unit) {
        try {
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
        } catch (e: Throwable) {
            logger.error("Failed to show API key dialog", e)
        }
    }
}
