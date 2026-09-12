package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.utilities.search.query.FilterType
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

@AliucordPlugin
class AdvancedSearchFilters : Plugin() {

    companion object {
        val logger = Logger("AdvancedSearchFilters")

        var FILTER_BEFORE: FilterType? = null
        var FILTER_AFTER: FilterType? = null
        var FILTER_ON: FilterType? = null
        var FILTER_AUTHOR_TYPE: FilterType? = null
    }

    override fun start(context: Context) {
        try {
            injectCustomFilters()
            patchSearchSuggestionEngine()
            patchSearchStringProvider()
            patchFilterViewHolder()
        } catch (e: Throwable) {
            logger.error("Failed to initialize AdvancedSearchFilters", e)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    // =========================================================================================
    // 1) Extend the FilterType enum at runtime
    // =========================================================================================

    private fun injectCustomFilters() {
        val filterClass = FilterType::class.java
        val base = filterClass.enumConstants!!.size // 4 -> FROM, MENTIONS, HAS, IN

        FILTER_ON = addEnum(filterClass, "ON", base)
        FILTER_BEFORE = addEnum(filterClass, "BEFORE", base + 1)
        FILTER_AFTER = addEnum(filterClass, "AFTER", base + 2)
        FILTER_AUTHOR_TYPE = addEnum(filterClass, "AUTHOR_TYPE", base + 3)

        if (FILTER_ON == null || FILTER_BEFORE == null || FILTER_AFTER == null || FILTER_AUTHOR_TYPE == null) {
            throw IllegalStateException("Could not inject one or more custom FilterType constants")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Enum<T>> addEnum(enumType: Class<T>, name: String, ordinal: Int): T? {
        return try {
            val constructor: Constructor<*> = enumType.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Int::class.javaPrimitiveType)
            } ?: throw NoSuchMethodException("No (String, int) enum constructor found on ${enumType.name}")

            constructor.isAccessible = true
            val newEnum = constructor.newInstance(name, ordinal) as T

            val valuesField: Field = enumType.getDeclaredField("\$VALUES")
            valuesField.isAccessible = true
            unlockField(valuesField)

            val oldValues = valuesField.get(null) as Array<T>
            if (oldValues.any { it.name == name }) {
                return oldValues.first { it.name == name }
            }

            val newValues = java.lang.reflect.Array.newInstance(enumType, oldValues.size + 1) as Array<T>
            System.arraycopy(oldValues, 0, newValues, 0, oldValues.size)
            newValues[oldValues.size] = newEnum
            valuesField.set(null, newValues)

            for (f in enumType.declaredFields) {
                if (f != valuesField && f.type.isArray && f.type.componentType == enumType) {
                    try {
                        f.isAccessible = true
                        unlockField(f)
                        f.set(null, newValues.copyOf())
                    } catch (_: Throwable) { }
                }
            }

            newEnum
        } catch (e: Throwable) {
            logger.error("Failed to inject enum constant $name", e)
            null
        }
    }

    private fun unlockField(field: Field) {
        for (fieldName in arrayOf("accessFlags", "modifiers")) {
            try {
                val modifiersField = Field::class.java.getDeclaredField(fieldName)
                modifiersField.isAccessible = true
                modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
                return
            } catch (_: Throwable) { }
        }
    }

    // =========================================================================================
    // 2) Inject suggestions into the "Search options" list
    // =========================================================================================

    private fun patchSearchSuggestionEngine() {
        val engineClass = Class.forName("com.discord.utilities.search.suggestion.SearchSuggestionEngine")
        val getFilterMethod = engineClass.declaredMethods.firstOrNull { it.name == "getFilterSuggestions" }
            ?: run { logger.error("getFilterSuggestions not found"); return }

        val suggestionClass = Class.forName("com.discord.utilities.search.suggestion.entries.FilterSuggestion")
        val ctor = suggestionClass.getDeclaredConstructor(FilterType::class.java).apply { isAccessible = true }

        patcher.patch(getFilterMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rawContent = (param.args.getOrNull(0) as? CharSequence)?.toString()?.trim()?.lowercase() ?: ""

                fun matches(token: String) = rawContent.isEmpty() || token.startsWith(rawContent)

                val currentSuggestions = ArrayList<Any>()
                (param.result as? Collection<*>)?.let { currentSuggestions.addAll(it.filterNotNull()) }

                val customFilters = listOf(
                    "on" to FILTER_ON,
                    "before" to FILTER_BEFORE,
                    "after" to FILTER_AFTER,
                    "type" to FILTER_AUTHOR_TYPE
                )

                for ((token, filter) in customFilters) {
                    if (filter != null && matches(token)) {
                        currentSuggestions.add(ctor.newInstance(filter))
                    }
                }

                param.result = currentSuggestions
            }
        })
    }

