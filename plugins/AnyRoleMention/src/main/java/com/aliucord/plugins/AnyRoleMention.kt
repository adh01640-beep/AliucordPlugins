package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel
import com.discord.widgets.chat.input.autocomplete.AutocompleteViewState
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.adapter.AutocompleteItemViewHolder
import de.robv.android.xposed.XC_MethodHook

/**
 * AnyRoleMention
 * ==========================================================================
 * الفكرة: إظهار كل الرتب في قائمة اقتراحات المنشن (@) حتى لو المستخدم
 * ملوش صلاحية يعمل بينج فعلي بيها، مع توضيح جنب اسم كل رتبة هل اختيارها
 * هيبعت إشعار فعلي (mention) ولا هيتكتب بس من غير تأثير (silent).
 *
 * الأساس التقني (مؤكد من فحص bytecode الفعلي لإصدار Discord 126.21):
 *   RoleAutocompletable.canMention : Boolean   <- ديسكورد نفسه بيحسبها
 *   جاهزة لكل رتبة، إحنا بس بنستخدمها، مش بنتلاعب بأي صلاحية حقيقية.
 *
 *   AutocompleteViewModel.getAutocompleteViewState(query, list1, list2, bool)
 *     -> هي الدالة اللي بتجهّز اللستة النهائية المعروضة، وفيها (أو قبلها)
 *        بيتم استبعاد الرتب اللي canMention=false. إحنا بنقارن اللستة
 *        النهائية باللستة الكاملة اللي دخلت كـ parameters، ونرجّع أي رتبة
 *        اتشالت لكنها لسه مطابقة للنص اللي المستخدم بيكتبه.
 *
 *   AutocompleteItemViewHolder.bindRole(RoleAutocompletable)
 *     -> الدالة اللي بترسم اسم الرتبة فعلياً. بنعمل Hook بعدها (after)
 *        ونضيف نص " (mention)"/" (silent)" على نفس الـ TextView من غير
 *        ما نغيّر أي منطق داخلي أو نص الإدخال الحقيقي.
 */
@AliucordPlugin
class AnyRoleMention : Plugin() {

    companion object {
        private val LOG = Logger("AnyRoleMention")
        const val SETTING_SHOW_LABEL = "showMentionLabel"
    }

    init {
        settingsTab = SettingsTab(AnyRoleMentionSettings::class.java)
    }

