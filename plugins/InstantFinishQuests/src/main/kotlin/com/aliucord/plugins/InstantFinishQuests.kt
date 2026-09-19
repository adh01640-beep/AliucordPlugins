package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.plugins.quests.CollectiblesPage
import com.aliucord.plugins.quests.QuestManager
import com.aliucord.plugins.quests.QuestProgressPage
import com.discord.widgets.settings.WidgetSettings
import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = false)
class InstantFinishQuests : Plugin() {

    init {
        settingsTab = SettingsTab(InstantFinishSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
    }

    override fun start(context: Context) {
        QuestManager.startAutoRunner(settings)

        patcher.patch(
            WidgetSettings::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { callFrame ->
                try {
                    val view = callFrame.args[0] as CoordinatorLayout
                    val nestedScrollView = view.getChildAt(1) as? NestedScrollView ?: return@Hook
                    val layout = nestedScrollView.getChildAt(0) as? LinearLayoutCompat ?: return@Hook
                    val ctx = layout.context

                    val targetId = Utils.getResId("qr_scanner", "id")
                    val targetView = layout.findViewById<TextView>(targetId)
                    val baseIndex = if (targetView != null) layout.indexOfChild(targetView) else 0

                    val instantFinishBtn = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        text = "Instant Finish Quests"
                        setCompoundDrawablesWithIntrinsicBounds(
                            Utils.tintToTheme(ctx.getDrawable(R.e.ic_play_arrow_24dp)),
                            null, null, null
                        )
                        setOnClickListener {
                            QuestManager.processQuests(settings)
                            Utils.showToast("Processing quests in background...")
                        }
                    }
                    layout.addView(instantFinishBtn, baseIndex + 1)

                    val progressBtn = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        text = "Quest Progress"
                        val iconId = Utils.getResId("ic_history_24dp", "drawable")
                        val drawable = if (iconId != 0) ctx.getDrawable(iconId) else ctx.getDrawable(R.e.ic_info_24dp)
                        setCompoundDrawablesWithIntrinsicBounds(
                            Utils.tintToTheme(drawable),
                            null, null, null
                        )
                        setOnClickListener {
                            Utils.openPageWithProxy(ctx, QuestProgressPage())
                        }
                    }
                    layout.addView(progressBtn, baseIndex + 2)

                    val collectiblesBtn = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        text = "Collectibles"
                        setCompoundDrawablesWithIntrinsicBounds(
                            Utils.tintToTheme(ctx.getDrawable(R.e.ic_gift_24dp)),
                            null, null, null
                        )
                        setOnClickListener {
                            Utils.openPageWithProxy(ctx, CollectiblesPage())
                        }
                    }
                    layout.addView(collectiblesBtn, baseIndex + 3)

                } catch (e: Exception) {}
            }
        )
    }

    override fun stop(context: Context) {
        QuestManager.stopAutoRunner()
        patcher.unpatchAll()
    }
}
