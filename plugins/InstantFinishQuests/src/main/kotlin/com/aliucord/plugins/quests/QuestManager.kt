package com.aliucord.plugins.quests

import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.discord.utilities.time.TimeUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object QuestManager {
    private var scheduler: ScheduledExecutorService? = null

    fun startAutoRunner(settings: SettingsAPI) {
        stopAutoRunner()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            if (settings.getBool("auto_finish", false)) {
                processAllAvailableQuests(settings)
            }
        }, 10, 3600, TimeUnit.SECONDS)
    }

    fun stopAutoRunner() {
        try {
            scheduler?.shutdownNow()
            scheduler = null
        } catch (e: Exception) {}
    }

    private fun processAllAvailableQuests(settings: SettingsAPI) {
        Utils.threadPool.execute {
            try {
                val response = QuestsApi.getQuests()
                val now = System.currentTimeMillis()

                val validQuests = response.quests.filter { quest ->
                    val expires = runCatching { TimeUtils.parseUTCDate(quest.config.expiresAt) }.getOrDefault(0L)
                    expires > now && quest.userStatus?.completedAt == null && quest.userStatus?.claimedAt == null
                }

                for (quest in validQuests) {
                    finishSingleQuest(quest, settings)
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {}
        }
    }

    fun finishSingleQuest(quest: Quest, settings: SettingsAPI): Boolean {
        return try {
            val tasks = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks ?: return false
            val isStreamTask = tasks.keys.any { it.contains("STREAM") || it.contains("PLAY") }
            val isVideoTask = tasks.keys.any { it.contains("VIDEO") }

            if (isStreamTask) {
                val altToken = settings.getString("alt_token", "")
                val voiceId = settings.getString("voice_id", "")
                val serverId = settings.getString("server_id", "")
                
                if (altToken.isNotBlank() && voiceId.isNotBlank() && serverId.isNotBlank()) {
                    try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                    try { QuestsApi.enroll(quest, altToken) } catch (e: Exception) {}
                    true
                } else {
                    false
                }
            } else if (isVideoTask) {
                if (quest.userStatus?.enrolledAt == null) {
                    try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                }
                val videoTask = tasks.values.firstOrNull { it.target > 0 }
                if (videoTask != null) {
                    QuestsApi.reportVideoProgress(quest.id, videoTask.target.toDouble())
                }
                true
            } else {
                if (quest.userStatus?.enrolledAt == null) {
                    try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
