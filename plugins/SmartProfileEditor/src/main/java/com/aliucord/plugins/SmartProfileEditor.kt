package com.aliucord.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
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
            "Open the Smart Profile Editor (Global & Server Profiles)",
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
    private val decoMap = LinkedHashMap<String, String>()
    private val effectMap = LinkedHashMap<String, String>()
    private var currentGuildId: Long = 0L

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Smart Profile Editor")

        val ctx = view.context
        decoMap["None"] = ""
        effectMap["None"] = ""

        currentGuildId = StoreStream.getGuildSelected().selectedGuildId

        val scrollView = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val profileTypeLabel = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_TextView).apply { 
            text = "Editing Target:" 
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        val profileTypeSpinner = Spinner(ctx)
        val profileTypes = mutableListOf("Global Profile (Default)")
        
        if (currentGuildId != 0L) {
            profileTypes.add("Server Profile (Current Server)")
        }
        profileTypeSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, profileTypes)

        val displayNameInput = TextInput(ctx, "Display Name / Server Nickname")
        val pronounsInput = TextInput(ctx, "Pronouns")
        val bioInput = TextInput(ctx, "Bio")
        val primaryColorInput = TextInput(ctx, "Primary Color (Hex, e.g. #000000)")
        val accentColorInput = TextInput(ctx, "Accent Color (Hex, e.g. #FFFF00)")

        val decoLabel = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_TextView).apply { text = "Avatar Decoration"; setPadding(0, 16, 0, 0) }
        val decoSpinner = Spinner(ctx)
        
        val effectLabel = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_TextView).apply { text = "Profile Effect"; setPadding(0, 16, 0, 0) }
        val effectSpinner = Spinner(ctx)

        layout.addView(profileTypeLabel)
        layout.addView(profileTypeSpinner)
        layout.addView(displayNameInput)
        layout.addView(pronounsInput)
        layout.addView(bioInput)
        layout.addView(primaryColorInput)
        layout.addView(accentColorInput)
        layout.addView(decoLabel)
        layout.addView(decoSpinner)
        layout.addView(effectLabel)
        layout.addView(effectSpinner)

        fetchCollectibles(ctx, decoSpinner, effectSpinner)

        val loadBtn = Button(ctx).apply {
            text = "Load Current Data"
            setOnClickListener {
                val isServer = profileTypeSpinner.selectedItemPosition == 1
                loadCurrentProfileData(isServer, displayNameInput, pronounsInput, bioInput, primaryColorInput, accentColorInput)
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "Save Profile"
            setOnClickListener {
                val isServer = profileTypeSpinner.selectedItemPosition == 1
                saveProfileData(
                    isServer, 
                    displayNameInput.editText.text.toString(),
                    pronounsInput.editText.text.toString(),
                    bioInput.editText.text.toString(),
                    decoSpinner.selectedItem?.toString() ?: "None",
                    effectSpinner.selectedItem?.toString() ?: "None",
                    primaryColorInput.editText.text.toString(),
                    accentColorInput.editText.text.toString()
                )
            }
        }

        layout.addView(loadBtn)
        layout.addView(saveBtn)
        scrollView.addView(layout)
        addView(scrollView)
    }

    private fun loadCurrentProfileData(
        isServer: Boolean,
        nameInput: TextInput,
        pronounsInput: TextInput,
        bioInput: TextInput,
        pColorInput: TextInput,
        aColorInput: TextInput
    ) {
        Utils.threadPool.execute {
            try {
                // تم التعديل هنا لاستخدام الدالة الرسمية
                val token = StoreStream.getAuthentication().getAuthToken()
                val url = if (isServer && currentGuildId != 0L) {
                    "https://discord.com/api/v9/users/@me/profile?with_mutual_guilds=false&guild_id=$currentGuildId"
                } else {
                    "https://discord.com/api/v9/users/@me/profile?with_mutual_guilds=false"
                }

                val req = Http.Request(url, "GET").setHeader("Authorization", token)
                val res = req.execute()

                if (res.statusCode == 200) {
                    val json = JSONObject(res.text())
                    val userObj = json.optJSONObject("user")
                    
                    val targetObj = if (isServer && json.has("guild_member_profile")) {
                        json.optJSONObject("guild_member_profile")
                    } else {
                        json.optJSONObject("user_profile")
                    }
                    
                    val memberObj = json.optJSONObject("guild_member")

                    Utils.mainThread.post {
                        val name = if (isServer) memberObj?.optString("nick", "") else userObj?.optString("global_name", "")
                        if (!name.isNullOrEmpty() && name != "null") nameInput.editText.setText(name)

                        targetObj?.optString("pronouns", "")?.takeIf { it != "null" }?.let { pronounsInput.editText.setText(it) }
                        targetObj?.optString("bio", "")?.takeIf { it != "null" }?.let { bioInput.editText.setText(it) }

                        val colors = targetObj?.optJSONArray("theme_colors")
                        if (colors != null && colors.length() == 2) {
                            pColorInput.editText.setText(String.format("#%06X", 0xFFFFFF and colors.getInt(0)))
                            aColorInput.editText.setText(String.format("#%06X", 0xFFFFFF and colors.getInt(1)))
                        }
                        Utils.showToast(if (isServer) "Server Profile loaded!" else "Global Profile loaded!")
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post { Utils.showToast("Failed to load data") }
            }
        }
    }

    private fun saveProfileData(
        isServer: Boolean,
        name: String,
        pronouns: String,
        bio: String,
        decoName: String,
        effectName: String,
        pColor: String,
        aColor: String
    ) {
        Utils.threadPool.execute {
            try {
                // تم التعديل هنا لاستخدام الدالة الرسمية
                val token = StoreStream.getAuthentication().getAuthToken()
                val json = JSONObject()

                if (name.isNotEmpty()) {
                    if (isServer) json.put("nick", name) else json.put("global_name", name)
                }

                if (pronouns.isNotEmpty()) json.put("pronouns", pronouns)
                if (bio.isNotEmpty()) json.put("bio", bio)

                val decoId = decoMap[decoName] ?: ""
                val effectId = effectMap[effectName] ?: ""
                
                if (decoId.isNotEmpty()) json.put("avatar_decoration_id", decoId)
                if (effectId.isNotEmpty()) json.put("profile_effect_id", effectId)

                if (pColor.isNotEmpty() && aColor.isNotEmpty()) {
                    try {
                        val pInt = android.graphics.Color.parseColor(pColor)
                        val aInt = android.graphics.Color.parseColor(aColor)
                        json.put("theme_colors", JSONArray().put(pInt).put(aInt))
                    } catch (e: Exception) {
                        Utils.mainThread.post { Utils.showToast("Invalid Color Format! Use #RRGGBB") }
                        return@execute
                    }
                }

                val url = if (isServer && currentGuildId != 0L) {
                    "https://discord.com/api/v9/guilds/$currentGuildId/members/@me"
                } else {
                    "https://discord.com/api/v9/users/@me"
                }

                val request = Http.Request(url, "PATCH")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")

                val response = request.executeWithBody(json.toString())

                Utils.mainThread.post {
                    if (response.statusCode in 200..299) {
                        Utils.showToast("Profile saved successfully!")
                    } else {
                        val errorMsg = "Error ${response.statusCode}: ${response.text()}"
                        Utils.showToast(errorMsg)
                        SmartProfileEditor.logger.error(errorMsg, null)
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post { Utils.showToast("Request Failed: ${e.message}") }
            }
        }
    }

    private fun fetchCollectibles(ctx: Context, decoSpinner: Spinner, effectSpinner: Spinner) {
        Utils.threadPool.execute {
            try {
                // تم التعديل هنا لاستخدام الدالة الرسمية
                val token = StoreStream.getAuthentication().getAuthToken()
                val req = Http.Request("https://discord.com/api/v9/collectibles/categories", "GET")
                    .setHeader("Authorization", token)
                val res = req.execute()

                if (res.statusCode == 200) {
                    val categories = JSONArray(res.text())
                    for (i in 0 until categories.length()) {
                        val cat = categories.getJSONObject(i)
                        val products = cat.optJSONArray("products") ?: continue
                        for (j in 0 until products.length()) {
                            val prod = products.getJSONObject(j)
                            val items = prod.optJSONArray("items") ?: continue
                            val prodName = prod.optString("name", "Unknown Item")
                            
                            for (k in 0 until items.length()) {
                                val item = items.getJSONObject(k)
                                val type = item.optInt("type")
                                val id = item.optString("id")
                                
                                if (type == 0) decoMap[prodName] = id
                                else if (type == 1) effectMap[prodName] = id
                            }
                        }
                    }
                    Utils.mainThread.post {
                        decoSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, decoMap.keys.toList())
                        effectSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, effectMap.keys.toList())
                    }
                }
            } catch (e: Exception) {
                SmartProfileEditor.logger.error("Failed to fetch collectibles", e)
            }
        }
    }
}
