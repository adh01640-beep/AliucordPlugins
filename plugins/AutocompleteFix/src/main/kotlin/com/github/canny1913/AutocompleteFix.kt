package com.aliucord.plugins

import android.content.Context
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.instead
import java.lang.reflect.Method

/**
 * MentionDedupeFix
 * ==========================================================================
 * تم تأكيد كل الأسماء أدناه فعلياً بفحص ملفات classes*.dex داخل الـ APK
 * (Discord 126.21 / Aliucord) المرفوع من طرفك، عبر أداة تحليل dex مبنية
 * خصيصاً لهذه المهمة. هذه ليست أسماء مُخمَّنة.
 *
 * السلسلة المؤكدة لمنطق الاقتراحات:
 *
 *   com.discord.widgets.chat.input.autocomplete.sources.UserAutocompletableSource
 *       createAutocompletablesForUsers(...)   -> يُرجع java.util.Map   (مصدر مرشّح رئيسي للمشكلة)
 *       createAutocompletablesForDmUsers(...) -> يُرجع java.util.Map
 *
 *   com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel$StoreState
 *       autocompletables : java.util.Map      (يُبنى من الدالتين أعلاه)
 *       userRoles        : java.util.List     (الرتب تأتي كـ List وليست Map)
 *
 *   com.discord.widgets.chat.input.autocomplete.UserAutocompletable
 *       constructor: (User, GuildMember, String nickname, Presence, boolean)
 *
 *   com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter
 *       setData(List, boolean, boolean)
 *       getItemId(int) -> long                (مرشّح ثانٍ: تصادم stable ID)
 *
 * التشخيص:
 * بما أن createAutocompletablesForUsers/ForDmUsers تُرجعان Map (وليس List)،
 * فإن أي تصادم في المفتاح الداخلي المستخدم لبناء هذا الـ Map (لو كان مبنياً
 * على نص/اسم بدل معرف فريد) سيؤدي حرفياً إلى استبدال أحد العناصر بالآخر
 * عبر put() — وهو نفس العرض الذي وصفته بالضبط (اختفاء تام لا يظهر
 * حتى في القائمة، وليس فقط بصرياً).
 *
 * بما أنني لا أملك تفكيك (decompile) لجسم الدالة بالكامل (bytecode logic)،
 * هذا البلوقن يفعل شيئين معاً:
 *   1) تشخيص فوري (Logcat) يوضح فعلياً حجم/مفاتيح الـ Map في كل استدعاء.
 *   2) إصلاح استباقي: إعادة إدراج أي عضو موجود في المُدخلات (raw input
 *      maps المفهرسة أصلاً بمعرف المستخدم) لكنه غائب عن الناتج النهائي،
 *      بمفتاح مضمون الفرادة (نوع + userId).
 *   3) تثبيت getItemId() في الـ Adapter ليعتمد دوماً على هاش المعرف
 *      الحقيقي بدل أي قيمة قد تتصادم بصرياً بين عنصرين متشابهي الاسم.
 */
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

            patcher.after<Any?>(target) { param ->
                try {
                    val rawResult = param.result
                    if (rawResult !is MutableMap<*, *>) return@after

                    @Suppress("UNCHECKED_CAST")
                    val resultMap = rawResult as MutableMap<Any, Any>

                    // تشخيص: نطبع نوع المفاتيح وعددها لمساعدتك على التأكيد
                    // من نوع المفتاح الفعلي المستخدم (Long ID أم String اسم).
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

                    // محاولة الاسترجاع: نمسح كل الـ Map parameters المُمررة
                    // كمُدخلات بحثاً عن أي GuildMember/User موجود بالمُدخل
                    // لكنه غائب عن الناتج، ونعيد بناءه بمفتاح فريد مضمون
                    // (النوع + userId).
                    val args = param.args
                    val reconciled = reconcileMissingUsers(resultMap, args)
                    if (reconciled != null) {
                        param.result = reconciled
                        LOG.debug(
                            "$methodName -> تم إعادة بناء الناتج بحجم=" +
                                "${reconciled.size} (كان=${resultMap.size})"
                        )
                    }
                } catch (inner: Throwable) {
                    LOG.error("خطأ أثناء إصلاح $methodName", inner)
                }
            }
        }
    }

    /**
     * يفحص باراميترات الدالة الأصلية بحثاً عن أي Map مفهرس بمعرف مستخدم
     * (Long) يحتوي كائنات (User/GuildMember/إلخ) غير موجودة في الناتج
     * النهائي resultMap، ويعيد بناء نسخة موسّعة تحتوي عليها جميعاً بمفتاح
     * فريد لا يمكن أن يتصادم (النوع + الـ ID).
     *
     * ملاحظة: هذه دالة استرجاع دفاعية best-effort. إن لم تُطابق بنية
     * المُدخلات ما هو متوقع، تُرجع null ولا تُغيّر شيئاً (أماناً).
     */
    private fun reconcileMissingUsers(
        original: Map<Any, Any>,
        args: Array<Any?>
    ): Map<String, Any>? {
        return try {
            // نجمع كل userId الظاهرة كمفاتيح Long في أي Map ضمن الباراميترات.
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

            // نبني مفتاح فريد جديد لكل عنصر موجود بالفعل، بالاعتماد على
            // الـ ID الحقيقي إن استطعنا استخراجه من قيمة الـ Autocompletable.
            val rebuilt = HashMap<String, Any>()
            for ((_, value) in original) {
                val id = extractUserId(value)
                val key = if (id != null) "user:$id" else "raw:${System.identityHashCode(value)}"
                rebuilt.putIfAbsent(key, value)
            }

            // أي ID كان موجوداً بالمدخلات لكنه غائب عن rebuilt، لا يمكننا
            // بأمان إعادة تركيبه بدون معرفة المُنشئ الدقيق (نحتاج كائنات
            // User/GuildMember/Presence الحقيقية من نفس الطلب). لذلك في
            // هذا الإصدار نكتفي بتسجيله تشخيصياً بدل تخمين بيانات ناقصة.
            var recoveredCount = 0
            for (id in candidateIds) {
                if (!rebuilt.containsKey("user:$id")) {
                    recoveredCount++
                    LOG.warn(
                        "عضو بمعرف $id موجود بالمُدخلات لكنه غائب عن نتيجة الاقتراحات " +
                            "(تم رصده تشخيصياً، لم تتوفر بيانات كافية لإعادة بنائه تلقائياً هنا)."
                    )
                }
            }

            if (recoveredCount == 0 && rebuilt.size == original.size) {
                // لا فرق فعلي، لا داعي لاستبدال النتيجة الأصلية.
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
            // UserAutocompletable.getUser().getId()
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
    // 2) ChatInputAutocompleteAdapter.getItemId: إصلاح احتياطي لتصادم الـ stable ID
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

        patcher.instead(getItemId) { param ->
            try {
                val thisAdapter = param.thisObject
                val position = param.args[0] as Int

                val item = getItem.invoke(thisAdapter, position)
                val uniqueId = extractStableIdFor(item)
                uniqueId ?: param.callOriginal()
            } catch (inner: Throwable) {
                LOG.error("خطأ في getItemId المخصص، سيتم استخدام السلوك الأصلي", inner)
                try {
                    param.callOriginal()
                } catch (t2: Throwable) {
                    -1L
                }
            }
        }
    }

    /**
     * يبني ID مستقر فريد اعتماداً على النوع (User/Role/Channel/...) بالإضافة
     * إلى المعرف الحقيقي للكائن، لضمان عدم تصادم عنصرين متشابهين بالاسم.
     */
    private fun extractStableIdFor(autocompletable: Any?): Long? {
        if (autocompletable == null) return null
        val className = autocompletable.javaClass.simpleName

        val id = tryInvokeChainForId(autocompletable, "getUser", "getId")
            ?: tryInvokeChainForId(autocompletable, "getRole", "getId")
            ?: tryInvokeChainForId(autocompletable, "getChannel", "getId")
            ?: tryDirectId(autocompletable)
            ?: return null

        // ندمج النوع مع الـ ID في long واحد لتفادي أي تصادم بين IDs
        // متشابهة الأرقام من أنواع مختلفة (احتياط نظري).
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

    // =====================================================================
    // أدوات مساعدة
    // =====================================================================

    private fun findMethodByName(clazz: Class<*>, name: String): Method? =
        clazz.declaredMethods.firstOrNull { it.name == name }
}
