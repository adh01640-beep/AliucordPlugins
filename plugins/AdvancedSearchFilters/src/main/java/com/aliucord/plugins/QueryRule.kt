package com.aliucord.plugins

import android.content.Context
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.search.query.node.QueryNode
import java.util.regex.Matcher
import java.util.regex.Pattern

internal typealias ParserRule = Rule<Context, QueryNode, Any>

internal class QueryRule(
    regex: Pattern,
    private val onMatch: (matcher: Matcher, state: Any?) -> ParseSpec<Context, Any?>,
) : ParserRule(regex) {
    override fun parse(
        matcher: Matcher,
        parser: Parser<Context, in QueryNode, in Any?>,
        obj: Any?,
    ): ParseSpec<Context, in Any?> = onMatch(matcher, obj)
}
