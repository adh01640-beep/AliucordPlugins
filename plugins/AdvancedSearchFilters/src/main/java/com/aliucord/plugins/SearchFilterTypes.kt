package com.aliucord.plugins

import com.discord.utilities.search.query.FilterType

internal object SearchFilterTypes {
    lateinit var BEFORE: FilterType
    lateinit var AFTER: FilterType
    lateinit var ON: FilterType
    lateinit var AUTHOR_TYPE: FilterType

    @Volatile
    var ready: Boolean = false

    fun keywordFor(type: FilterType?): String? = when {
        !ready -> null
        type === BEFORE -> "before"
        type === AFTER -> "after"
        type === ON -> "on"
        type === AUTHOR_TYPE -> "type"
        else -> null
    }

    fun labelFor(type: FilterType?): String? = when {
        !ready -> null
        type === BEFORE -> "Before a date"
        type === AFTER -> "After a date"
        type === ON -> "Sent on a date"
        type === AUTHOR_TYPE -> "By author type"
        else -> null
    }

    fun isOurs(type: FilterType?): Boolean = ready && keywordFor(type) != null

    fun all(): List<FilterType> = listOf(ON, BEFORE, AFTER, AUTHOR_TYPE)
}
