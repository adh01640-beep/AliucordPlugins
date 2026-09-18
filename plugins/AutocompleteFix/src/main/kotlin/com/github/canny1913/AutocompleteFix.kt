package com.aliucord.plugins

import android.content.Context
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

@AliucordPlugin
class AutocompleteFix : Plugin() {

    companion object {
        private val LOG = Logger("AutocompleteFix")
        private const val COMPARATOR_CLASS = "com.discord.widgets.chat.input.autocomplete.AutocompletableComparator"
        private const val ADAPTER_CLASS = "com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter"
    }

    override fun start(context: Context) {
        patchComparator()
        patchAdapterStableIds()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun patchComparator() {
        try {
            val comparatorClass = Class.forName(COMPARATOR_CLASS)
            val compareMethod = comparatorClass.declaredMethods.firstOrNull { 
                it.name == "compare" && it.parameterTypes.size == 2 
            }
            
            if (compareMethod != null) {
                patcher.patch(compareMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? Int ?: return
                        
                        // plugin will start working only when the result is 0
                        if (result == 0) {
                            val o1 = param.args[0]
                            val o2 = param.args[1]
                            
                            if (o1 === o2) return 

                            val id1 = extractStableIdFor(o1)
                            val id2 = extractStableIdFor(o2)

                            // autocomplete fix by id
                            if (id1 != null && id2 != null) {
                                if (id1 != id2) {
                                    param.result = id1.compareTo(id2)
                                }
                            } else {
                                
                                param.result = System.identityHashCode(o1).compareTo(System.identityHashCode(o2))
                            }
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            LOG.error("error detected", t)
        }
    }

    private fun patchAdapterStableIds() {
        try {
            val adapterClass = Class.forName(ADAPTER_CLASS)
            val getItem = adapterClass.getMethod("getItem", Int::class.javaPrimitiveType)
            getItem.isAccessible = true

            val getItemId = adapterClass.getMethod("getItemId", Int::class.javaPrimitiveType)
            getItemId.isAccessible = true

            patcher.patch(getItemId, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val thisAdapter = param.thisObject
                        val position = param.args[0] as Int

                        val item = getItem.invoke(thisAdapter, position)
                        val uniqueId = extractStableIdFor(item)
                        if (uniqueId != null) {
                            param.result = uniqueId
                        }
                    } catch (inner: Throwable) {
                         
                    }
                }
            })
        } catch (t: Throwable) {
            LOG.error("error detected", t)
        }
    }

    private fun extractStableIdFor(autocompletable: Any?): Long? {
        if (autocompletable == null) return null
        
        // user mentions autocompletefix
        try {
            val getUser = autocompletable.javaClass.getMethod("getUser")
            val user = getUser.invoke(autocompletable)
            if (user != null) {
                val getId = user.javaClass.getMethod("getId")
                return (getId.invoke(user) as? Number)?.toLong()
            }
        } catch (e: Throwable) { }

        // roles autocompletefix
        try {
            val getRole = autocompletable.javaClass.getMethod("getRole")
            val role = getRole.invoke(autocompletable)
            if (role != null) {
                val getId = role.javaClass.getMethod("getId")
                return (getId.invoke(role) as? Number)?.toLong()
            }
        } catch (e: Throwable) { }

        // slash commands so it will work on slash commands too
        try {
            val getCommand = autocompletable.javaClass.getMethod("getCommand")
            val command = getCommand.invoke(autocompletable)
            if (command != null) {
                val getId = command.javaClass.getMethod("getId")
                return (getId.invoke(command) as? Number)?.toLong()
            }
        } catch (e: Throwable) { }
        
        return null
    }
}
