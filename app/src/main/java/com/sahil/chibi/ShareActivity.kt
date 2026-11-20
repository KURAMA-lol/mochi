package com.sahil.chibi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Send to Nana": appears in the Share sheet (screenshot preview > Share, gallery, files).
 * Shows a preview and a caption box; nothing is sent until the user taps Send.
 */
class ShareActivity : Activity() {
    private val uris = ArrayList<Uri>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }
        if (uris.isEmpty()) { finish(); return }
        val items = uris.take(6)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        root.addView(TextView(this).apply { text = "Send to Nana"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val prev = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0x22FFFFFF)
            visibility = View.GONE
        }
        root.addView(prev, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(10) })
        val info = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, dp(8), 0, 0) }
        root.addView(info)
        val first = items[0]
        val mime = try { contentResolver.getType(first) } catch (e: Exception) { null } ?: intent.type ?: ""
        info.text = if (items.size > 1) "${items.size} items" else if (mime.startsWith("image/")) "" else "1 file"
        if (mime.startsWith("image/")) {
            Thread {
                val b = Attach.decodeUri(this, first, 1200)
                runOnUiThread { if (b != null) { prev.setImageBitmap(b); prev.visibility = View.VISIBLE } }
            }.start()
        }
        val cap = EditText(this).apply {
            hint = "Ask Nana something about it (optional)"
            setHintTextColor(0x77FFFFFF); setTextColor(Color.WHITE); textSize = 15f
            minLines = 1; maxLines = 4
            setText(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        }
        root.addView(cap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        val send = Button(this).apply { text = "Send" }
        send.setOnClickListener {
            send.isEnabled = false; send.text = "Sending..."
            val caption = cap.text.toString().trim()
            Thread {
                val atts = ArrayList<Att>()
                for (u in items) Attach.copyIn(this, u)?.let { atts.add(it) }
                runOnUiThread {
                    if (atts.isEmpty()) {
                        android.widget.Toast.makeText(this, "Could not read that file", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            startForegroundService(Attach.putExtras(
                                Intent(this, CompanionService::class.java).setAction(CompanionService.ACT_ATTACH)
                                    .putExtra("send", true).putExtra("caption", caption), atts))
                        } catch (e: Exception) {}
                    }
                    finish()
                }
            }.start()
        }
        row.addView(cancel); row.addView(send)
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(root)
    }
}
