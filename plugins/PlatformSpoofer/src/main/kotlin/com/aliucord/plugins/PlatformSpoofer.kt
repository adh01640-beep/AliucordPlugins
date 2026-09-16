package com.aliucord.plugins

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import de.robv.android.xposed.XC_MethodHook

@AliucordPlugin(requiresRestart = true)
class PlatformSpoofer : Plugin() {

    init {
        settingsTab = SettingsTab(PlatformSpooferSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        try {
            val identifyClass = Class.forName("com.discord.gateway.io.OutgoingPayload\$Identify")
            val constructor = identifyClass.constructors.firstOrNull { it.parameterTypes.size == 6 }
            
            if (constructor != null) {
                patcher.patch(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val originalMap = param.args[4] as? Map<*, *> ?: return
                            val newMap = HashMap<String, Any?>()
                            
                            for ((k, v) in originalMap) {
                                if (k is String) {
                                    newMap[k] = v
                                }
                            }

                            val platform = settings.getString("platform", "Mobile")
                            when (platform) {
                                "Computer" -> {
                                    newMap["os"] = "Windows"
                                    newMap["browser"] = "Discord Client"
                                    newMap.remove("device")
                                    newMap.remove("device_vendor")
                                }
                                "Web" -> {
                                    newMap["os"] = "Windows"
                                    newMap["browser"] = "Chrome"
                                    newMap.remove("device")
                                    newMap.remove("device_vendor")
                                }
                            }

                            param.args[4] = newMap
                        } catch (e: Exception) {
                            logger.error("Error spoofing platform payload", e)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            logger.error("Failed to hook Identify payload", e)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
