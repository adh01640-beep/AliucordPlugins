package com.aliucord.plugins

import android.content.Context
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.before
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.patcher.component3
import com.aliucord.patcher.component4
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.stores.StoreSearch
import com.discord.stores.StoreSearchInput
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.utilities.search.network.SearchFetcher
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.SearchSuggestionEngine
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.SearchSuggestion
import com.discord.widgets.search.suggestions.WidgetSearchSuggestionsAdapter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.regex.Pattern

@AliucordPlugin(requiresRestart = false)
class AdvancedSearchFilters : Plugin() {

    companion object {
        val logger = Logger("AdvancedSearchFilters")
    }

    private val valuesField by lazy {
        FilterType::class.java.getDeclaredField("\$VALUES").apply { isAccessible = true }
    }
    private val rulesField by lazy {
        Parser::class.java.getDeclaredField("rules").apply { isAccessible = true }
    }
    private val replaceAndPublish by lazy {
        StoreSearchInput::class.java.getDeclaredMethod(
            "replaceAndPublish",
            Int::class.javaPrimitiveType!!,
            List::class.java,
            List::class.java,
        ).apply { isAccessible = true }
    }

    private val placeholder by lazy { Utils.getResId("search_filter_from", "string") }
    // Icon confirmed to exist on-device (used by other working Aliucord plugins);
    // reused here to avoid crashing on an icon lookup for an unknown FilterType.
    private val filterIcon by lazy { Utils.getResId("ic_text_channel_white_24dp", "drawable") }
    private var origFilterTypes: Array<FilterType>? = null

    override fun start(context: Context) {
        try {
            extendFilterType()
            SearchFilterTypes.ready = true
            patchQueryParser()
            patchSuggestionUi()
            patchFilterClicked()
            patchAuthorTypeFilter()
        } catch (e: Throwable) {
            SearchFilterTypes.ready = false
            logger.error("فشل تهيئة فلاتر البحث", e)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        SearchFilterTypes.ready = false
        val stock = origFilterTypes
        if (stock != null) {
            valuesField.set(null, stock)
        }
        origFilterTypes = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extendFilterType() {
        val values = valuesField.get(null) as Array<FilterType>
        origFilterTypes = origFilterTypes ?: values

        val constructor = FilterType::class.java.declaredConstructors[0].apply { isAccessible = true }
        var next = values.size
        SearchFilterTypes.BEFORE = constructor.newInstance("BEFORE", next++) as FilterType
        SearchFilterTypes.AFTER = constructor.newInstance("AFTER", next++) as FilterType
        SearchFilterTypes.ON = constructor.newInstance("ON", next++) as FilterType
        SearchFilterTypes.AUTHOR_TYPE = constructor.newInstance("AUTHOR_TYPE", next++) as FilterType

        valuesField.set(null, values + arrayOf(SearchFilterTypes.BEFORE, SearchFilterTypes.AFTER, SearchFilterTypes.ON, SearchFilterTypes.AUTHOR_TYPE))
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchQueryParser() {
        patcher.after<QueryParser>(SearchStringProvider::class.java) {
            val rules = rulesField.get(this) as ArrayList<Rule<Context, QueryNode, Any>>
            rules.addAll(
                0,
                listOf(
                    filterMarkerRule("before", SearchFilterTypes.BEFORE),
                    filterMarkerRule("after", SearchFilterTypes.AFTER),
                    filterMarkerRule("on", SearchFilterTypes.ON),
                    filterMarkerRule("type", SearchFilterTypes.AUTHOR_TYPE),
                    dateAnswerRule(),
                    authorTypeAnswerRule(),
                ),
            )
        }
    }

    private fun filterMarkerRule(keyword: String, type: FilterType): ParserRule {
        val pattern = Pattern.compile("^\\s*?($keyword):", Pattern.UNICODE_CASE)
        return QueryRule(pattern) { _, state -> ParseSpec(FilterNode(type, keyword), state) }
    }

    private fun dateAnswerRule(): ParserRule {
        val pattern = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})\\b")
        return QueryRule(pattern) { matcher, state -> ParseSpec(DateAnswerNode(matcher.group(1)!!), state) }
    }

    private fun authorTypeAnswerRule(): ParserRule {
        val pattern = Pattern.compile("^\\s*(user|bot|webhook)\\b", Pattern.CASE_INSENSITIVE)
        return QueryRule(pattern) { matcher, state -> ParseSpec(AuthorTypeAnswerNode(matcher.group(1)!!), state) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchSuggestionUi() {
        patcher.after<SearchSuggestionEngine>(
            "getFilterSuggestions",
            CharSequence::class.java,
            SearchStringProvider::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) { (param, content: CharSequence) ->
            if (!SearchFilterTypes.ready) return@after

            val stock = origFilterTypes
            val final = (param.result as List<SearchSuggestion>).toMutableList()
            val insertAt = if (stock == null) {
                final.size
            } else {
                final.indexOfLast { it is FilterSuggestion && it.filterType in stock } + 1
            }

            val matches = SearchFilterTypes.all().filter { type ->
                val keyword = SearchFilterTypes.keywordFor(type) ?: return@filter false
                "$keyword:".contains(content, ignoreCase = true)
            }

            matches.forEachIndexed { i, type -> final.add(insertAt + i, FilterSuggestion(type)) }
            param.result = final
        }

        // NOTE: the real methods are "getFilterText" and "getAnswerText" — there is
        // no "getFilterTextId" on FilterViewHolder. Using a nonexistent method name
        // here previously threw NoSuchMethodException during start(), which made
        // SearchFilterTypes.ready stay false and silently disabled every filter.
        for (method in arrayOf("getFilterText", "getAnswerText")) {
            patcher.before<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
                method,
                FilterType::class.java,
            ) { (param, type: FilterType) ->
                val keyword = SearchFilterTypes.keywordFor(type) ?: return@before
                param.result = if (method == "getFilterText") "$keyword:" else placeholder
            }
        }

        patcher.before<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
            "getIconDrawable",
            Context::class.java,
            FilterType::class.java,
        ) { (param, context: Context, type: FilterType) ->
            if (!SearchFilterTypes.isOurs(type)) return@before
            param.result = androidx.core.content.ContextCompat.getDrawable(context, filterIcon)
        }

        patcher.after<WidgetSearchSuggestionsAdapter.FilterViewHolder>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            MGRecyclerDataPayload::class.java,
        ) { (_, _: Int, payload: SingleTypePayload<*>) ->
            val data = payload.data
            if (data !is FilterSuggestion) return@after
            val label = SearchFilterTypes.labelFor(data.filterType) ?: return@after
            val textViewId = Utils.getResId("suggestion_example_filter", "id")
            itemView.findViewById<TextView>(textViewId)?.text = label
        }
    }

