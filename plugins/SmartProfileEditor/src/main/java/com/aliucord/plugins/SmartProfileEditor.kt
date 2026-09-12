package com.aliucord.plugins

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.facebook.drawee.view.SimpleDraweeView
import org.json.JSONArray
import org.json.JSONObject

@AliucordPlugin
class SmartProfileEditor : Plugin() {
    companion object {
        val logger = Logger("SmartProfileEditor")
    }

    init {
        settingsTab = SettingsTab(SmartProfileSettings::class.java)
    }

    override fun start(context: Context) {
        commands.registerCommand(
            "editprofile",
            "Open the Smart Profile Editor (Visual GUI)",
            emptyList()
        ) {
            Utils.openPageWithProxy(Utils.appActivity, SmartProfileSettings())
            CommandsAPI.CommandResult()
        }
    }

    override fun stop(context: Context) {
        commands.unregisterAll()
    }
}

@SuppressLint("SetTextI18n")
class SmartProfileSettings : SettingsPage() {
    // asset = هاش الصورة الحقيقي المطلوب لإرساله في avatar_decoration_data.asset
    // id    = sku_id الحقيقي المطلوب في avatar_decoration_data.sku_id
    data class CollectibleItem(val name: String, val id: String, val asset: String, val iconUrl: String, val isEffect: Boolean)
    data class GuildItem(val name: String, val id: Long)

    private val decoList = mutableListOf<CollectibleItem>()
    private val effectList = mutableListOf<CollectibleItem>()
    private val guildList = mutableListOf<GuildItem>()
    private val tagGuildList = mutableListOf<GuildItem>() // بدون "Global" لأن التاج مرتبط بسيرفر دائماً

    private var selectedPrimaryColor: Int? = null
    private var selectedAccentColor: Int? = null
    private var tagEnabled = false

    /**
     * ==========================================================================
     * إصلاح جذري: الطريقة القديمة (البحث في حقول StoreAuthentication عن أي
     * String يبدأ بـ "mfa."/"MTA" أو طوله>50) غير موثوقة إطلاقاً وغالباً
     * ترجع "" فتفشل كل العمليات بصمت (return@execute).
     *
     * الطريقة الصحيحة المؤكدة (فحصتها من بلوقن "Token" الرسمي الشغّال):
     *   RestAPI.AppHeadersProvider.INSTANCE.getAuthToken()
     * وهي دالة singleton حقيقية داخل ديسكورد نفسه تُرجع توكن الحساب الحالي
     * مباشرة دون أي تخمين reflection.
     * ==========================================================================
     */
    private fun getDiscordToken(): String {
        return try {
            RestAPI.AppHeadersProvider.INSTANCE.authToken ?: ""
        } catch (e: Exception) {
            SmartProfileEditor.logger.error("Token Extraction Failed", e)
            ""
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Advanced Profile Editor")

        val ctx = view.context
        val scrollView = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // 1. قائمة السيرفرات التلقائية (للبروفايل: Global أو سيرفر معيّن)
        val guildLabel = createLabel(ctx, "Target Profile (Global or Server)")
        val guildSpinner = Spinner(ctx)
        loadGuilds(ctx, guildSpinner)

        val displayNameInput = TextInput(ctx, "Display Name / Server Nickname")
        val pronounsInput = TextInput(ctx, "Pronouns")
        val bioInput = TextInput(ctx, "Bio")

        // 2. اختيار الألوان بصرياً
        val colorsLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 16) }

