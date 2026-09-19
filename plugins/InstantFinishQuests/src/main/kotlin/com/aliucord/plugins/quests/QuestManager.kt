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
                processQuests(settings)
            }
        }, 10, 3600, TimeUnit.SECONDS)
    }

    fun stopAutoRunner() {
        try {
            scheduler?.shutdownNow()
            scheduler = null
        } catch (e: Exception) {}
    }

    fun processQuests(settings: SettingsAPI) {
        Utils.threadPool.execute {
            try {
                val response = QuestsApi.getQuests()
                val now = System.currentTimeMillis()

                val validQuests = response.quests.filter { quest ->
                    val expires = runCatching { TimeUtils.parseUTCDate(quest.config.expiresAt) }.getOrDefault(0L)
                    expires > now && quest.userStatus?.completedAt == null && quest.userStatus?.claimedAt == null
                }

                for (quest in validQuests) {
                    processSingleQuest(quest, settings)
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {}
        }
    }

    private fun processSingleQuest(quest: Quest, settings: SettingsAPI) {
        try {
            val tasks = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks ?: return
            val isStreamTask = tasks.keys.any { it.contains("STREAM") || it.contains("PLAY") }
            val isVideoTask = tasks.keys.any { it.contains("VIDEO") }

            if (isStreamTask) {
                val altToken = settings.getString("alt_token", "")
                val voiceId = settings.getString("voice_id", "")
                val serverId = settings.getString("server_id", "")
                
                if (altToken.isBlank() || voiceId.isBlank() || serverId.isBlank()) {
                    return
                }

                try {
                    QuestsApi.enroll(quest)
                } catch (e: Exception) {}

                try {
                    QuestsApi.enroll(quest, altToken)
                } catch (e: Exception) {}

            } else if (isVideoTask) {
                try {
                    if (quest.userStatus?.enrolledAt == null) {
                        QuestsApi.enroll(quest)
                    }
                    val videoTask = tasks.values.firstOrNull { it.target > 0 }
                    if (videoTask != null) {
                        QuestsApi.reportVideoProgress(quest.id, videoTask.target.toDouble())
                    }
                } catch (e: Exception) {}
            } else {
                try {
                    if (quest.userStatus?.enrolledAt == null) {
                        QuestsApi.enroll(quest)
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
}
