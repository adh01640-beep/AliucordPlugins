package com.aliucord.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
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

    private fun getDiscordToken(): String {
        try {
            val auth = StoreStream.getAuthentication()
            val possibleNames = arrayOf("getAuthToken", "getToken", "authToken", "token")
            for (name in possibleNames) {
                try {
                    val method = auth.javaClass.methods.find { it.name == name }
                    if (method != null) {
                        val t = method.invoke(auth) as? String
                        if (!t.isNullOrEmpty()) return t
                    }
                    val field = auth.javaClass.declaredFields.find { it.name == name }
                    if (field != null) {
                        field.isAccessible = true
                        val t = field.get(auth) as? String
                        if (!t.isNullOrEmpty()) return t
                    }
                } catch (e: Exception) {}
            }
            
            for (field in auth.javaClass.declaredFields) {
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val value = field.get(auth) as? String
                    if (value != null && (value.startsWith("MTA") || value.startsWith("mfa.") || value.length > 50)) {
                        return value
                    }
                }
            }
        } catch (e: Exception) {}
        return ""
    }

    private fun createLabel(ctx: Context, textStr: String): TextView {
        return TextView(ctx).apply {
            text = textStr
            textSize = 15f
            setTextColor(Color.parseColor("#B9BBBE")) // لون مقارب لديسكورد
            setPadding(0, 24, 0, 8)
        }
    }

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

        val profileTypeLabel = createLabel(ctx, "Editing Target")
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

        val decoLabel = createLabel(ctx, "Avatar Decoration")
        val decoSpinner = Spinner(ctx)
        
        val effectLabel = createLabel(ctx, "Profile Effect")
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

        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        val loadBtn = Button(ctx).apply {
            text = "Load Current Data"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val isServer = profileTypeSpinner.selectedItemPosition == 1
                loadCurrentProfileData(isServer, displayNameInput, pronounsInput, bioInput, primaryColorInput, accentColorInput, decoSpinner, effectSpinner)
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "Save Profile"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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

        buttonsLayout.addView(loadBtn)
        buttonsLayout.addView(saveBtn)
        layout.addView(buttonsLayout)
        
        scrollView.addView(layout)
        addView(scrollView)
    }

    private fun loadCurrentProfileData(
        isServer: Boolean, nameInput: TextInput, pronounsInput: TextInput, bioInput: TextInput, pColorInput: TextInput, aColorInput: TextInput, decoSpinner: Spinner, effectSpinner: Spinner
    ) {
        Utils.threadPool.execute {
            try {
                val token = getDiscordToken()
                if (token.isEmpty()) {
                    Utils.mainThread.post { Utils.showToast("Error: Missing Token!") }
                    return@execute
                }

                val url = if (isServer && currentGuildId != 0L) {
                    "https://discord.com/api/v9/users/@me/profile?with_mutual_guilds=false&guild_id=$currentGuildId"
                } else {
                    "https://discord.com/api/v9/users/@me/profile?with_mutual_guilds=false"
                }

                val req = Http.Request(url, "GET").setHeader("Authorization", token)
                val res = req.execute()

                if (res.statusCode in 200..299) {
                    val json = JSONObject(res.text())
                    val userObj = json.optJSONObject("user")
                    
                    val targetObj = if (isServer && json.has("guild_member_profile")) {
                        json.optJSONObject("guild_member_profile")
                    } else {
                        json.optJSONObject("user_profile")
                    }
                    val memberObj = json.optJSONObject("guild_member")

                    // استخراج معرفات التأثيرات الحالية
                    val currentDecoId = userObj?.optJSONObject("avatar_decoration_data")?.optString("sku_id", "") ?: ""
                    val currentEffectId = targetObj?.optJSONObject("profile_effect")?.optString("id", "") ?: ""

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

                        // تعيين التأثيرات في القوائم المنسدلة
                        val decoName = decoMap.entries.find { it.value == currentDecoId }?.key ?: "None"
                        val effectName = effectMap.entries.find { it.value == currentEffectId }?.key ?: "None"
                        
                        (decoSpinner.adapter as? ArrayAdapter<String>)?.getPosition(decoName)?.let { if(it >= 0) decoSpinner.setSelection(it) }
                        (effectSpinner.adapter as? ArrayAdapter<String>)?.getPosition(effectName)?.let { if(it >= 0) effectSpinner.setSelection(it) }

                        Utils.showToast(if (isServer) "Server Profile loaded!" else "Global Profile loaded!")
                    }
                } else {
                    Utils.mainThread.post { Utils.showToast("Failed to load profile: ${res.statusCode}") }
                }
            } catch (e: Exception) {
                Utils.mainThread.post { Utils.showToast("Request Failed") }
            }
        }
    }

    private fun saveProfileData(
        isServer: Boolean, name: String, pronouns: String, bio: String, decoName: String, effectName: String, pColor: String, aColor: String
    ) {
        Utils.threadPool.execute {
            try {
                val token = getDiscordToken()
                if (token.isEmpty()) return@execute

                val decoId = decoMap[decoName] ?: ""
                val effectId = effectMap[effectName] ?: ""

                // 1. تحديث الاسم والزينة (مسار users/@me)
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
                    Http.Request("https://discord.com/api/v9/guilds/$currentGuildId/members/@me", "PATCH")
                        .setHeader("Authorization", token)
                        .setHeader("Content-Type", "application/json")
                        .executeWithBody(nickJson.toString())
                }

                // 3. تحديث البايو، الألوان والتأثيرات (مسار profile)
                val profileJson = JSONObject()
                if (pronouns.isNotEmpty()) profileJson.put("pronouns", pronouns)
                if (bio.isNotEmpty()) profileJson.put("bio", bio)
                profileJson.put("profile_effect_id", effectId.ifEmpty { JSONObject.NULL })

                if (pColor.isNotEmpty() && aColor.isNotEmpty()) {
                    try {
                        val pInt = Color.parseColor(pColor)
                        val aInt = Color.parseColor(aColor)
                        profileJson.put("theme_colors", JSONArray().put(pInt).put(aInt))
                    } catch (e: Exception) {
                        Utils.mainThread.post { Utils.showToast("Invalid Color! Use #RRGGBB") }
                        return@execute
                    }
                }

                val profileUrl = if (isServer && currentGuildId != 0L) {
                    "https://discord.com/api/v9/users/@me/guilds/$currentGuildId/profile"
                } else {
                    "https://discord.com/api/v9/users/@me/profile"
                }

                val response = Http.Request(profileUrl, "PATCH")
                    .setHeader("Authorization", token)
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(profileJson.toString())

                Utils.mainThread.post {
                    if (response.statusCode in 200..299) {
                        Utils.showToast("Profile saved successfully!")
                    } else {
                        Utils.showToast("Error ${response.statusCode}: Update failed")
                    }
                }
            } catch (e: Exception) {
                Utils.mainThread.post { Utils.showToast("Update Failed: ${e.message}") }
            }
        }
    }

    private fun fetchCollectibles(ctx: Context, decoSpinner: Spinner, effectSpinner: Spinner) {
        Utils.threadPool.execute {
            try {
                val token = getDiscordToken()
                if (token.isEmpty()) return@execute

                val req = Http.Request("https://discord.com/api/v9/collectibles/categories", "GET")
                    .setHeader("Authorization", token)
                val res = req.execute()

                if (res.statusCode in 200..299) {
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
                                val skuId = item.optString("sku_id", item.optString("id")) 
                                val id = item.optString("id")
                                
                                if (type == 0) decoMap[prodName] = skuId 
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
