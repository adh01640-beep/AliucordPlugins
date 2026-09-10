package com.github.canny1913

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.discord.api.commands.CommandChoice
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import java.lang.reflect.Field
import java.util.Comparator
import java.util.TreeMap
import java.util.TreeSet

@AliucordPlugin(requiresRestart = true)
class AutocompleteFix : Plugin() {

    override fun start(context: Context) {
        patchChatAutocomplete()
        patchSlashChoices()
    }

    private fun patchChatAutocomplete() {
        try {
            patcher.after<Any>(
                "com.discord.widgets.chat.input.autocomplete.ChatInputAutocompletables\$observeChannelAutocompletables\$1\$1",
                "call",
                Map::class.java,
                Map::class.java,
                Map::class.java,
                Map::class.java
            ) { param ->
                val resultMap = param.result as? Map<*, *> ?: return@after

                for (value in resultMap.values) {
                    if (value is TreeSet<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val treeSet = value as TreeSet<Autocompletable>
                        injectNonCollidingComparator(treeSet)
                    }
                }
            }
        } catch (e: Throwable) {
            logger.error("Error patching chat autocomplete", e)
        }
    }

    private fun patchSlashChoices() {
        try {
            // تم تصحيح الـ Generic syntax هنا ليتوافق تماماً مع Aliucord Patcher
            patcher.after<CommandChoice>("a") { param ->
                val originalName = param.result as? String ?: return@after
                val choice = param.thisObject as? CommandChoice ?: return@after

                val value = choice.b()?.toString()
                if (value != null && value.length >= 4 && value.all { it.isDigit() }) {
                    val suffix = " (#${value.takeLast(4)})"
                    if (!originalName.endsWith(")")) {
                        param.result = originalName + suffix
                    }
                }
            }
        } catch (e: Throwable) {
            logger.error("Error patching slash command choices", e)
        }
    }

    private fun injectNonCollidingComparator(set: TreeSet<Autocompletable>) {
        try {
            val mField: Field = TreeSet::class.java.getDeclaredField("m").apply { isAccessible = true }
            val treeMap = mField.get(set) as? TreeMap<*, *> ?: return

            val compField: Field = TreeMap::class.java.getDeclaredField("comparator").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val baseComparator = compField.get(treeMap) as? Comparator<Autocompletable>

            val safeComparator = Comparator<Autocompletable> { a, b ->
                if (a === b) return@Comparator 0
                val res = baseComparator?.compare(a, b) ?: 0
                if (res != 0) return@Comparator res

                if (a is UserAutocompletable && b is UserAutocompletable) {
                    val idCmp = a.user.id.compareTo(b.user.id)
                    if (idCmp != 0) return@Comparator idCmp
                }

                if (a is RoleAutocompletable && b is RoleAutocompletable) {
                    val roleIdCmp = a.role.id.compareTo(b.role.id)
                    if (roleIdCmp != 0) return@Comparator roleIdCmp
                }

                System.identityHashCode(a).compareTo(System.identityHashCode(b))
            }

            compField.set(treeMap, safeComparator)
        } catch (ignored: Throwable) {}
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
