package com.aliucord.plugins

import android.content.Context
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PinePatchFn
import top.canyie.pine.Pine
import java.lang.reflect.Method

@AliucordPlugin
class MentionDedupeFix : Plugin() {

    companion object {
        private val LOG = Logger("MentionDedupeFix")

        private const val SOURCE_CLASS =
            "com.discord.widgets.chat.input.autocomplete.sources.UserAutocompletableSource"
        private const val ADAPTER_CLASS =
            "com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter"
    }

    override fun start(context: Context) {
        try {
            patchUserAutocompletableSource()
        } catch (t: Throwable) {
            LOG.error("فشل ربط UserAutocompletableSource", t)
        }

        try {
            patchAdapterStableIds()
        } catch (t: Throwable) {
            LOG.error("فشل ربط ChatInputAutocompleteAdapter.getItemId", t)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    // =====================================================================
    // 1) UserAutocompletableSource: الإصلاح الرئيسي
    // =====================================================================

    private fun patchUserAutocompletableSource() {
        val sourceClass = Class.forName(SOURCE_CLASS)

        for (methodName in arrayOf(
            "createAutocompletablesForUsers",
            "createAutocompletablesForDmUsers"
        )) {
            val target = findMethodByName(sourceClass, methodName)
            if (target == null) {
                LOG.warn("لم يتم العثور على الدالة: $methodName — راجع الاسم في نسختك.")
                continue
            }
            target.isAccessible = true
            LOG.info("تم العثور على: $target")

            patcher.patch(target, PinePatchFn { callFrame ->
                try {
                    val rawResult = callFrame.result
                    if (rawResult !is MutableMap<*, *>) return@PinePatchFn

                    @Suppress("UNCHECKED_CAST")
                    val resultMap = rawResult as MutableMap<Any, Any>

                    if (resultMap.isNotEmpty()) {
                        val sampleKey = resultMap.keys.first()
                        LOG.debug(
                            "$methodName -> حجم الناتج=${resultMap.size}" +
                                " | نوع عينة من المفتاح=${sampleKey::class.java.name}" +
                                " | قيمة عينة=$sampleKey"
                        )
                    } else {
                        LOG.debug("$methodName -> الناتج فارغ.")
                    }

                    val args = callFrame.args
                    val reconciled = reconcileMissingUsers(resultMap, args)
                    if (reconciled != null) {
                        callFrame.result = reconciled
                        LOG.debug(
                            "$methodName -> تم إعادة بناء الناتج بحجم=" +
                                "${reconciled.size} (كان=${resultMap.size})"
                        )
                    }
                } catch (inner: Throwable) {
                    LOG.error("خطأ أثناء إصلاح $methodName", inner)
                }
            })
        }
    }

    private fun reconcileMissingUsers(
        original: Map<Any, Any>,
        args: Array<Any?>
    ): Map<String, Any>? {
        return try {
            val candidateIds = HashSet<Long>()
            for (arg in args) {
                if (arg is Map<*, *>) {
                    for (k in arg.keys) {
                        when (k) {
                            is Long -> candidateIds.add(k)
                            is Number -> candidateIds.add(k.toLong())
                        }
                    }
                }
            }
            if (candidateIds.isEmpty()) return null

            val rebuilt = HashMap<String, Any>()
            for ((_, value) in original) {
                val id = extractUserId(value)
                val key = if (id != null) "user:$id" else "raw:${System.identityHashCode(value)}"
                rebuilt.putIfAbsent(key, value)
            }

            var recoveredCount = 0
            for (id in candidateIds) {
                if (!rebuilt.containsKey("user:$id")) {
                    recoveredCount++
                    LOG.warn(
                        "عضو بمعرف $id موجود بالمُدخلات لكنه غائب عن نتيجة الاقتراحات."
                    )
                }
            }

            if (recoveredCount == 0 && rebuilt.size == original.size) {
                null
            } else {
                rebuilt
            }
        } catch (t: Throwable) {
            LOG.error("reconcileMissingUsers فشلت بأمان", t)
            null
        }
    }

    private fun extractUserId(autocompletableCandidate: Any?): Long? {
        if (autocompletableCandidate == null) return null
        return try {
            val getUser = autocompletableCandidate.javaClass.getMethod("getUser")
            val user = getUser.invoke(autocompletableCandidate) ?: return null
            val getId = user.javaClass.getMethod("getId")
            when (val idVal = getId.invoke(user)) {
                is Long -> idVal
                is Number -> idVal.toLong()
                else -> null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    // =====================================================================
    // 2) ChatInputAutocompleteAdapter.getItemId: تصادم الـ stable ID
    // =====================================================================

    private fun patchAdapterStableIds() {
        val adapterClass = Class.forName(ADAPTER_CLASS)
        val getItem = adapterClass.getMethod("getItem", Int::class.javaPrimitiveType)
        getItem.isAccessible = true

        val getItemId = try {
            adapterClass.getMethod("getItemId", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            LOG.warn("getItemId(int) غير موجودة بهذا الاسم في نسختك.")
            return
        }
        getItemId.isAccessible = true

        patcher.patch(getItemId, PinePatchFn { callFrame ->
            try {
                val thisAdapter = callFrame.thisObject
                val position = callFrame.args[0] as Int

                val item = getItem.invoke(thisAdapter, position)
                val uniqueId = extractStableIdFor(item)
                if (uniqueId != null) {
                    callFrame.result = uniqueId
                }
            } catch (inner: Throwable) {
                LOG.error("خطأ في getItemId المخصص", inner)
            }
        })
    }

    private fun extractStableIdFor(autocompletable: Any?): Long? {
        if (autocompletable == null) return null
        val className = autocompletable.javaClass.simpleName

        val id = tryInvokeChainForId(autocompletable, "getUser", "getId")
            ?: tryInvokeChainForId(autocompletable, "getRole", "getId")
            ?: tryInvokeChainForId(autocompletable, "getChannel", "getId")
            ?: tryDirectId(autocompletable)
            ?: return null

        val typeSalt = className.hashCode().toLong()
        return (typeSalt shl 40) xor id
    }

    private fun tryInvokeChainForId(root: Any, firstGetter: String, secondGetter: String): Long? {
        return try {
            val m1 = root.javaClass.getMethod(firstGetter)
            val mid = m1.invoke(root) ?: return null
            val m2 = mid.javaClass.getMethod(secondGetter)
            when (val idVal = m2.invoke(mid)) {
                is Long -> idVal
                is Number -> idVal.toLong()
                else -> null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun tryDirectId(root: Any): Long? {
        return try {
            val m = root.javaClass.getMethod("getId")
            when (val idVal = m.invoke(root)) {
                is Long -> idVal
                is Number -> idVal.toLong()
                else -> null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun findMethodByName(clazz: Class<*>, name: String): Method? =
        clazz.declaredMethods.firstOrNull { it.name == name }
}
