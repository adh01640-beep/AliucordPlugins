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
                    Thread.sleep((8000L..12000L).random())
                }
            } catch (e: Exception) {}
        }
    }

    fun finishSingleQuest(quest: Quest, settings: SettingsAPI): Pair<Boolean, String> {
        return try {
            val tasks = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks ?: return Pair(false, "Quest data unavailable")
            val taskKey = tasks.keys.firstOrNull() ?: return Pair(false, "Unknown quest type")
            val task = tasks[taskKey] ?: return Pair(false, "Task data unavailable")

            if (taskKey.contains("STREAM") || taskKey.contains("PLAY")) {
                val altToken = settings.getString("alt_token", "")
                val voiceId = settings.getString("voice_id", "")
                val serverId = settings.getString("server_id", "")
                
                if (altToken.isBlank() || voiceId.isBlank() || serverId.isBlank()) {
                    return Pair(false, "Stream settings missing")
                }
                try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                try { QuestsApi.enroll(quest, altToken) } catch (e: Exception) {}
                Pair(true, "Quest started successfully")
                
            } else if (taskKey.contains("VIDEO")) {
                if (quest.userStatus?.enrolledAt == null) {
                    try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                }
                
                Utils.threadPool.execute {
                    try {
                        var currentProgress = quest.userStatus?.progress?.get(taskKey)?.value?.toDouble() ?: 0.0
                        val target = task.target.toDouble()
                        
                        while (currentProgress < target) {
                            currentProgress += (5..7).random().toDouble()
                            if (currentProgress > target) currentProgress = target
                            
                            val updated = QuestsApi.reportVideoProgress(quest.id, currentProgress)
                            if (updated.completedAt != null) break
                            
                            Thread.sleep((7000L..8500L).random())
                        }
                    } catch (e: Exception) {}
                }
                Pair(true, "Processing safely in background")
                
            } else {
                if (quest.userStatus?.enrolledAt == null) {
                    try { QuestsApi.enroll(quest) } catch (e: Exception) {}
                }
                Pair(true, "Quest enrolled successfully")
            }
        } catch (e: Exception) {
            Pair(false, "Could not process quest")
        }
    }
}
