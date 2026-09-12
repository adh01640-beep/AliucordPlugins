package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.utilities.search.query.FilterType
import de.robv.android.xposed.XC_MethodHook
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
            logger.error("فشل تهيئة فلاتر البحث", e)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    /**
     * 1. حقن الفلاتر الجديدة داخل FilterType Enum أثناء التشغيل
     */
    private fun injectCustomFilters() {
        val filterClass = FilterType::class.java
        FILTER_BEFORE = addEnum(filterClass, "BEFORE", 4)
        FILTER_AFTER = addEnum(filterClass, "AFTER", 5)
        FILTER_ON = addEnum(filterClass, "ON", 6)
        FILTER_AUTHOR_TYPE = addEnum(filterClass, "AUTHOR_TYPE", 7)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Enum<T>> addEnum(enumType: Class<T>, name: String, ordinal: Int): T? {
        return try {
            val constructor = enumType.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            val newEnum = constructor.newInstance(name, ordinal) as T

            val valuesField: Field = enumType.getDeclaredField("\$VALUES")
            valuesField.isAccessible = true
            
            val modifiersField = Field::class.java.getDeclaredField("accessFlags")
            modifiersField.isAccessible = true
            modifiersField.setInt(valuesField, valuesField.modifiers and Modifier.FINAL.inv())

            val oldValues = valuesField.get(null) as Array<T>
            val newValues = java.lang.reflect.Array.newInstance(enumType, oldValues.size + 1) as Array<T>
            System.arraycopy(oldValues, 0, newValues, 0, oldValues.size)
            newValues[oldValues.size] = newEnum
            
            valuesField.set(null, newValues)
            newEnum
        } catch (e: Exception) {
            logger.error("فشل حقن $name", e)
            null
        }
    }

    /**
     * 2. إضافة الفلاتر لقائمة الاقتراحات Search Options
     */
    private fun patchSearchSuggestionEngine() {
        val engineClass = Class.forName("com.discord.utilities.search.suggestion.SearchSuggestionEngine")
        val getFilterMethod = engineClass.declaredMethods.firstOrNull { it.name == "getFilterSuggestions" } ?: return
        
        patcher.patch(getFilterMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val currentSuggestions = (param.result as? List<*>)?.toMutableList() ?: mutableListOf<Any>()
                
                val filterClass = Class.forName("com.discord.utilities.search.suggestion.entries.FilterSuggestion")
                val constructor = filterClass.getDeclaredConstructor(FilterType::class.java)
                constructor.isAccessible = true
                
                if (FILTER_ON != null) currentSuggestions.add(constructor.newInstance(FILTER_ON))
                if (FILTER_BEFORE != null) currentSuggestions.add(constructor.newInstance(FILTER_BEFORE))
                if (FILTER_AFTER != null) currentSuggestions.add(constructor.newInstance(FILTER_AFTER))
                if (FILTER_AUTHOR_TYPE != null) currentSuggestions.add(constructor.newInstance(FILTER_AUTHOR_TYPE))
                
                param.result = currentSuggestions
            }
        })
    }

    /**
     * 3. تزويد ديسكورد بالنصوص الرسمية ليتعرف عليها المحرك
     */
    private fun patchSearchStringProvider() {
        val providerClass = Class.forName("com.discord.utilities.search.strings.SearchStringProvider")
        
        // ربط الفلتر بكلمة البحث (مثال: before:)
        patcher.patch(providerClass.getDeclaredMethod("getFilterText", FilterType::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                when (param.args[0]) {
                    FILTER_BEFORE -> param.result = "before:"
                    FILTER_AFTER -> param.result = "after:"
                    FILTER_ON -> param.result = "on:"
                    FILTER_AUTHOR_TYPE -> param.result = "type:"
                }
            }
        })
        
        // إرجاع ID وهمي لتجنب كراش الموارد (Resources NotFound)
        patcher.patch(providerClass.getDeclaredMethod("getFilterTextId", FilterType::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                when (param.args[0]) {
                    FILTER_BEFORE, FILTER_AFTER, FILTER_ON, FILTER_AUTHOR_TYPE -> {
                        param.result = Utils.getResId("search_filter_from", "string")
                    }
                }
            }
        })
    }

    /**
     * 4. تغيير نصوص الواجهة المرئية لأسماء الفلاتر الجديدة
     */
    private fun patchFilterViewHolder() {
        val vhClass = Class.forName("com.discord.widgets.search.suggestions.WidgetSearchSuggestionsAdapter\$FilterViewHolder")
        val suggestionClass = Class.forName("com.discord.utilities.search.suggestion.entries.FilterSuggestion")
        
        patcher.patch(vhClass.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, suggestionClass), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val suggestion = param.args[1] ?: return
                val filterType = suggestion.javaClass.getMethod("getFilterType").invoke(suggestion) as? FilterType ?: return
                
                val customText = when (filterType) {
                    FILTER_BEFORE -> "Before a date"
                    FILTER_AFTER -> "After a date"
                    FILTER_ON -> "Sent on a date"
                    FILTER_AUTHOR_TYPE -> "By author type"
                    else -> null
                }
                
                if (customText != null) {
                    val holder = param.thisObject as RecyclerView.ViewHolder
                    val fallbackString = holder.itemView.context.getString(Utils.getResId("search_filter_from", "string"))
                    updateCustomTextView(holder.itemView, fallbackString, customText)
                }
            }
        })
    }

    // استبدال النص الوهمي بالنص الفعلي داخل العنصر
    private fun updateCustomTextView(view: View, oldText: String, newText: String) {
        if (view is TextView) {
            if (view.text.toString() == oldText) {
                view.text = newText
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateCustomTextView(view.getChildAt(i), oldText, newText)
            }
        }
    }
}
