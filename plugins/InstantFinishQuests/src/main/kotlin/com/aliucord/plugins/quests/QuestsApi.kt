package com.aliucord.plugins.quests

import com.aliucord.Http
import com.aliucord.utils.GsonUtils

object QuestsApi {
    fun getQuests(): QuestsResponse {
        return Http.Request.newDiscordRNRequest("/quests/@me", "GET").execute().readJson()
    }

    fun enroll(quest: Quest, customToken: String? = null): QuestUserStatus {
        val body = EnrollQuestRequest(
            trafficMetadataRaw = quest.trafficMetadataRaw,
            trafficMetadataSealed = quest.trafficMetadataSealed
        )
        return post("/quests/${quest.id}/enroll", body, customToken)
    }

    fun reportVideoProgress(questId: String, timestamp: Double): QuestUserStatus {
        return post("/quests/$questId/video-progress", VideoProgressRequest(timestamp))
    }

    fun claimReward(quest: Quest, captchaSolution: QuestCaptchaSolution? = null): QuestUserStatus {
        val body = ClaimRewardRequest(
            platform = quest.config.rewardsConfig.platforms.firstOrNull() ?: 0,
            trafficMetadataSealed = quest.trafficMetadataSealed
        )
        val request = Http.Request.newDiscordRNRequest(
            "/quests/${quest.id}/claim-reward",
            "POST"
        )
        if (captchaSolution != null) {
            request
                .setHeader("x-captcha-key", captchaSolution.key)
                .setHeader("x-captcha-rqtoken", captchaSolution.rqtoken)
                .setHeader("x-captcha-session-id", captchaSolution.sessionId)
        }
        return request
            .executeWithJson(GsonUtils.gsonRestApi, body)
            .readJson()
    }

    private inline fun <reified T> post(path: String, body: Any, customToken: String? = null): T {
        val request = Http.Request.newDiscordRNRequest(path, "POST")
        if (customToken != null) {
            request.setHeader("Authorization", customToken)
        }
        return request.executeWithJson(GsonUtils.gsonRestApi, body).readJson()
    }

    private inline fun <reified T> Http.Response.readJson(): T = use { response ->
        if (!response.ok()) {
            val errorBody = runCatching { response.text() }.getOrNull() ?: ""
            val error = runCatching {
                GsonUtils.fromJson(errorBody, QuestApiError::class.java)
            }.getOrNull()
            
            val challenge = error?.let {
                if (!it.captchaKey.isNullOrEmpty() && it.captchaSiteKey != null &&
                    it.captchaRqdata != null && it.captchaRqtoken != null &&
                    it.captchaSessionId != null
                ) {
                    QuestCaptchaChallenge(
                        it.captchaSiteKey,
                        it.captchaRqdata,
                        it.captchaRqtoken,
                        it.captchaSessionId
                    )
                } else null
            }
            
            val message = if (challenge != null) "Discord requires a captcha" else error?.message ?: "Discord returned HTTP ${response.statusCode}"
            throw QuestApiException(response.statusCode, challenge, message)
        }
        response.json(GsonUtils.gsonRestApi, T::class.java)
    }
}
