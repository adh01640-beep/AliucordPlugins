package com.aliucord.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
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

    override fun start(context: Context) {}
    override fun stop(context: Context) {}
}

@SuppressLint("SetTextI18n")
class SmartProfileSettings : SettingsPage() {
    private val decoMap = LinkedHashMap<String, String>()
    private val effectMap = LinkedHashMap<String, String>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Smart Profile Editor")

        val ctx = view.context
        decoMap["None"] = ""
        effectMap["None"] = ""

        val scrollView = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val displayNameInput = TextInput(ctx, "Display Name")
        val pronounsInput = TextInput(ctx, "Pronouns")
        val bioInput = TextInput(ctx, "Bio")
        val primaryColorInput = TextInput(ctx, "Primary Color (Hex, e.g. #000000)")
        val accentColorInput = TextInput(ctx, "Accent Color (Hex, e.g. #FFFF00)")

        val decoLabel = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_TextView).apply { text = "Avatar Decoration" }
        val decoSpinner = Spinner(ctx)
        
        val effectLabel = TextView(ctx, null, 0, com.lytefast.flexinput.R.i.UiKit_TextView).apply { text = "Profile Effect" }
        val effectSpinner = Spinner(ctx)

        layout.addView(displayNameInput)
        layout.addView(pronounsInput)
        layout.addView(bioInput)
        layout.addView(primaryColorInput)
        layout.addView(accentColorInput)
        
        layout.addView(decoLabel)
        layout.addView(decoSpinner)
        layout.addView(effectLabel)
        layout.addView(effectSpinner)

        // Fetch Shop Catalog to populate Spinners
        fetchCollectibles(ctx, decoSpinner, effectSpinner)

        val loadBtn = Button(ctx).apply {
            text = "Load Current Data"
            setOnClickListener {
                Utils.threadPool.execute {
                    try {
                        val token = StoreStream.getAuthentication().authToken
                        val req = Http.Request("https://discord.com/api/v9/users/@me/profile?with_mutual_guilds=false", "GET")
                            .setHeader("Authorization", token)
                        val res = req.execute()

                        if (res.statusCode == 200) {
                            val json = JSONObject(res.text())
                            val userObj = json.optJSONObject("user")
                            val profileObj = json.optJSONObject("user_profile")

                            Utils.mainThread.post {
                                userObj?.optString("global_name", "")?.let { displayNameInput.editText.setText(it) }
                                profileObj?.optString("pronouns", "")?.let { pronounsInput.editText.setText(it) }
                                profileObj?.optString("bio", "")?.let { bioInput.editText.setText(it) }

                                val colors = profileObj?.optJSONArray("theme_colors")
                                if (colors != null && colors.length() == 2) {
                                    primaryColorInput.editText.setText(String.format("#%06X", 0xFFFFFF and colors.getInt(0)))
                                    accentColorInput.editText.setText(String.format("#%06X", 0xFFFFFF and colors.getInt(1)))
                                }
                                Utils.showToast("Profile data loaded!")
                            }
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post { Utils.showToast("Failed to load data") }
                    }
                }
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "Save Profile"
            setOnClickListener {
                Utils.threadPool.execute {
                    try {
                        val token = StoreStream.getAuthentication().authToken
                        val json = JSONObject()

                        val dName = displayNameInput.editText.text.toString()
                        if (dName.isNotEmpty()) json.put("global_name", dName)

                        json.put("pronouns", pronounsInput.editText.text.toString())
                        json.put("bio", bioInput.editText.text.toString())

                        val selectedDecoName = decoSpinner.selectedItem?.toString() ?: "None"
                        val selectedEffectName = effectSpinner.selectedItem?.toString() ?: "None"
                        
                        val decoId = decoMap[selectedDecoName] ?: ""
                        val effectId = effectMap[selectedEffectName] ?: ""
                        
                        if (decoId.isNotEmpty()) json.put("avatar_decoration_id", decoId)
                        if (effectId.isNotEmpty()) json.put("profile_effect_id", effectId)

                        val pColor = primaryColorInput.editText.text.toString()
                        val aColor = accentColorInput.editText.text.toString()
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

                        val request = Http.Request("https://discord.com/api/v9/users/@me", "PATCH")
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
        }

        layout.addView(loadBtn)
        layout.addView(saveBtn)
        scrollView.addView(layout)
        addView(scrollView)
    }

    private fun fetchCollectibles(ctx: Context, decoSpinner: Spinner, effectSpinner: Spinner) {
        Utils.threadPool.execute {
            try {
                val token = StoreStream.getAuthentication().authToken
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
