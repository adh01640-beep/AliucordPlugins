package com.aliucord.plugins

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.aliucord.Http
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.stores.StoreStream
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
    private val decoList = mutableListOf<CollectibleItem>()
    private val effectList = mutableListOf<CollectibleItem>()
    private val guildList = mutableListOf<GuildItem>()
    
    private var selectedPrimaryColor: Int? = null
    private var selectedAccentColor: Int? = null

    data class CollectibleItem(val name: String, val id: String, val iconUrl: String, val isEffect: Boolean)
    data class GuildItem(val name: String, val id: Long)

    // استخراج التوكن التلقائي الجذري (يعمل دائماً)
    private fun getDiscordToken(): String {
        try {
            val auth = StoreStream.getAuthentication()
            for (field in auth.javaClass.declaredFields) {
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val value = field.get(auth) as? String
                    if (value != null && (value.startsWith("mfa.") || value.startsWith("MTA") || value.length > 50)) {
                        return value
                    }
                }
            }
        } catch (e: Exception) {
            SmartProfileEditor.logger.error("Token Extraction Failed", e)
        }
        return ""
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

        // 1. قائمة السيرفرات التلقائية
        val guildLabel = createLabel(ctx, "Target Profile (Global or Server)")
        val guildSpinner = Spinner(ctx)
        loadGuilds(ctx, guildSpinner)

        val displayNameInput = TextInput(ctx, "Display Name / Server Nickname")
        val pronounsInput = TextInput(ctx, "Pronouns")
        val bioInput = TextInput(ctx, "Bio")

        // 2. اختيار الألوان بصرياً (بدون أكواد)
        val colorsLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 16) }
        
        val primaryColorBtn = Button(ctx).apply {
            text = "Primary Color"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showColorPicker(ctx, "Primary Color") { color -> 
                selectedPrimaryColor = color
                this.setBackgroundColor(color)
            }}
        }
        
        val accentColorBtn = Button(ctx).apply {
            text = "Accent Color"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showColorPicker(ctx, "Accent Color") { color -> 
                selectedAccentColor = color
                this.setBackgroundColor(color)
            }}
        }

        colorsLayout.addView(primaryColorBtn)
        colorsLayout.addView(accentColorBtn)

        // 3. القوائم المنسدلة بالصور للزينة والتأثيرات
        val decoLabel = createLabel(ctx, "Avatar Decoration")
        val decoSpinner = Spinner(ctx)
        
        val effectLabel = createLabel(ctx, "Profile Effect")
        val effectSpinner = Spinner(ctx)

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

        fetchCollectibles(ctx, decoSpinner, effectSpinner)

        val saveBtn = Button(ctx).apply {
            text = "Save Profile"
            setPadding(0, 32, 0, 0)
            setOnClickListener {
                val selectedGuild = guildList.getOrNull(guildSpinner.selectedItemPosition)
                saveProfileData(
                    selectedGuild?.id ?: 0L,
                    displayNameInput.editText.text.toString(),
                    pronounsInput.editText.text.toString(),
                    bioInput.editText.text.toString(),
                    (decoSpinner.selectedItem as? CollectibleItem)?.id ?: "",
                    (effectSpinner.selectedItem as? CollectibleItem)?.id ?: ""
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

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, guildList.map { it.name })
        spinner.adapter = adapter
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
            val color = Color.rgb(rBar.progress, gBar.progress, bBar.progress)
            colorPreview.setBackgroundColor(color)
        }

        rBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) = updateColor(); override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        gBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) = updateColor(); override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        bBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) = updateColor(); override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })

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
            if (token.isEmpty()) return@execute

            try {
                val res = Http.Request("https://discord.com/api/v9/collectibles/categories", "GET")
                    .setHeader("Authorization", token)
                    .execute()

                if (res.statusCode in 200..299) {
                    decoList.add(CollectibleItem("None", "", "", false))
                    effectList.add(CollectibleItem("None", "", "", true))

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
                                    val url = "https://cdn.discordapp.com/avatar-decoration-presets/${asset}.png"
                                    decoList.add(CollectibleItem(name, skuId, url, false))
                                } else if (type == 1) { // Effect
                                    val url = "https://cdn.discordapp.com/profile-effects/${id}/preview.png"
                                    effectList.add(CollectibleItem(name, id, url, true))
                                }
                            }
                        }
                    }
                    Utils.mainThread.post {
                        decoSpinner.adapter = CollectibleAdapter(ctx, decoList)
                        effectSpinner.adapter = CollectibleAdapter(ctx, effectList)
                    }
                }
            } catch (e: Exception) {
                SmartProfileEditor.logger.error("Fetch collectibles error", e)
            }
        }
    }

    private fun saveProfileData(guildId: Long, name: String, pronouns: String, bio: String, decoId: String, effectId: String) {
        Utils.threadPool.execute {
            val token = getDiscordToken()
            if (token.isEmpty()) return@execute

            val isServer = guildId != 0L

            try {
                // 1. تحديث الاسم والزينة (مسار Users)
                val userJson = JSONObject()
                if (!isServer && name.isNotEmpty()) userJson.put("global_name", name)
                if (!isServer) userJson.put("avatar_decoration_id", decoId.ifEmpty { JSONObject.NULL })

                if (userJson.length() > 0) {
                    Http.Request("https://discord.com/api/v9/users/@me", "PATCH")
                        .setHeader("Authorization", token)
                        .setHeader("Content-Type", "application/json")
                        .executeWithBody(userJson.toString())
                }

                // 2. تحديث بروفايل السيرفر (Nick)
                if (isServer && name.isNotEmpty()) {
                    val nickJson = JSONObject().put("nick", name)
                    Http.Request("https://discord.com/api/v9/guilds/$guildId/members/@me", "PATCH")
                        .setHeader("Authorization", token)
                        .setHeader("Content-Type", "application/json")
                        .executeWithBody(nickJson.toString())
                }

                // 3. تحديث البايو، الألوان والتأثيرات (مسار Profile)
                val profileJson = JSONObject()
                if (pronouns.isNotEmpty()) profileJson.put("pronouns", pronouns)
                if (bio.isNotEmpty()) profileJson.put("bio", bio)
                profileJson.put("profile_effect_id", effectId.ifEmpty { JSONObject.NULL })

                if (selectedPrimaryColor != null && selectedAccentColor != null) {
                    profileJson.put("theme_colors", JSONArray().put(selectedPrimaryColor).put(selectedAccentColor))
                }

                val profileUrl = if (isServer) "https://discord.com/api/v9/users/@me/guilds/$guildId/profile" else "https://discord.com/api/v9/users/@me/profile"
                
                val res = Http.Request(profileUrl, "PATCH")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(profileJson.toString())

                Utils.mainThread.post {
                    if (res.statusCode in 200..299) Utils.showToast("Saved Successfully!")
                    else Utils.showToast("Error ${res.statusCode}")
                }
            } catch (e: Exception) {
                Utils.mainThread.post { Utils.showToast("Failed to save") }
            }
        }
    }

    // محول مخصص يعرض الصورة والاسم في القائمة المنسدلة
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

