package com.github.canny1913

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.instead
import com.discord.api.commands.CommandChoice
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import java.lang.reflect.Field
import java.util.*

@AliucordPlugin(requiresRestart = true)
class AutocompleteFix : Plugin() {

    override fun start(context: Context) {
        patchChatAutocomplete()
        patchSlashChoices()
    }

    /**
     * معالجة منشن الشات العادي (المستخدمين والرتب)
     */
    private fun patchChatAutocomplete() {
        try {
            // هوك على دالة معالجة الـ Autocomplete للشات
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

    /**
     * معالجة خيارات السلاش (Slash Command Choices) المتطابقة في الاسم
     */
    private fun patchSlashChoices() {
        try {
            // هوك على دالة استخراج اسم الخيار لمنع دمج الرتب/المستخدمين في خيارات السلاش
            patcher.after<CommandChoice>(
                CommandChoice::class.java,
                "a" // دالة getName() داخل موديل CommandChoice
            ) { param ->
                val originalName = param.result as? String ?: return@after
                val choice = param.thisObject as? CommandChoice ?: return@after
                
                // استخراج القيمة (التي تحمل الـ ID في أوامر الرتب والمستخدمين)
                val value = choice.b()?.toString() // دالة getValue()
                if (value != null && value.length >= 4 && value.all { it.isDigit() }) {
                    // إضافة تمييز فريد غير مرئي أو واضح عند تطابق الأسماء
                    // نضع آخر 4 أرقام من الـ ID بين قوسين للتفريق بين الخيارات المتشابهة
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

    /**
     * استبدال المقارن بمقارن يمنع تماماً حذف أي عنصر مهما تشابه
     */
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

                // كسر التعادل للمستخدمين
                if (a is UserAutocompletable && b is UserAutocompletable) {
                    val idCmp = a.user.id.compareTo(b.user.id)
                    if (idCmp != 0) return@Comparator idCmp
                }

                // كسر التعادل للرتب
                if (a is RoleAutocompletable && b is RoleAutocompletable) {
                    val roleIdCmp = a.role.id.compareTo(b.role.id)
                    if (roleIdCmp != 0) return@Comparator roleIdCmp
                }

                // كسر التعادل لأي كائنين آخرين
                System.identityHashCode(a).compareTo(System.identityHashCode(b))
            }

            compField.set(treeMap, safeComparator)
        } catch (ignored: Throwable) {}
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}

