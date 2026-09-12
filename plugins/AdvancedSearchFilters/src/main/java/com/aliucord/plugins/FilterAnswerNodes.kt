package com.aliucord.plugins

import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.answer.AnswerNode
import com.discord.utilities.search.validation.SearchData
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

private const val DISCORD_EPOCH_MS = 1420070400000L
private val DATE_PATTERN: Pattern = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$")

internal fun parseIsoDate(raw: String): Triple<Int, Int, Int>? {
    val m = DATE_PATTERN.matcher(raw.trim())
    if (!m.matches()) return null
    return try {
        val y = m.group(1)!!.toInt()
        val mo = m.group(2)!!.toInt()
        val d = m.group(3)!!.toInt()
        if (mo !in 1..12 || d !in 1..31) null else Triple(y, mo, d)
    } catch (e: NumberFormatException) {
        null
    }
}

internal fun snowflakeForDayBoundary(year: Int, month: Int, day: Int, endOfDay: Boolean): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    if (endOfDay) {
        cal.set(year, month - 1, day, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
    } else {
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
    val snowflake = (cal.timeInMillis - DISCORD_EPOCH_MS) shl 22
    return snowflake.toString()
}

internal class DateAnswerNode(private val rawText: String) : AnswerNode() {
    override fun getValidFilters(): Set<FilterType> =
        setOf(SearchFilterTypes.BEFORE, SearchFilterTypes.AFTER, SearchFilterTypes.ON)

    override fun isValid(searchData: SearchData?): Boolean = parseIsoDate(rawText) != null

    override fun getText(): String = rawText

    override fun updateQuery(builder: SearchQuery.Builder, searchData: SearchData?, filterType: FilterType?) {
        val (y, mo, d) = parseIsoDate(rawText) ?: return
        when {
            filterType === SearchFilterTypes.BEFORE ->
                builder.appendParam("max_id", snowflakeForDayBoundary(y, mo, d, endOfDay = false))
            filterType === SearchFilterTypes.AFTER ->
                builder.appendParam("min_id", snowflakeForDayBoundary(y, mo, d, endOfDay = true))
            filterType === SearchFilterTypes.ON -> {
                builder.appendParam("min_id", snowflakeForDayBoundary(y, mo, d, endOfDay = false))
                builder.appendParam("max_id", snowflakeForDayBoundary(y, mo, d, endOfDay = true))
            }
        }
    }
}

internal class AuthorTypeAnswerNode(private val rawText: String) : AnswerNode() {
    companion object {
        val VALID_VALUES = setOf("user", "bot", "webhook")
    }

    override fun getValidFilters(): Set<FilterType> = setOf(SearchFilterTypes.AUTHOR_TYPE)

    override fun isValid(searchData: SearchData?): Boolean = rawText.lowercase() in VALID_VALUES

    override fun getText(): String = rawText

    override fun updateQuery(builder: SearchQuery.Builder, searchData: SearchData?, filterType: FilterType?) {
        if (rawText.lowercase() !in VALID_VALUES) return
        builder.appendParam("author_type", rawText.lowercase())
    }
}
