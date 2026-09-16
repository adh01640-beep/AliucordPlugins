package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.RxUtils.subscribe
import com.discord.api.presence.ClientStatus
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = false)
class LastOnlineTracker : Plugin() {

    init {
        settingsTab = SettingsTab(LastOnlineSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val rowViewId = View.generateViewId()
    private val lastKnownStatus = ConcurrentHashMap<Long, ClientStatus>()
    private val records = ConcurrentHashMap<Long, PresenceRecord>()

    data class PresenceRecord(
        var lastOnline: Long? = null,
        var lastIdle: Long? = null,
        var lastDnd: Long? = null,
        var lastSeenOfflineMessage: Long? = null,
    )

    val defaultFormat = "dd/MM/yy, hh:mm a"

    private fun timestampFormat(): String =
        settings.getString("timestamp_format", defaultFormat)

    private fun loadRecords() {
        try {
            val json = JSONObject(settings.getString("presence_records", "{}"))
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.getJSONObject(key)
                records[key.toLong()] = PresenceRecord(
                    lastOnline = obj.optLong("online", -1).takeIf { it != -1L },
                    lastIdle = obj.optLong("idle", -1).takeIf { it != -1L },
                    lastDnd = obj.optLong("dnd", -1).takeIf { it != -1L },
                    lastSeenOfflineMessage = obj.optLong("msg", -1).takeIf { it != -1L },
                )
            }
        } catch (e: Exception) {
            logger.error("loadRecords", e)
        }
    }

    private fun saveRecords() {
        try {
            val json = JSONObject()
            for ((userId, record) in records) {
                val obj = JSONObject()
                record.lastOnline?.let { obj.put("online", it) }
                record.lastIdle?.let { obj.put("idle", it) }
                record.lastDnd?.let { obj.put("dnd", it) }
                record.lastSeenOfflineMessage?.let { obj.put("msg", it) }
                json.put(userId.toString(), obj)
            }
            settings.setString("presence_records", json.toString())
        } catch (e: Exception) {
            logger.error("saveRecords", e)
        }
    }

    fun getLastOnlineText(userId: Long): String {
        val record = records[userId]
        val timestamps = listOfNotNull(
            record?.lastOnline,
            record?.lastIdle,
            record?.lastDnd,
            record?.lastSeenOfflineMessage,
        )
        val latest = timestamps.maxOrNull() ?: return "undetected yet"

        return try {
            SimpleDateFormat(timestampFormat(), Locale.getDefault()).format(Date(latest))
        } catch (e: Exception) {
            SimpleDateFormat(defaultFormat, Locale.getDefault()).format(Date(latest))
        }
    }

    override fun start(context: Context) {
        loadRecords()

        // Continuously track presence changes for every user, not just ones whose
        // profile is currently open, so data is ready the moment it's needed.
        try {
            StoreStream.getPresences().observeAllPresences().subscribe { map ->
                val now = System.currentTimeMillis()

                for ((userId, presence) in map as Map<Long, Presence>) {
                    val status = presence.status
                    lastKnownStatus[userId] = status

                    val record = records.getOrPut(userId) { PresenceRecord() }
                    when (status) {
                        ClientStatus.ONLINE -> record.lastOnline = now
                        ClientStatus.IDLE -> record.lastIdle = now
                        ClientStatus.DND -> record.lastDnd = now
                        else -> {}
                    }
                }
                saveRecords()
            }
        } catch (e: Exception) {
            logger.error("presence subscription failed", e)
        }

        // Fallback: if we catch a message from someone whose last known status
        // was offline/invisible, record that moment as our best guess.
        try {
            val messageClass = Class.forName("com.discord.api.message.Message")
            val handleMessageCreate = StoreStream::class.java.getDeclaredMethod("handleMessageCreate", messageClass)

            patcher.patch(handleMessageCreate, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val message = param.args[0] ?: return
                        val author = message.javaClass.getMethod("getAuthor").invoke(message) ?: return
                        val authorId = (author.javaClass.getMethod("getId").invoke(author) as? Number)?.toLong() ?: return

                        val status = lastKnownStatus[authorId]
                        if (status == null || status == ClientStatus.OFFLINE || status == ClientStatus.INVISIBLE) {
                            val record = records.getOrPut(authorId) { PresenceRecord() }
                            record.lastSeenOfflineMessage = System.currentTimeMillis()
                            saveRecords()
                        }
                    } catch (e: Exception) {
                        logger.error("message hook", e)
                    }
                }
            })
        } catch (e: Exception) {
            logger.error("could not hook handleMessageCreate", e)
        }

        // Inject the "Last Online:" row right after the About Me card, inside the
        // sheet's main content container - matches where BetterUserDetails places
        // its own rows, confirmed from its real source.
        patcher.after<WidgetUserSheet>(
            "configureNote",
            WidgetUserSheetViewModel.ViewState.Loaded::class.java,
        ) { (_, viewState: WidgetUserSheetViewModel.ViewState.Loaded) ->
            try {
                val root = view ?: return@after
                val userId = viewState.user.id

                val content = root.findViewById<LinearLayout>(Utils.getResId("user_sheet_content", "id"))
                    ?: return@after
                val aboutMeCard = content.findViewById<View>(Utils.getResId("about_me_card", "id"))
                    ?: return@after

                val text = "Last Online: ${getLastOnlineText(userId)}"
                val existing = content.findViewById<TextView>(rowViewId)

                if (existing != null) {
                    existing.text = text
                } else {
                    val dp = DimenUtils.defaultPadding
                    val row = TextView(root.context).apply {
                        id = rowViewId
                        setPadding(dp, dp / 2, dp, dp / 2)
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }
                    row.text = text

                    content.addView(row, content.indexOfChild(aboutMeCard) + 1)
                }
            } catch (e: Exception) {
                logger.error("configureNote hook", e)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        saveRecords()
    }
}
