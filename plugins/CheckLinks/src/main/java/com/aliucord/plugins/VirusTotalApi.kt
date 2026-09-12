package com.aliucord.plugins

import android.util.Base64
import com.aliucord.Http
import org.json.JSONObject

data class VtEntry(val engine: String, val category: String)

data class VtResult(
    val safePercent: Int,
    val malicious: Int,
    val suspicious: Int,
    val harmless: Int,
    val undetected: Int,
    val totalEngines: Int,
    val entries: List<VtEntry>,
)

private data class VtStatsHelper(
    val malicious: Int,
    val suspicious: Int,
    val harmless: Int,
    val undetected: Int,
    val timeout: Int,
)

object VirusTotalApi {
    private const val BASE = "https://www.virustotal.com/api/v3"

    fun check(url: String, apiKey: String): VtResult? {
        val urlId = Base64.encodeToString(
            url.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        // Fast path: Check if VirusTotal already has a cached report for this URL
        val cachedRes = Http.Request("$BASE/urls/$urlId", "GET")
            .setHeader("x-apikey", apiKey)
            .execute()

        if (cachedRes.ok()) {
            try {
                val root = JSONObject(cachedRes.text())
                val attrs = root.optJSONObject("data")?.optJSONObject("attributes")
                val statsObj = attrs?.optJSONObject("last_analysis_stats")
                if (statsObj != null) {
                    val stats = parseStats(statsObj)
                    val resultsMap = parseResults(attrs.optJSONObject("last_analysis_results"))
                    return buildResult(stats, resultsMap)
                }
            } catch (e: Throwable) {
                // Ignore parse errors and proceed to submit
            }
        }

        // If not cached, submit the URL for a fresh analysis scan
        val submitRes = Http.Request("$BASE/urls", "POST")
            .setHeader("x-apikey", apiKey)
            .executeWithUrlEncodedForm(mapOf("url" to url))

        if (!submitRes.ok()) return null

        val analysisId = try {
            val root = JSONObject(submitRes.text())
            root.optJSONObject("data")?.optString("id")
        } catch (e: Throwable) {
            null
        } ?: return null

        // Poll analysis status until completion
        repeat(10) {
            Thread.sleep(3000)

            val pollRes = Http.Request("$BASE/analyses/$analysisId", "GET")
                .setHeader("x-apikey", apiKey)
                .execute()

            if (!pollRes.ok()) return@repeat

            try {
                val root = JSONObject(pollRes.text())
                val attrs = root.optJSONObject("data")?.optJSONObject("attributes")
                val status = attrs?.optString("status")
                val statsObj = attrs?.optJSONObject("stats")

                if (status == "completed" && statsObj != null) {
                    val stats = parseStats(statsObj)
                    val resultsMap = parseResults(attrs.optJSONObject("results"))
                    return buildResult(stats, resultsMap)
                }
            } catch (e: Throwable) {
                // Continue polling on parse failure
            }
        }

        return null
    }

    private fun parseStats(obj: JSONObject): VtStatsHelper {
        return VtStatsHelper(
            malicious = obj.optInt("malicious", 0),
            suspicious = obj.optInt("suspicious", 0),
            harmless = obj.optInt("harmless", 0),
            undetected = obj.optInt("undetected", 0),
            timeout = obj.optInt("timeout", 0),
        )
    }

    private fun parseResults(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val engineName = keys.next()
            val engineObj = obj.optJSONObject(engineName)
            val category = engineObj?.optString("category") ?: "unknown"
            map[engineName] = category
        }
        return map
    }

    private fun buildResult(stats: VtStatsHelper, results: Map<String, String>): VtResult {
        val total = stats.malicious + stats.suspicious + stats.harmless + stats.undetected + stats.timeout
        val unsafe = stats.malicious + stats.suspicious
        val safePercent = if (total == 0) 100 else (((total - unsafe).toDouble() / total) * 100).toInt()

        val entries = results.map { (engine, category) -> VtEntry(engine, category) }
            .sortedBy { it.category }

        return VtResult(
            safePercent = safePercent,
            malicious = stats.malicious,
            suspicious = stats.suspicious,
            harmless = stats.harmless,
            undetected = stats.undetected,
            totalEngines = total,
            entries = entries,
        )
    }
}

