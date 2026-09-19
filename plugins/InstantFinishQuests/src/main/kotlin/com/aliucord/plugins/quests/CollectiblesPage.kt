package com.aliucord.plugins.quests

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.RxUtils.subscribe
import com.discord.utilities.captcha.CaptchaHelper
import com.lytefast.flexinput.R
import rx.Subscriber

class CollectiblesPage : SettingsPage() {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Loading Collectibles...")

        val context = view.context

        Utils.threadPool.execute {
            try {
                val questsResponse = QuestsApi.getQuests()
                val claimableQuests = questsResponse.quests.filter {
                    it.userStatus?.completedAt != null && it.userStatus?.claimedAt == null
                }

                Utils.mainThread.post {
                    setActionBarTitle("Collectibles")

                    if (claimableQuests.isEmpty()) {
                        val noQuestsView = TextView(context).apply {
                            text = "No claimable rewards available at the moment."
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(16, 64, 16, 16)
                        }
                        linearLayout.addView(noQuestsView)
                    } else {
                        claimableQuests.forEach { quest ->
                            addClaimCard(context, quest)
                        }
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post {
                    setActionBarTitle("Collectibles")
                    val errorView = TextView(context).apply {
                        text = "Failed to load collectibles. Please try again."
                        setTextColor(Color.parseColor("#ED4245"))
                        gravity = Gravity.CENTER
                        setPadding(16, 64, 16, 16)
                    }
                    linearLayout.addView(errorView)
                }
            }
        }
    }

    private fun addClaimCard(context: Context, quest: Quest) {
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
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, com.aliucord.Constants.Fonts.whitney_semibold)
        }
        card.addView(title)

        val rewardsText = quest.config.rewardsConfig.rewards.joinToString(", ") { 
            it.messages.nameWithArticle.removePrefix("a ").removePrefix("an ") 
        }
        val subtitle = TextView(context).apply {
            text = "Reward: $rewardsText"
            setTextColor(Color.parseColor("#B9BBBE"))
            textSize = 14f
            setPadding(0, DimenUtils.dpToPx(4), 0, DimenUtils.dpToPx(12))
        }
        card.addView(subtitle)

        val claimButton = TextView(context).apply {
            text = "Claim Reward"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#57F287"))
                cornerRadius = DimenUtils.dpToPx(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                DimenUtils.dpToPx(40)
            )
        }
        card.addView(claimButton)
        linearLayout.addView(card)

        claimButton.setOnClickListener {
            performClaim(claimButton, quest, null)
        }
    }

    private fun performClaim(button: TextView, quest: Quest, captchaSolution: QuestCaptchaSolution?) {
        button.isEnabled = false
        button.alpha = 0.5f
        button.text = if (captchaSolution == null) "Claiming..." else "Verifying Captcha..."

        Utils.threadPool.execute {
            try {
                QuestsApi.claimReward(quest, captchaSolution)
                Utils.mainThread.post {
                    button.text = "Claimed"
                    button.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#4F545C"))
                        cornerRadius = DimenUtils.dpToPx(4).toFloat()
                    }
                    Utils.showToast("Reward successfully claimed")
                }
            } catch (e: Exception) {
                val challenge = (e as? QuestApiException)?.captchaChallenge
                if (challenge != null && captchaSolution == null) {
                    Utils.mainThread.post {
                        button.text = "Awaiting Captcha"
                        val request = CaptchaHelper.CaptchaRequest.HCaptcha(
                            challenge.siteKey,
                            Utils.appActivity,
                            challenge.rqdata
                        )
                        CaptchaHelper.INSTANCE.tryShowCaptcha(request).subscribe(
                            object : Subscriber<String>() {
                                override fun onNext(token: String) {
                                    performClaim(
                                        button,
                                        quest,
                                        QuestCaptchaSolution(token, challenge.rqtoken, challenge.sessionId)
                                    )
                                }
                                override fun onError(error: Throwable) {
                                    button.isEnabled = true
                                    button.alpha = 1f
                                    button.text = "Claim Reward"
                                }
                                override fun onCompleted() {}
                            }
                        )
                    }
                } else {
                    Utils.mainThread.post {
                        button.isEnabled = true
                        button.alpha = 1f
                        button.text = "Claim Reward"
                    }
                }
            }
        }
    }
}
