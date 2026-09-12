package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

/**
 * AnyRoleMention
 * ==========================================================================
 * الفكرة: إظهار كل الرتب في قائمة اقتراحات المنشن (@) حتى لو المستخدم
 * ملوش صلاحية يعمل بينج فعلي بيها، مع توضيح جنب اسم كل رتبة هل اختيارها
 * هيبعت إشعار فعلي (mention) ولا هيتكتب بس من غير تأثير (silent).
 *
 * ملاحظة API: نفس أسلوب الـ Hook المستخدم في MentionDedupeFix اللي أثبت
 * إنه شغال 100% عندك: patcher.patch(method, object : XC_MethodHook() {...})
 * — مش .after<T>(...) اللي كانت غلطة مني في نسخة سابقة.
 *
 * الأساس التقني (مؤكد من فحص bytecode الفعلي لإصدار Discord 126.21):
 *   RoleAutocompletable.canMention : Boolean   <- ديسكورد نفسه بيحسبها
 *   جاهزة لكل رتبة، إحنا بس بنستخدمها، مش بنتلاعب بأي صلاحية حقيقية.
 *
 *   AutocompleteViewModel.getAutocompleteViewState(query, list1, list2, bool)
 *     -> هي الدالة اللي بتجهّز اللستة النهائية المعروضة. بنقارنها باللستة
 *        الكاملة اللي دخلت كـ parameters، ونرجّع أي رتبة اتشالت لكنها لسه
 *        مطابقة للنص اللي المستخدم بيكتبه.
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

        private const val VIEWMODEL_CLASS = "com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel"
        private const val VIEWSTATE_AUTOCOMPLETE_CLASS = "com.discord.widgets.chat.input.autocomplete.AutocompleteViewState\$Autocomplete"
        private const val ROLE_AUTOCOMPLETABLE_CLASS = "com.discord.widgets.chat.input.autocomplete.RoleAutocompletable"
        private const val ITEM_VIEWHOLDER_CLASS = "com.discord.widgets.chat.input.autocomplete.adapter.AutocompleteItemViewHolder"
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
        val viewModelClass = Class.forName(VIEWMODEL_CLASS)
        val method: Method = viewModelClass.declaredMethods.firstOrNull {
            it.name == "getAutocompleteViewState" && it.parameterTypes.size == 4
        } ?: run {
            LOG.warn("لم يتم العثور على getAutocompleteViewState بالتوقيع المتوقع")
            return
        }
        method.isAccessible = true

        patcher.patch(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val query = param.args[0] as? String ?: return
                    val resultState = param.result ?: return
                    val autocompleteStateClass = Class.forName(VIEWSTATE_AUTOCOMPLETE_CLASS)
                    if (!autocompleteStateClass.isInstance(resultState)) return

                    val roleClass = Class.forName(ROLE_AUTOCOMPLETABLE_CLASS)

                    // كل الرتب اللي اتبعتت كـ parameters للدالة (المصدر الكامل قبل الفلترة)
                    val allCandidateRoles = LinkedHashSet<Any>()
                    for (argIndex in intArrayOf(1, 2)) {
                        val list = param.args[argIndex] as? List<*> ?: continue
                        for (item in list) {
                            if (item != null && roleClass.isInstance(item)) allCandidateRoles.add(item)
                        }
                    }
                    if (allCandidateRoles.isEmpty()) return

                    val getAutocompletables = autocompleteStateClass.getMethod("getAutocompletables")
                    @Suppress("UNCHECKED_CAST")
                    val currentList = getAutocompletables.invoke(resultState) as List<Any>

                    val alreadyShownRoleIds = HashSet<Long>()
                    for (item in currentList) {
                        if (roleClass.isInstance(item)) {
                            getRoleId(item)?.let { alreadyShownRoleIds.add(it) }
                        }
                    }

                    // أي رتبة مطابقة للنص اللي المستخدم كاتبه، وغائبة عن اللستة
                    // النهائية (يعني اتشالت بسبب canMention=false)، نرجّعها.
                    val matchesText = roleClass.getMethod("matchesText", String::class.java)
                    val missingRoles = allCandidateRoles.filter { role ->
                        val id = getRoleId(role)
                        val matches = matchesText.invoke(role, query) as? Boolean ?: false
                        (id == null || id !in alreadyShownRoleIds) && matches
                    }
                    if (missingRoles.isEmpty()) return

                    val newList = ArrayList(currentList)
                    newList.addAll(missingRoles)

                    // copy(isAutocomplete, isError, isLoading, autocompletables, stickers, token)
                    // لازم يتنادى بالترتيب (positional) لأن الـ stub فاقد أسماء الباراميترات.
                    val isAutocomplete = autocompleteStateClass.getMethod("isAutocomplete").invoke(resultState)
                    val isError = autocompleteStateClass.getMethod("isError").invoke(resultState)
                    val isLoading = autocompleteStateClass.getMethod("isLoading").invoke(resultState)
                    val stickers = autocompleteStateClass.getMethod("getStickers").invoke(resultState)
                    val token = autocompleteStateClass.getMethod("getToken").invoke(resultState)

                    val copyMethod = autocompleteStateClass.declaredMethods.first { it.name == "copy" && it.parameterTypes.size == 6 }
                    copyMethod.isAccessible = true
                    val newState = copyMethod.invoke(
                        resultState, isAutocomplete, isError, isLoading, newList, stickers, token
                    )

                    param.result = newState
                    LOG.debug("AnyRoleMention: تمت إضافة ${missingRoles.size} رتبة كانت مستبعدة (query=\"$query\")")
                } catch (inner: Throwable) {
                    LOG.error("خطأ أثناء إعادة إضافة الرتب المستبعدة", inner)
                }
            }
        })
    }

    // =====================================================================
    // 2) إضافة توضيح (mention)/(silent) بجانب اسم الرتبة في القائمة
    // =====================================================================

    private fun patchBindRoleLabel() {
        val holderClass = Class.forName(ITEM_VIEWHOLDER_CLASS)
        val roleClass = Class.forName(ROLE_AUTOCOMPLETABLE_CLASS)
        val method = holderClass.getMethod("bindRole", roleClass)
        method.isAccessible = true

        patcher.patch(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (!settings.getBool(SETTING_SHOW_LABEL, true)) return

                    val holder = param.thisObject ?: return
                    val roleAutocompletable = param.args[0] ?: return

                    val canMention = roleClass.getMethod("getCanMention").invoke(roleAutocompletable) as? Boolean ?: true
                    val getRole = roleClass.getMethod("getRole")
                    val realRole = getRole.invoke(roleAutocompletable)
                    val roleName = if (realRole != null) getRoleDisplayName(realRole) else null

                    val nameTextView = findRoleNameTextView(holder, roleName) ?: return

                    val suffix = if (canMention) " (mention)" else " (silent)"
                    val baseName = roleName ?: nameTextView.text.toString()
                    nameTextView.text = baseName + suffix
                } catch (inner: Throwable) {
                    LOG.error("خطأ أثناء إضافة توضيح mention/silent", inner)
                }
            }
        })
    }

    private fun getRoleId(role: Any): Long? {
        return try {
            val getRole = role.javaClass.getMethod("getRole")
            val realRole = getRole.invoke(role) ?: return null
            val getId = realRole.javaClass.getMethod("getId")
            when (val idVal = getId.invoke(realRole)) {
                is Long -> idVal
                is Number -> idVal.toLong()
                else -> null
            }
        } catch (t: Throwable) {
            null
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
    private fun findRoleNameTextView(holder: Any, roleName: String?): TextView? {
        if (roleName == null) return null
        return try {
            val bindingField = holder.javaClass.getDeclaredField("binding")
            bindingField.isAccessible = true
            val binding = bindingField.get(holder) ?: return null

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
 * SettingsPage بيتفتح بمعزل عن نسخة الـ Plugin. بيتعبّى من start().
 */
object AnyRoleMentionPluginRef {
    var plugin: AnyRoleMention? = null
}
