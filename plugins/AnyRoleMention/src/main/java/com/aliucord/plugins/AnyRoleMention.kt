package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

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

    private fun patchAutocompleteViewState() {
        val viewModelClass = Class.forName(VIEWMODEL_CLASS)
        val method: Method = viewModelClass.declaredMethods.firstOrNull {
            it.name == "getAutocompleteViewState" && it.parameterTypes.size == 4
        } ?: return

        method.isAccessible = true

        patcher.patch(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val query = param.args[0] as? String ?: ""
                    val resultState = param.result ?: return
                    val autocompleteStateClass = Class.forName(VIEWSTATE_AUTOCOMPLETE_CLASS)
                    if (!autocompleteStateClass.isInstance(resultState)) return

                    val roleClass = Class.forName(ROLE_AUTOCOMPLETABLE_CLASS)
                    val guildRoleClass = Class.forName("com.discord.api.role.GuildRole")

                    val getAutocompletables = autocompleteStateClass.getMethod("getAutocompletables")
                    @Suppress("UNCHECKED_CAST")
                    val currentList = getAutocompletables.invoke(resultState) as List<Any>

                    val alreadyShownRoleIds = HashSet<Long>()
                    for (item in currentList) {
                        if (roleClass.isInstance(item)) {
                            getRoleId(item)?.let { alreadyShownRoleIds.add(it) }
                        }
                    }

                    val guildId = StoreStream.getGuildSelected().selectedGuildId
                    if (guildId == 0L) return

                    // الحل النهائي: استخدام Kotlin Map لتجنب أي تعارض في استدعاء values
                    val guildRolesMap = StoreStream.getGuilds().roles[guildId] as? Map<*, *> ?: return
                    val allRoles = guildRolesMap.values

                    val roleAutoConstructor = roleClass.getConstructor(guildRoleClass, Boolean::class.javaPrimitiveType)
                    
                    val cleanQuery = query.replace("@", "").lowercase()
                    val missingRoles = ArrayList<Any>()
                    
                    for (role in allRoles) {
                        if (role == null) continue
                        val roleId = getRoleIdFromGuildRole(role) ?: continue
                        if (roleId in alreadyShownRoleIds) continue 

                        val roleName = getRoleDisplayName(role) ?: continue
                        
                        if (cleanQuery.isEmpty() || roleName.lowercase().contains(cleanQuery)) {
                            val roleAutoInstance = roleAutoConstructor.newInstance(role, false)
                            missingRoles.add(roleAutoInstance)
                        }
                    }

                    if (missingRoles.isEmpty()) return

                    val newList = ArrayList(currentList)
                    newList.addAll(missingRoles)

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
                } catch (inner: Throwable) {
                    LOG.error("خطأ أثناء إعادة إضافة الرتب المستبعدة", inner)
                }
            }
        })
    }

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

    private fun getRoleId(roleAuto: Any): Long? {
        return try {
            val getRole = roleAuto.javaClass.getMethod("getRole")
            val realRole = getRole.invoke(roleAuto) ?: return null
            getRoleIdFromGuildRole(realRole)
        } catch (t: Throwable) {
            null
        }
    }

    private fun getRoleIdFromGuildRole(guildRole: Any): Long? {
        return try {
            val getId = guildRole.javaClass.getMethod("getId")
            when (val idVal = getId.invoke(guildRole)) {
                is Long -> idVal
                is Number -> idVal.toLong()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun getRoleDisplayName(guildRole: Any): String? {
        for (getterName in arrayOf("getName", "g")) {
            try {
                val m = guildRole.javaClass.getMethod(getterName)
                val v = m.invoke(guildRole)
                if (v is String) return v
            } catch (ignored: Throwable) {}
        }
        for (m in guildRole.javaClass.methods) {
            if (m.parameterCount == 0 && m.returnType == String::class.java && m.name != "toString") {
                try {
                    val v = m.invoke(guildRole) as? String
                    if (!v.isNullOrEmpty()) return v
                } catch (ignored: Throwable) {}
            }
        }
        return null
    }

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

object AnyRoleMentionPluginRef {
    var plugin: AnyRoleMention? = null
}