    private fun patchFilterClicked() {
        patcher.before<StoreSearchInput>(
            "onFilterClicked",
            FilterType::class.java,
            SearchStringProvider::class.java,
            List::class.java,
        ) { (param, type: FilterType, _: SearchStringProvider, query: List<QueryNode>) ->
            val keyword = SearchFilterTypes.keywordFor(type) ?: return@before
            val index = when {
                query.isEmpty() -> 0
                query.last() is ContentNode -> query.lastIndex
                else -> query.size
            }
            replaceAndPublish.invoke(this, index, listOf(FilterNode(type, keyword)), query)
            param.result = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun patchAuthorTypeFilter() {
        patcher.after<SearchFetcher>(
            "makeQuery",
            StoreSearch.SearchTarget::class.java,
            Long::class.javaObjectType,
            SearchQuery::class.java,
        ) { (param, _: StoreSearch.SearchTarget, _: Long?, query: SearchQuery) ->
            val wantedType = query.params["author_type"]?.firstOrNull()?.lowercase() ?: return@after
            val original = param.result ?: return@after
            param.result = mapObservableAuthorType(original, wantedType)
        }
    }

    private fun mapObservableAuthorType(observable: Any, wantedType: String): Any {
        val func1Class = Class.forName("rx.functions.Func1")
        val proxy = Proxy.newProxyInstance(
            func1Class.classLoader,
            arrayOf(func1Class),
        ) { _, method, args ->
            if (method.name == "call" && args != null && args.isNotEmpty()) {
                filterByAuthorType(args[0], wantedType)
            } else null
        }
        val mapMethod = observable.javaClass.getMethod("map", func1Class)
        return mapMethod.invoke(observable, proxy)!!
    }

    private fun filterByAuthorType(response: Any?, wantedType: String): Any? {
        if (response == null) return response
        return try {
            val messagesField = response.javaClass.getDeclaredField("messages").apply { isAccessible = true }
            val originalMessages = messagesField.get(response) as? List<*> ?: return response

            val filtered = originalMessages.filter { hitList ->
                val messages = hitList as? List<*> ?: return@filter true
                val targetMsg = messages.firstOrNull { msg ->
                    if (msg == null) return@firstOrNull false
                    val hitField = msg.javaClass.getDeclaredField("hit").apply { isAccessible = true }
                    (hitField.get(msg) as? Boolean) == true
                } ?: messages.firstOrNull()

                matchesAuthorType(targetMsg, wantedType)
            }

            val modifiersField = Field::class.java.getDeclaredField("accessFlags").apply { isAccessible = true }
            modifiersField.setInt(messagesField, messagesField.modifiers and Modifier.FINAL.inv())
            messagesField.set(response, filtered)

            response
        } catch (e: Throwable) {
            logger.error("فشل فلترة type: - هترجع النتائج كاملة", e)
            response
        }
    }

    private fun matchesAuthorType(entry: Any?, wantedType: String): Boolean {
        if (entry == null) return true
        return try {
            val authorField = entry.javaClass.getDeclaredField("author").apply { isAccessible = true }
            val author = authorField.get(entry) ?: return true

            var isBot = false
            try {
                val botMethod = author.javaClass.methods.firstOrNull { it.name.contains("bot", ignoreCase = true) && (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType) }
                if (botMethod != null) {
                    isBot = botMethod.invoke(author) as? Boolean ?: false
                }
            } catch (e: Throwable) {}

            val webhookIdField = entry.javaClass.getDeclaredField("webhookId").apply { isAccessible = true }
            val webhookId = webhookIdField.get(entry)

            when (wantedType) {
                "bot" -> isBot && webhookId == null
                "webhook" -> webhookId != null
                "user" -> !isBot && webhookId == null
                else -> true
            }
        } catch (e: Throwable) {
            true
        }
    }
}
