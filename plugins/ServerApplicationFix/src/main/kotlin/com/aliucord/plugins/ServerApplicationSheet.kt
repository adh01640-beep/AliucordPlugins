package com.aliucord.plugins

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.aliucord.views.Button
import com.aliucord.widgets.BottomSheet
import org.json.JSONArray
import org.json.JSONObject

class ServerApplicationSheet(
    private val guildId: String,
    private val formFields: JSONArray,
    private val inviteCode: String,
    private val plugin: ServerApplicationFix
) : BottomSheet() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = view.context

        val title = TextView(ctx).apply {
            text = "Server Application"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 40)
        }
        addView(title)

        val inputs = mutableListOf<Pair<JSONObject, EditText>>()

        for (i in 0 until formFields.length()) {
            val field = formFields.getJSONObject(i)
            val fieldType = field.optString("field_type")
            val label = field.optString("label")

            if (fieldType == "TEXT_INPUT" || fieldType == "PARAGRAPH") {
                val tv = TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.parseColor("#B9BBBE"))
                    setPadding(0, 20, 0, 10)
                }
                val et = EditText(ctx).apply {
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.parseColor("#72767D"))
                }
                addView(tv)
                addView(et)
                inputs.add(Pair(field, et))
            }
        }

        val submitBtn = Button(ctx).apply {
            text = "Submit Application"
            setOnClickListener {
                plugin.submitApplication(guildId, inputs, inviteCode)
                dismiss()
            }
        }
        addView(submitBtn)
    }
}
