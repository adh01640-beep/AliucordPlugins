package com.aliucord.plugins

import android.util.Base64
import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.fromJson

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

private data class VtStats(
    val malicious: Int = 0,
    val suspicious: Int = 0,
    val harmless: Int = 0,
    val undetected: Int = 0,
    val timeout: Int = 0,
)

private data class VtEngineResult(val category: String)

private data class VtCachedAttributes(
    val last_analysis_stats: VtStats?,
    val last_analysis_results: Map<String, VtEngineResult>?,
)
private data class VtCachedData(val attributes: VtCachedAttributes?)
private data class VtCachedResponse(val data: VtCachedData?)

private data class VtAnalysisAttributes(
    val status: String?,
    val stats: VtStats?,
    val results: Map<String, VtEngineResult>?,
)
private data class VtAnalysisData(val attributes: VtAnalysisAttributes?)
private data class VtAnalysisResponse(val data: VtAnalysisData?)

private data class VtSubmitData(val id: String?)
private data class VtSubmitResponse(val data: VtSubmitData?)

object VirusTotalApi {
    private const val BASE = "https://www.virustotal.com/api/v3"

    fun check(url: String, apiKey: String): VtResult? {
        val urlId = Base64.encodeToString(
            url.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        // Fast path: VirusTotal already has a report for this exact URL
        val cachedRes = Http.Request("$BASE/urls/$urlId", "GET")
            .setHeader("x-apikey", apiKey)
            .execute()

        if (cachedRes.ok()) {
            val parsed = GsonUtils.gson.fromJson(cachedRes.text(), VtCachedResponse::class.java)
            val attrs = parsed?.data?.attributes
            if (attrs?.last_analysis_stats != null) {
                return buildResult(attrs.last_analysis_stats, attrs.last_analysis_results)
            }
        }

        // Not analyzed before: submit it for a fresh scan
        val submitRes = Http.Request("$BASE/urls", "POST")
            .setHeader("x-apikey", apiKey)
            .executeWithUrlEncodedForm(mapOf("url" to url))

        if (!submitRes.ok()) return null

        val submitParsed = GsonUtils.gson.fromJson(submitRes.text(), VtSubmitResponse::class.java)
        val analysisId = submitParsed?.data?.id ?: return null

        // Poll until the scan finishes (VT usually takes just a few seconds)
        repeat(10) {
            Thread.sleep(3000)

            val pollRes = Http.Request("$BASE/analyses/$analysisId", "GET")
                .setHeader("x-apikey", apiKey)
                .execute()

            if (!pollRes.ok()) return@repeat

            val pollParsed = GsonUtils.gson.fromJson(pollRes.text(), VtAnalysisResponse::class.java)
            val attrs = pollParsed?.data?.attributes ?: return@repeat

            if (attrs.status == "completed" && attrs.stats != null) {
                return buildResult(attrs.stats, attrs.results)
            }
        }

        return null
    }

    private fun buildResult(stats: VtStats, results: Map<String, VtEngineResult>?): VtResult {
        val total = stats.malicious + stats.suspicious + stats.harmless + stats.undetected + stats.timeout
        val unsafe = stats.malicious + stats.suspicious
        val safePercent = if (total == 0) 100 else (((total - unsafe).toDouble() / total) * 100).toInt()

        val entries = results.orEmpty()
            .map { (engine, res) -> VtEntry(engine, res.category) }
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