    // =========================================================================================
    // 3) Teach SearchStringProvider about the new tokens
    // =========================================================================================

    private fun tokenFor(type: FilterType?): String? = when (type) {
        FILTER_BEFORE -> "before:"
        FILTER_AFTER -> "after:"
        FILTER_ON -> "on:"
        FILTER_AUTHOR_TYPE -> "type:"
        else -> null
    }

    private fun labelFor(type: FilterType?): String? = when (type) {
        FILTER_BEFORE -> "Before a date"
        FILTER_AFTER -> "After a date"
        FILTER_ON -> "Sent on a date"
        FILTER_AUTHOR_TYPE -> "By author type"
        else -> null
    }

    private fun patchSearchStringProvider() {
        val providerClass = Class.forName("com.discord.utilities.search.strings.SearchStringProvider")

        patcher.patch(providerClass.getDeclaredMethod("getFilterText", FilterType::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = param.args[0] as? FilterType
                tokenFor(type)?.let { param.result = it }
            }
        })

        patcher.patch(providerClass.getDeclaredMethod("getFilterTextId", FilterType::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val type = param.args[0] as? FilterType
                if (labelFor(type) != null) {
                    param.result = Utils.getResId("search_filter_from", "string")
                }
            }
        })
    }

    // =========================================================================================
    // 4) Intercept FilterViewHolder.onConfigure BEFORE the native (crashing) binding logic runs
    // =========================================================================================

    private fun patchFilterViewHolder() {
        val vhClass = Class.forName("com.discord.widgets.search.suggestions.WidgetSearchSuggestionsAdapter\$FilterViewHolder")
        val suggestionClass = Class.forName("com.discord.utilities.search.suggestion.entries.FilterSuggestion")
        val getFilterTypeMethod = suggestionClass.getMethod("getFilterType")
        val onConfigureMethod = vhClass.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, suggestionClass)

        patcher.patch(onConfigureMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val suggestion = param.args[1] ?: return
                val filterType = getFilterTypeMethod.invoke(suggestion) as? FilterType ?: return
                val label = labelFor(filterType) ?: return
                val token = tokenFor(filterType) ?: return

                val holder = param.thisObject as RecyclerView.ViewHolder
                val itemView = holder.itemView

                copyMatchingFields(holder, suggestion, suggestionClass)

                setLabel(itemView, label)
                setIcon(itemView, filterType)

                itemView.setOnClickListener { insertToken(itemView, token) }

                param.result = null
            }
        })
    }

    private fun copyMatchingFields(holder: Any, suggestion: Any, suggestionClass: Class<*>) {
        var cls: Class<*>? = holder.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (f.type.isAssignableFrom(suggestionClass)) {
                    try {
                        f.isAccessible = true
                        f.set(holder, suggestion)
                    } catch (_: Throwable) { }
                }
            }
            cls = cls.superclass
        }
    }

    private fun setLabel(itemView: View, label: String) {
        findFirst<TextView>(itemView)?.text = label
    }

    // الأيقونات المتوافقة مع ملفات ديسكورد الأصلية
    private fun setIcon(itemView: View, type: FilterType) {
        val iv = findFirst<ImageView>(itemView) ?: return

        val resName = when (type) {
            FILTER_BEFORE -> "ic_history_24dp"
            FILTER_AFTER -> "ic_history_24dp"
            FILTER_ON -> "ic_calendar_24dp"
            FILTER_AUTHOR_TYPE -> "ic_person_24dp"
            else -> null
        }

        val resId = resName?.let { Utils.getResId(it, "drawable").takeIf { id -> id != 0 } }
            ?: Utils.getResId("ic_search_filter_in", "drawable")

        if (resId != 0) {
            iv.setImageResource(resId)
        }
    }

    private inline fun <reified T : View> findFirst(view: View): T? {
        if (view is T) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirst<T>(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun insertToken(anchor: View, token: String) {
        val root = anchor.rootView
        val input = findFirst<EditText>(root)
        if (input == null) {
            logger.error("Could not locate the search EditText to insert '$token'")
            return
        }

        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)

        input.text.replace(lo, hi, token)
        input.setSelection(lo + token.length)
        input.requestFocus()
    }
}
