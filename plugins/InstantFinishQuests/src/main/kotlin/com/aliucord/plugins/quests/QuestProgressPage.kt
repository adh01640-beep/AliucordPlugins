package com.aliucord.plugins.quests

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.utilities.time.TimeUtils

class QuestProgressPage : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Loading Progress...")

        val context = view.context

        Utils.threadPool.execute {
            try {
                val questsResponse = QuestsApi.getQuests()
                val now = System.currentTimeMillis()

                val activeQuests = questsResponse.quests.filter {
                    val expires = runCatching { TimeUtils.parseUTCDate(it.config.expiresAt) }.getOrDefault(0L)
                    expires > now && it.userStatus?.claimedAt == null
                }

                Utils.mainThread.post {
                    setActionBarTitle("Quest Progress")

                    if (activeQuests.isEmpty()) {
                        val noQuestsView = TextView(context).apply {
                            text = "No active quests at the moment."
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(16, 64, 16, 16)
                        }
                        linearLayout.addView(noQuestsView)
                    } else {
                        activeQuests.forEach { quest ->
                            addProgressCard(context, quest)
                        }
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post {
                    setActionBarTitle("Quest Progress")
                    val errorView = TextView(context).apply {
                        text = "Failed to load progress. Please try again."
                        setTextColor(Color.parseColor("#ED4245"))
                        gravity = Gravity.CENTER
                        setPadding(16, 64, 16, 16)
                    }
                    linearLayout.addView(errorView)
                }
            }
        }
    }

    private fun addProgressCard(context: Context, quest: Quest) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2F3136"))
                cornerRadius = DimenUtils.dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    DimenUtils.dpToPx(12),
                    DimenUtils.dpToPx(12),
                    DimenUtils.dpToPx(12),
                    DimenUtils.dpToPx(4)
                )
            }
            setPadding(
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16)
            )
        }

        val title = TextView(context).apply {
            text = quest.config.messages.questName
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_semibold)
        }
        card.addView(title)

        val task = (quest.config.taskConfigV2 ?: quest.config.taskConfig)?.tasks?.values?.firstOrNull()
        val target = task?.target ?: 1
        val progressValue = quest.userStatus?.progress?.values?.firstOrNull()?.value ?: 0
        
        val statusStr = when {
            quest.userStatus?.completedAt != null -> "Completed"
            quest.userStatus?.enrolledAt != null -> "In Progress"
            else -> "Available"
        }

        val statusColor = when {
            quest.userStatus?.completedAt != null -> "#57F287"
            quest.userStatus?.enrolledAt != null -> "#5865F2"
            else -> "#B9BBBE"
        }

        val statusText = TextView(context).apply {
            text = "Status: $statusStr"
            setTextColor(Color.parseColor(statusColor))
            textSize = 14f
            setPadding(0, DimenUtils.dpToPx(8), 0, DimenUtils.dpToPx(4))
        }
        card.addView(statusText)

        val progressText = TextView(context).apply {
            text = "Progress: $progressValue / $target"
            setTextColor(Color.parseColor("#B9BBBE"))
            textSize = 14f
            setPadding(0, 0, 0, DimenUtils.dpToPx(8))
        }
        card.addView(progressText)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                DimenUtils.dpToPx(12)
            )
            max = target
            progress = progressValue
            progressDrawable.setColorFilter(Color.parseColor(statusColor), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        card.addView(progressBar)

        linearLayout.addView(card)
    }
}