    override fun start(context: Context) {
        AnyRoleMentionPluginRef.plugin = this

        try {
            patchAutocompleteViewState()
        } catch (t: Throwable) {
            LOG.error("فشل ربط getAutocompleteViewState", t)
        }

        try {
            patchBindRoleLabel()
        } catch (t: Throwable) {
            LOG.error("فشل ربط bindRole", t)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        AnyRoleMentionPluginRef.plugin = null
    }

    // =====================================================================
    // 1) إرجاع كل الرتب المطابقة للنص، حتى الغير قابلة للمنشن
    // =====================================================================

    private fun patchAutocompleteViewState() {
        patcher.after<AutocompleteViewModel>(
            "getAutocompleteViewState",
            String::class.java,
            List::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType!!
        ) { param: XC_MethodHook.MethodHookParam ->
            try {
                val query = param.args[0] as? String ?: return@after
                val resultState = param.result
                if (resultState !is AutocompleteViewState.Autocomplete) return@after

                // كل الرتب اللي اتبعتت كـ parameters للدالة (المصدر الكامل قبل الفلترة)
                val allCandidateRoles = LinkedHashSet<RoleAutocompletable>()
                for (argIndex in intArrayOf(1, 2)) {
                    val list = param.args[argIndex] as? List<*> ?: continue
                    for (item in list) {
                        if (item is RoleAutocompletable) allCandidateRoles.add(item)
                    }
                }
                if (allCandidateRoles.isEmpty()) return@after

                val currentList = resultState.autocompletables
                val alreadyShownRoleIds = currentList
                    .filterIsInstance<RoleAutocompletable>()
                    .mapNotNull { it.role?.id }
                    .toHashSet()

                // أي رتبة مطابقة للنص اللي المستخدم كاتبه، وغائبة عن اللستة
                // النهائية (يعني اتشالت بسبب canMention=false)، نرجّعها.
                val missingRoles = allCandidateRoles.filter { role ->
                    role.role?.id !in alreadyShownRoleIds && role.matchesText(query)
                }
                if (missingRoles.isEmpty()) return@after

                val newList = ArrayList<Autocompletable>(currentList)
                newList.addAll(missingRoles)

                // ملاحظة: copy() هنا لازم يتنادى بالترتيب (positional)، مش بالأسماء
                // (autocompletables = ...)، لأن الـ stub بتاع ديسكورد اللي بنبني
                // عليه فاقد أسماء الباراميترات الأصلية (Kotlin metadata)، فالكومبايلر
                // بيشوفهم p0..p5 بس ومش بيقبل named arguments هنا.
                param.result = resultState.copy(
                    resultState.isAutocomplete,
                    resultState.isError,
                    resultState.isLoading,
                    newList,
                    resultState.stickers,
                    resultState.token
                )

                LOG.debug("AnyRoleMention: تمت إضافة ${missingRoles.size} رتبة كانت مستبعدة (query=\"$query\")")
            } catch (inner: Throwable) {
                LOG.error("خطأ أثناء إعادة إضافة الرتب المستبعدة", inner)
            }
        }
    }

    // =====================================================================
    // 2) إضافة توضيح (mention)/(silent) بجانب اسم الرتبة في القائمة
    // =====================================================================

    private fun patchBindRoleLabel() {
        patcher.after<AutocompleteItemViewHolder>(
            "bindRole",
            RoleAutocompletable::class.java
        ) { param: XC_MethodHook.MethodHookParam ->
            try {
                if (!settings.getBool(SETTING_SHOW_LABEL, true)) return@after

                val roleAutocompletable = param.args[0] as? RoleAutocompletable ?: return@after
                val nameTextView = findRoleNameTextView(this, roleAutocompletable) ?: return@after

                val suffix = if (roleAutocompletable.canMention) " (mention)" else " (silent)"
                val realRole = roleAutocompletable.role
                val baseName = (if (realRole != null) getRoleDisplayName(realRole) else null)
                    ?: nameTextView.text.toString()
                nameTextView.text = baseName + suffix
            } catch (inner: Throwable) {
                LOG.error("خطأ أثناء إضافة توضيح mention/silent", inner)
            }
        }
    }

    /**
     * getRole().getName() مش متاحة كـ property مباشر في نسخة الـ stub اللي
     * بنبني عليها (الحقل الحقيقي private وبدون accessor بالاسم القياسي).
     * بنستخدم reflection بدل ما نعتمد على اسم getter مبهم قد يتغيّر.
     */
    private fun getRoleDisplayName(guildRole: Any): String? {
        for (getterName in arrayOf("getName", "g")) {
            try {
                val m = guildRole.javaClass.getMethod(getterName)
                val v = m.invoke(guildRole)
                if (v is String) return v
            } catch (ignored: Throwable) {
            }
        }
        // fallback: أي دالة من غير باراميترات بترجع String (غير toString)
        for (m in guildRole.javaClass.methods) {
            if (m.parameterCount == 0 && m.returnType == String::class.java && m.name != "toString") {
                try {
                    val v = m.invoke(guildRole) as? String
                    if (!v.isNullOrEmpty()) return v
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    /**
     * بدل الاعتماد على اسم حقل مبهم (زي "e") قد يتغيّر بين البنايات، بندور
     * على أي TextView جوه binding.* نصه الحالي == اسم الرتبة بالظبط (لأن
     * bindRole الأصلية كانت خلاص حطّت الاسم فيه قبل ما الـ hook بتاعنا يشتغل)،
     * وده بيخلي الكود شغال حتى لو الحقل اتسمّى حرف تاني في نسخة تانية.
     */
    private fun findRoleNameTextView(holder: AutocompleteItemViewHolder, role: RoleAutocompletable): TextView? {
        return try {
            val bindingField = holder.javaClass.getDeclaredField("binding")
            bindingField.isAccessible = true
            val binding = bindingField.get(holder) ?: return null

            val realRole = role.role ?: return null
            val roleName = getRoleDisplayName(realRole) ?: return null
            for (f in binding.javaClass.declaredFields) {
                if (TextView::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    val tv = f.get(binding) as? TextView ?: continue
                    if (tv.text?.toString() == roleName) return tv
                }
            }
            null
        } catch (t: Throwable) {
            LOG.error("findRoleNameTextView فشلت", t)
            null
        }
    }
}

class AnyRoleMentionSettings : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Any Role Mention")

        val ctx = view.context
        val switch = android.widget.Switch(ctx).apply {
            text = "Show (mention)/(silent) label next to role names"
            isChecked = AnyRoleMentionPluginRef.plugin?.settings
                ?.getBool(AnyRoleMention.SETTING_SHOW_LABEL, true) ?: true
            setPadding(32, 32, 32, 32)
            setOnCheckedChangeListener { _, isChecked ->
                AnyRoleMentionPluginRef.plugin?.settings
                    ?.setBool(AnyRoleMention.SETTING_SHOW_LABEL, isChecked)
            }
        }
        addView(switch)
    }
}

/**
 * مرجع بسيط للوصول لـ settings الخاصة بالبلوقن من صفحة الإعدادات، بما إن
 * SettingsPage بيتفتح بمعزل عن نسخة الـ Plugin. عبّي `plugin` من start().
 */
object AnyRoleMentionPluginRef {
    var plugin: AnyRoleMention? = null
}
