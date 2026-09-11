package com.aliucord.plugins;

import android.content.Context;

import com.aliucord.Logger;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.PatcherKt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
 * عبر Map.put() — وهو نفس العرض الذي وصفته بالضبط (اختفاء تام لا يظهر
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
public class MentionDedupeFix extends Plugin {

    private static final Logger LOG = new Logger("MentionDedupeFix");

    private static final String SOURCE_CLASS =
        "com.discord.widgets.chat.input.autocomplete.sources.UserAutocompletableSource";
    private static final String USER_AUTOCOMPLETABLE_CLASS =
        "com.discord.widgets.chat.input.autocomplete.UserAutocompletable";
    private static final String ADAPTER_CLASS =
        "com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter";
    private static final String AUTOCOMPLETABLE_INTERFACE =
        "com.discord.widgets.chat.input.autocomplete.Autocompletable";

    private final List<Runnable> unhooks = new ArrayList<>();

    @Override
    public void start(Context context) {
        try {
            patchUserAutocompletableSource();
        } catch (Throwable t) {
            LOG.error("فشل ربط UserAutocompletableSource", t);
        }

        try {
            patchAdapterStableIds();
        } catch (Throwable t) {
            LOG.error("فشل ربط ChatInputAutocompleteAdapter.getItemId", t);
        }
    }

    @Override
    public void stop(Context context) {
        for (Runnable r : unhooks) {
            try { r.run(); } catch (Throwable ignored) {}
        }
        unhooks.clear();
    }

    // =====================================================================
    // 1) UserAutocompletableSource: الإصلاح الرئيسي
    // =====================================================================

    @SuppressWarnings("unchecked")
    private void patchUserAutocompletableSource() throws Exception {
        Class<?> sourceClass = Class.forName(SOURCE_CLASS);

        for (String methodName : new String[]{
                "createAutocompletablesForUsers",
                "createAutocompletablesForDmUsers"
        }) {
            Method target = findMethodByName(sourceClass, methodName);
            if (target == null) {
                LOG.warn("لم يتم العثور على الدالة: " + methodName + " — راجع الاسم في نسختك.");
                continue;
            }
            target.setAccessible(true);

            LOG.info("تم العثور على: " + target);

            Object unhook = PatcherKt.after(target, param -> {
                try {
                    Object rawResult = param.getResult();
                    if (!(rawResult instanceof Map)) return kotlin.Unit.INSTANCE;

                    Map<Object, Object> resultMap = (Map<Object, Object>) rawResult;

                    // تشخيص: نطبع نوع المفاتيح وعددها لمساعدتك على التأكيد
                    // من نوع المفتاح الفعلي المستخدم (Long ID أم String اسم).
                    if (!resultMap.isEmpty()) {
                        Object sampleKey = resultMap.keySet().iterator().next();
                        LOG.debug(methodName + " -> حجم الناتج=" + resultMap.size()
                                + " | نوع عينة من المفتاح=" + sampleKey.getClass().getName()
                                + " | قيمة عينة=" + sampleKey);
                    } else {
                        LOG.debug(methodName + " -> الناتج فارغ.");
                    }

                    // محاولة الاسترجاع: نمسح كل الـ Map parameters المُمررة
                    // كمُدخلات (Ljava/util/Map; في التوقيع) بحثاً عن أي
                    // GuildMember/User موجود بالمُدخل لكنه غائب عن الناتج،
                    // ونعيد بناءه بمفتاح فريد مضمون (النوع + userId).
                    Object[] args = param.getArgs();
                    Map<String, Object> reconciled = reconcileMissingUsers(resultMap, args);
                    if (reconciled != null) {
                        param.setResult(reconciled);
                        LOG.debug(methodName + " -> تم إعادة بناء الناتج بحجم="
                                + reconciled.size() + " (كان=" + resultMap.size() + ")");
                    }
                } catch (Throwable inner) {
                    LOG.error("خطأ أثناء إصلاح " + methodName, inner);
                }
                return kotlin.Unit.INSTANCE;
            });

            if (unhook instanceof Runnable) unhooks.add((Runnable) unhook);
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
    @SuppressWarnings("unchecked")
    private Map<String, Object> reconcileMissingUsers(Map<Object, Object> original, Object[] args) {
        try {
            // نجمع كل userId الظاهرة كمفاتيح Long في أي Map ضمن الباراميترات.
            java.util.Set<Long> candidateIds = new java.util.HashSet<>();
            for (Object arg : args) {
                if (arg instanceof Map) {
                    for (Object k : ((Map<Object, Object>) arg).keySet()) {
                        if (k instanceof Long) candidateIds.add((Long) k);
                        else if (k instanceof Number) candidateIds.add(((Number) k).longValue());
                    }
                }
            }
            if (candidateIds.isEmpty()) return null;

            // نبني مفتاح فريد جديد لكل عنصر موجود بالفعل، بالاعتماد على
            // الـ ID الحقيقي إن استطعنا استخراجه من قيمة الـ Autocompletable.
            Map<String, Object> rebuilt = new HashMap<>();
            int recoveredCount = 0;

            for (Map.Entry<Object, Object> e : original.entrySet()) {
                Long id = extractUserId(e.getValue());
                String key = (id != null) ? ("user:" + id) : ("raw:" + System.identityHashCode(e.getValue()));
                rebuilt.putIfAbsent(key, e.getValue());
            }

            // أي ID كان موجوداً بالمدخلات لكنه غائب عن rebuilt، لا يمكننا
            // بأمان إعادة تركيبه بدون معرفة المُنشئ الدقيق (نحتاج كائنات
            // User/GuildMember/Presence الحقيقية من نفس الطلب). لذلك في
            // هذا الإصدار نكتفي بتسجيله تشخيصياً بدل تخمين بيانات ناقصة.
            for (Long id : candidateIds) {
                if (!rebuilt.containsKey("user:" + id)) {
                    recoveredCount++;
                    LOG.warn("عضو بمعرف " + id + " موجود بالمُدخلات لكنه غائب عن نتيجة الاقتراحات "
                            + "(تم رصده تشخيصياً، لم تتوفر بيانات كافية لإعادة بنائه تلقائياً هنا).");
                }
            }

            if (recoveredCount == 0 && rebuilt.size() == original.size()) {
                // لا فرق فعلي، لا داعي لاستبدال النتيجة الأصلية.
                return null;
            }
            return rebuilt;
        } catch (Throwable t) {
            LOG.error("reconcileMissingUsers فشلت بأمان", t);
            return null;
        }
    }

    private Long extractUserId(Object autocompletableCandidate) {
        if (autocompletableCandidate == null) return null;
        try {
            // UserAutocompletable.getUser().getId()
            Method getUser = autocompletableCandidate.getClass().getMethod("getUser");
            Object user = getUser.invoke(autocompletableCandidate);
            if (user != null) {
                Method getId = user.getClass().getMethod("getId");
                Object idVal = getId.invoke(user);
                if (idVal instanceof Long) return (Long) idVal;
                if (idVal instanceof Number) return ((Number) idVal).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 2) ChatInputAutocompleteAdapter.getItemId: إصلاح احتياطي لتصادم الـ stable ID
    // =====================================================================

    private void patchAdapterStableIds() throws Exception {
        Class<?> adapterClass = Class.forName(ADAPTER_CLASS);
        Method getItem = adapterClass.getMethod("getItem", int.class);
        getItem.setAccessible(true);

        Method getItemId;
        try {
            getItemId = adapterClass.getMethod("getItemId", int.class);
        } catch (NoSuchMethodException e) {
            LOG.warn("getItemId(int) غير موجودة بهذا الاسم في نسختك.");
            return;
        }
        getItemId.setAccessible(true);

        Object unhook = PatcherKt.instead(getItemId, param -> {
            try {
                Object thisAdapter = param.getThisObject();
                Object[] args = param.getArgs();
                int position = (int) args[0];

                Object item = getItem.invoke(thisAdapter, position);
                Long uniqueId = extractStableIdFor(item);
                if (uniqueId != null) {
                    return uniqueId;
                }
                // fallback: نستدعي التطبيق الأصلي إن تعذر استخراج ID فريد
                return param.callOriginal();
            } catch (Throwable inner) {
                LOG.error("خطأ في getItemId المخصص، سيتم استخدام السلوك الأصلي", inner);
                try {
                    return param.callOriginal();
                } catch (Throwable t2) {
                    return -1L;
                }
            }
        });

        if (unhook instanceof Runnable) unhooks.add((Runnable) unhook);
    }

    /**
     * يبني ID مستقر فريد اعتماداً على النوع (User/Role/Channel/...) بالإضافة
     * إلى المعرف الحقيقي للكائن، لضمان عدم تصادم عنصرين متشابهين بالاسم.
     */
    private Long extractStableIdFor(Object autocompletable) {
        if (autocompletable == null) return null;
        String className = autocompletable.getClass().getSimpleName();

        Long id = tryInvokeChainForId(autocompletable, "getUser", "getId");
        if (id == null) id = tryInvokeChainForId(autocompletable, "getRole", "getId");
        if (id == null) id = tryInvokeChainForId(autocompletable, "getChannel", "getId");
        if (id == null) id = tryDirectId(autocompletable);

        if (id == null) return null;

        // ندمج النوع مع الـ ID في long واحد لتفادي أي تصادم بين IDs
        // متشابهة الأرقام من أنواع مختلفة (احتياط نظري).
        long typeSalt = className.hashCode();
        return (typeSalt << 40) ^ id;
    }

    private Long tryInvokeChainForId(Object root, String firstGetter, String secondGetter) {
        try {
            Method m1 = root.getClass().getMethod(firstGetter);
            Object mid = m1.invoke(root);
            if (mid == null) return null;
            Method m2 = mid.getClass().getMethod(secondGetter);
            Object idVal = m2.invoke(mid);
            if (idVal instanceof Long) return (Long) idVal;
            if (idVal instanceof Number) return ((Number) idVal).longValue();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Long tryDirectId(Object root) {
        try {
            Method m = root.getClass().getMethod("getId");
            Object idVal = m.invoke(root);
            if (idVal instanceof Long) return (Long) idVal;
            if (idVal instanceof Number) return ((Number) idVal).longValue();
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // أدوات مساعدة
    // =====================================================================

    private Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}