        val primaryColorBtn = Button(ctx).apply {
            text = "Primary Color"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                showColorPicker(ctx, "Primary Color") { color ->
                    selectedPrimaryColor = color
                    this.setBackgroundColor(color)
                }
            }
        }

        val accentColorBtn = Button(ctx).apply {
            text = "Accent Color"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                showColorPicker(ctx, "Accent Color") { color ->
                    selectedAccentColor = color
                    this.setBackgroundColor(color)
                }
            }
        }

        colorsLayout.addView(primaryColorBtn)
        colorsLayout.addView(accentColorBtn)

        // 3. القوائم المنسدلة بالصور للزينة والتأثيرات
        val decoLabel = createLabel(ctx, "Avatar Decoration")
        val decoSpinner = Spinner(ctx)

        val effectLabel = createLabel(ctx, "Profile Effect")
        val effectSpinner = Spinner(ctx)

        // 4. تاج السيرفر (Server Tag / Primary Guild) — جديد
        val tagLabel = createLabel(ctx, "Server Tag (Clan Tag)")
        val tagGuildSpinner = Spinner(ctx)
        loadTagGuilds(ctx, tagGuildSpinner)

        val tagSwitch = Switch(ctx).apply {
            text = "Show this server's tag on my profile"
            setPadding(0, 8, 0, 16)
            setOnCheckedChangeListener { _, isChecked -> tagEnabled = isChecked }
        }

        layout.addView(guildLabel)
        layout.addView(guildSpinner)
        layout.addView(displayNameInput)
        layout.addView(pronounsInput)
        layout.addView(bioInput)
        layout.addView(colorsLayout)
        layout.addView(decoLabel)
        layout.addView(decoSpinner)
        layout.addView(effectLabel)
        layout.addView(effectSpinner)
        layout.addView(tagLabel)
        layout.addView(tagGuildSpinner)
        layout.addView(tagSwitch)

        fetchCollectibles(ctx, decoSpinner, effectSpinner)

        val saveBtn = Button(ctx).apply {
            text = "Save Profile"
            setPadding(0, 32, 0, 0)
            setOnClickListener {
                val selectedGuild = guildList.getOrNull(guildSpinner.selectedItemPosition)
                val selectedTagGuild = tagGuildList.getOrNull(tagGuildSpinner.selectedItemPosition)
                saveProfileData(
                    selectedGuild?.id ?: 0L,
                    displayNameInput.editText.text.toString(),
                    pronounsInput.editText.text.toString(),
                    bioInput.editText.text.toString(),
                    decoSpinner.selectedItem as? CollectibleItem,
                    effectSpinner.selectedItem as? CollectibleItem,
                    selectedTagGuild,
                    tagEnabled
                )
            }
        }

        layout.addView(saveBtn)
        scrollView.addView(layout)
        addView(scrollView)
    }

    private fun createLabel(ctx: Context, textStr: String): TextView {
        return TextView(ctx).apply {
            text = textStr
            textSize = 15f
            setTextColor(Color.parseColor("#B9BBBE"))
            setPadding(0, 24, 0, 8)
        }
    }

    private fun loadGuilds(ctx: Context, spinner: Spinner) {
        guildList.clear()
        guildList.add(GuildItem("Global Profile (Default)", 0L))

        StoreStream.getGuilds().guilds.values.forEach { guild ->
            guildList.add(GuildItem(guild.name, guild.id))
        }

        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, guildList.map { it.name })
    }

    private fun loadTagGuilds(ctx: Context, spinner: Spinner) {
        tagGuildList.clear()
        StoreStream.getGuilds().guilds.values.forEach { guild ->
            tagGuildList.add(GuildItem(guild.name, guild.id))
        }
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tagGuildList.map { it.name })
    }

    private fun showColorPicker(ctx: Context, title: String, onColorSelected: (Int) -> Unit) {
        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val colorPreview = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150)
            setBackgroundColor(Color.BLACK)
        }

        val rBar = SeekBar(ctx).apply { max = 255 }
        val gBar = SeekBar(ctx).apply { max = 255 }
        val bBar = SeekBar(ctx).apply { max = 255 }

        val updateColor = {
            colorPreview.setBackgroundColor(Color.rgb(rBar.progress, gBar.progress, bBar.progress))
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) = updateColor()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        rBar.setOnSeekBarChangeListener(listener)
        gBar.setOnSeekBarChangeListener(listener)
        bBar.setOnSeekBarChangeListener(listener)

        dialogView.addView(colorPreview)
        dialogView.addView(TextView(ctx).apply { text = "Red" })
        dialogView.addView(rBar)
        dialogView.addView(TextView(ctx).apply { text = "Green" })
        dialogView.addView(gBar)
        dialogView.addView(TextView(ctx).apply { text = "Blue" })
        dialogView.addView(bBar)

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Select") { _, _ ->
                onColorSelected(Color.rgb(rBar.progress, gBar.progress, bBar.progress))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchCollectibles(ctx: Context, decoSpinner: Spinner, effectSpinner: Spinner) {
        Utils.threadPool.execute {
            val token = getDiscordToken()
            if (token.isEmpty()) {
                Utils.mainThread.post { Utils.showToast("Failed to get account token") }
                return@execute
            }

            try {
                val res = Http.Request("https://discord.com/api/v9/collectibles/categories", "GET")
                    .setHeader("Authorization", token)
                    .execute()

                if (res.statusCode in 200..299) {
                    decoList.add(CollectibleItem("None", "", "", "", false))
                    effectList.add(CollectibleItem("None", "", "", "", true))

                    val categories = JSONArray(res.text())
                    for (i in 0 until categories.length()) {
                        val products = categories.getJSONObject(i).optJSONArray("products") ?: continue
                        for (j in 0 until products.length()) {
                            val prod = products.getJSONObject(j)
                            val items = prod.optJSONArray("items") ?: continue
                            val name = prod.optString("name", "Unknown")

                            for (k in 0 until items.length()) {
                                val item = items.getJSONObject(k)
                                val type = item.optInt("type")
                                val id = item.optString("id")
                                val skuId = item.optString("sku_id", id)
                                val asset = item.optString("asset", "")

                                if (type == 0) { // Decoration
                                    val url = "https://cdn.discordapp.com/avatar-decoration-presets/$asset.png"
                                    decoList.add(CollectibleItem(name, skuId, asset, url, false))
                                } else if (type == 1) { // Effect
                                    val url = "https://cdn.discordapp.com/profile-effects/$id/preview.png"
                                    effectList.add(CollectibleItem(name, id, asset, url, true))
                                }
                            }
                        }
                    }
                    Utils.mainThread.post {
                        decoSpinner.adapter = CollectibleAdapter(ctx, decoList)
                        effectSpinner.adapter = CollectibleAdapter(ctx, effectList)
                    }
                } else {
                    Utils.mainThread.post { Utils.showToast("Collectibles fetch failed: ${res.statusCode}") }
                }
            } catch (e: Exception) {
                SmartProfileEditor.logger.error("Fetch collectibles error", e)
                Utils.mainThread.post { Utils.showToast("Failed to load collectibles") }
            }
        }
    }

    private fun saveProfileData(
        guildId: Long,
        name: String,
        pronouns: String,
        bio: String,
        deco: CollectibleItem?,
        effect: CollectibleItem?,
        tagGuild: GuildItem?,
        tagEnabledNow: Boolean
    ) {
        Utils.threadPool.execute {
            val token = getDiscordToken()
            if (token.isEmpty()) {
                Utils.mainThread.post { Utils.showToast("Failed to get account token") }
                return@execute
            }

            val isServer = guildId != 0L
            var anySuccess = false
            var lastError: String? = null

            try {
                // 1. تحديث الاسم العام + الديكوريشن (فقط للبروفايل العام Global)
                if (!isServer) {
                    val userJson = JSONObject()
                    if (name.isNotEmpty()) userJson.put("global_name", name)

                    // avatar_decoration_data يجب أن يكون object فيه asset و sku_id،
                    // أو null صريحة لإزالة الديكوريشن — وليس string مفرد.
                    if (deco != null) {
                        if (deco.id.isEmpty()) {
                            userJson.put("avatar_decoration_data", JSONObject.NULL)
                        } else {
                            userJson.put(
                                "avatar_decoration_data",
                                JSONObject().put("asset", deco.asset).put("sku_id", deco.id)
                            )
                        }
                    }

                    // تاج السيرفر (Server Tag / primary_guild) — يعمل من مسار /users/@me
                    if (tagGuild != null) {
                        if (tagEnabledNow) {
                            userJson.put(
                                "primary_guild",
                                JSONObject()
                                    .put("identity_guild_id", tagGuild.id.toString())
                                    .put("identity_enabled", true)
                            )
                        } else {
                            userJson.put(
                                "primary_guild",
                                JSONObject().put("identity_enabled", false)
                            )
                        }
                    }

                    if (userJson.length() > 0) {
                        val r = Http.Request("https://discord.com/api/v9/users/@me", "PATCH")
                            .setHeader("Authorization", token)
                            .setHeader("Content-Type", "application/json")
                            .executeWithBody(userJson.toString())
                        if (r.statusCode in 200..299) anySuccess = true else lastError = "users/@me: ${r.statusCode} ${r.text()}"
                    }
                }

                // 2. تحديث نك السيرفر (خاص بسيرفر معيّن فقط)
                if (isServer && name.isNotEmpty()) {
                    val nickJson = JSONObject().put("nick", name)
                    val r = Http.Request("https://discord.com/api/v9/guilds/$guildId/members/@me", "PATCH")
                        .setHeader("Authorization", token)
                        .setHeader("Content-Type", "application/json")
                        .executeWithBody(nickJson.toString())
                    if (r.statusCode in 200..299) anySuccess = true else lastError = "guild nick: ${r.statusCode} ${r.text()}"
                }

                // 3. البايو، الألوان والتأثيرات (Profile endpoint، عام أو خاص بسيرفر)
                val profileJson = JSONObject()
                if (pronouns.isNotEmpty()) profileJson.put("pronouns", pronouns)
                if (bio.isNotEmpty()) profileJson.put("bio", bio)
                if (effect != null) {
                    profileJson.put("profile_effect_id", if (effect.id.isEmpty()) JSONObject.NULL else effect.id)
                }
                if (selectedPrimaryColor != null && selectedAccentColor != null) {
                    profileJson.put("theme_colors", JSONArray().put(selectedPrimaryColor).put(selectedAccentColor))
                }

                if (profileJson.length() > 0) {
                    val profileUrl = if (isServer)
                        "https://discord.com/api/v9/users/@me/guilds/$guildId/profile"
                    else
                        "https://discord.com/api/v9/users/@me/profile"

                    val res = Http.Request(profileUrl, "PATCH")
                        .setHeader("Authorization", token)
                        .setHeader("Content-Type", "application/json")
                        .executeWithBody(profileJson.toString())

                    if (res.statusCode in 200..299) anySuccess = true else lastError = "profile: ${res.statusCode} ${res.text()}"
                }

                Utils.mainThread.post {
                    if (anySuccess) Utils.showToast("Saved Successfully!")
                    else Utils.showToast("Error: ${lastError ?: "nothing to save"}")
                }
            } catch (e: Exception) {
                SmartProfileEditor.logger.error("Save profile error", e)
                Utils.mainThread.post { Utils.showToast("Failed to save: ${e.message}") }
            }
        }
    }

    private inner class CollectibleAdapter(context: Context, items: List<CollectibleItem>) : ArrayAdapter<CollectibleItem>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = createView(position)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = createView(position)

        private fun createView(position: Int): View {
            val item = getItem(position)
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
            }

            val imageView = SimpleDraweeView(context).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(0, 0, 24, 0) }
                if (item?.iconUrl?.isNotEmpty() == true) setImageURI(Uri.parse(item.iconUrl))
            }

            val textView = TextView(context).apply {
                text = item?.name ?: "None"
                textSize = 16f
                setTextColor(Color.WHITE)
            }

            layout.addView(imageView)
            layout.addView(textView)
            return layout
        }
    }
}

