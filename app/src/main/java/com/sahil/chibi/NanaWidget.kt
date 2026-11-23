package com.sahil.chibi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Nana's home-screen widget. The shape and colour are drawn into a bitmap, so any shape can be chosen in Settings. */
class NanaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(context, mgr, ids, false) }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        draw(context, mgr, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACT_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            refresh(context, mgr, mgr.getAppWidgetIds(ComponentName(context, NanaWidget::class.java)), true)
        }
    }

    private fun refresh(c: Context, mgr: AppWidgetManager, ids: IntArray, force: Boolean) {
        draw(c, mgr, ids)
        if (!prefs(c).getBoolean("mail", true) || !Mail.ready(c)) return
        if (!force && Mail.unreadAge(c) < 10 * 60 * 1000L) return
        val pending = goAsync()
        Thread {
            try { Mail.refreshUnread(c); draw(c, mgr, ids) } catch (e: Throwable) {} finally { try { pending.finish() } catch (e: Exception) {} }
        }.start()
    }

    companion object {
        const val ACT_REFRESH = "com.sahil.chibi.WIDGET_REFRESH"

        private fun prefs(c: Context) = c.getSharedPreferences("nana_widget", Context.MODE_PRIVATE)

        fun cfgJson(c: Context): String = JSONObject()
            .put("shape", prefs(c).getString("shape", "round")).put("style", prefs(c).getString("style", "dark"))
            .put("mail", prefs(c).getBoolean("mail", true)).toString()

        fun save(c: Context, shape: String, style: String, mail: Boolean) {
            prefs(c).edit().putString("shape", shape).putString("style", style).putBoolean("mail", mail).apply()
            updateAll(c)
        }

        fun updateAll(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                val ids = m.getAppWidgetIds(ComponentName(c, NanaWidget::class.java))
                if (ids.isNotEmpty()) draw(c, m, ids)
            } catch (e: Exception) {}
        }

        private fun greeting(c: Context): String {
            val name = try { ConfigStore.load(c).persona.userName } catch (e: Throwable) { "" }
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val g = when { h < 5 -> "Up late"; h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; h < 22 -> "Good evening"; else -> "Up late" }
            return if (name.isNotBlank()) g + ", " + name else g
        }

        private fun nextReminder(c: Context): String {
            return try {
                val a = JSONArray(Reminders.json(c))
                val now = System.currentTimeMillis()
                var best: JSONObject? = null
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val at = o.getLong("at")
                    val b = best
                    if (at > now && (b == null || at < b.getLong("at"))) best = o
                }
                val b = best ?: return "No reminders"
                val at = b.getLong("at")
                val t = Calendar.getInstance().apply { timeInMillis = at }
                val n = Calendar.getInstance()
                val same = t.get(Calendar.YEAR) == n.get(Calendar.YEAR) && t.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
                val f = SimpleDateFormat(if (same) "h:mm a" else "EEE h:mm a", Locale.getDefault())
                f.format(Date(at)) + "  " + b.optString("text")
            } catch (e: Exception) { "" }
        }

        private fun fill(style: String): Int = when (style) {
            "black" -> 0xC8000000.toInt()
            "light" -> 0xCCF4F5F8.toInt()
            "accent" -> (Theme.ac and 0x00FFFFFF) or 0xD0000000.toInt()
            else -> 0xB8121316.toInt()
        }

        @Volatile private var nanaBmp: Bitmap? = null

        /** Nana's resting picture (the same one the app uses as a fallback). */
        private fun nana(c: Context): Bitmap? {
            nanaBmp?.let { return it }
            return try {
                val s = c.assets.open("rest.b64").bufferedReader().use { it.readText() }.trim()
                val raw = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size).also { nanaBmp = it }
            } catch (e: Throwable) { null }
        }

        private fun radii(shape: String, w: Int, h: Int, d: Float): FloatArray {
            val m = minOf(w, h).toFloat()
            fun all(x: Float) = FloatArray(8) { x }
            return when (shape) {
                "pill" -> all(h / 2f)
                "soft" -> all(m * 0.34f)
                "leaf" -> floatArrayOf(m * 0.4f, m * 0.4f, 8 * d, 8 * d, m * 0.4f, m * 0.4f, 8 * d, 8 * d)
                "square" -> all(8 * d)
                else -> all(26 * d)
            }
        }

        private fun render(c: Context, wDp: Int, hDp: Int, shape: String, style: String): Bitmap {
            val d = c.resources.displayMetrics.density
            val w = (wDp * d).toInt().coerceIn(160, 1200)
            val h = (hDp * d).toInt().coerceIn(100, 800)
            val wf = w.toFloat()
            val hf = h.toFloat()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val light = style == "light"
            val path = Path().apply { addRoundRect(RectF(0f, 0f, wf, hf), radii(shape, w, h, d), Path.Direction.CW) }
            p.color = fill(style)
            cv.drawPath(path, p)
            cv.save()
            cv.clipPath(path)
            // liquid glass: light pooling at the top-left, a soft shade at the bottom
            p.shader = android.graphics.RadialGradient(wf * 0.18f, -hf * 0.1f, wf * 0.9f, intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, wf, hf, p)
            p.shader = LinearGradient(0f, hf * 0.6f, 0f, hf, 0x00000000, if (light) 0x14000000 else 0x30000000, Shader.TileMode.CLAMP)
            cv.drawRect(0f, hf * 0.6f, wf, hf, p)
            p.shader = null
            val pad = 14 * d
            val barH = 40 * d
            val barTop = hf - pad - barH
            // Nana stands behind the button bar: her cut edge sits exactly on the bar's top edge, below the dot
            val nb = nana(c)
            if (nb != null) {
                val ah = minOf(barTop - (pad + 22 * d), 110 * d, wf * 0.4f * nb.height / nb.width)
                if (ah >= 40 * d) {
                    val dw = nb.width * ah / nb.height
                    val dst = RectF(wf - pad - dw - 6 * d, barTop - ah, wf - pad - 6 * d, barTop)
                    // draw on a layer and fade the right edge, so the cropped sleeve melts into the glass
                    val lyr = cv.saveLayer(dst.left, dst.top, dst.right, dst.bottom, null)
                    cv.drawBitmap(nb, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    val mask = Paint()
                    mask.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                    mask.shader = LinearGradient(dst.right - dw * 0.3f, 0f, dst.right, 0f, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                    cv.drawRect(dst, mask)
                    cv.restoreToCount(lyr)
                }
            }
            cv.restore()
            // one glass bar for the three buttons
            val bar = RectF(pad, barTop, wf - pad, hf - pad)
            p.style = Paint.Style.FILL
            p.color = if (light) 0x10000000 else 0x18000000
            cv.drawRoundRect(bar, barH / 2f, barH / 2f, p)
            p.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, 0x30FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
            cv.drawRoundRect(bar, barH / 2f, barH / 2f, p)
            p.shader = null
            val cw = (wf - 2 * pad) / 3f
            p.color = if (light) 0x22000000 else 0x26FFFFFF
            for (i in 1..2) cv.drawRect(pad + i * cw - d / 2f, barTop + 11 * d, pad + i * cw + d / 2f, hf - pad - 11 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * d
            p.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, 0x70FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
            cv.drawRoundRect(bar, barH / 2f, barH / 2f, p)
            p.shader = null
            // Bloub's colour as a small status dot
            val dot = try { Color.parseColor(c.getSharedPreferences("bloub", Context.MODE_PRIVATE).getString("color", "#3b93f0")) } catch (e: Exception) { 0xFF3B93F0.toInt() }
            p.style = Paint.Style.FILL
            p.color = dot
            cv.drawCircle(wf - pad - 6 * d, pad + 8 * d, 5 * d, p)
            // outer glass rim
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.shader = LinearGradient(0f, 0f, wf, hf, 0x90FFFFFF.toInt(), 0x18FFFFFF, Shader.TileMode.CLAMP)
            cv.drawPath(path, p)
            p.shader = null
            return bmp
        }

        private fun draw(c: Context, mgr: AppWidgetManager, ids: IntArray) {
            Theme.load(c)
            val shape = prefs(c).getString("shape", "round") ?: "round"
            val style = prefs(c).getString("style", "dark") ?: "dark"
            val light = style == "light"
            val txt = when { style == "accent" -> Theme.onAc; light -> 0xFF15171B.toInt(); else -> 0xFFF2F3F5.toInt() }
            val muted = (txt and 0x00FFFFFF) or 0xB3000000.toInt()
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val overlay = Settings.canDrawOverlays(c)
            val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), flags)
            fun svc(code: Int, act: String): PendingIntent =
                if (overlay) PendingIntent.getForegroundService(c, code, Intent(c, CompanionService::class.java).setAction(act), flags) else open
            val showMail = prefs(c).getBoolean("mail", true) && Mail.ready(c)
            val unread = Mail.unreadCached(c)
            var l1 = greeting(c)
            if (showMail && unread >= 0) l1 += "  ·  " + unread + " unread"
            val l2 = nextReminder(c)
            for (id in ids) {
                val o = mgr.getAppWidgetOptions(id)
                val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).let { if (it <= 0) 180 else it }
                val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).let { if (it <= 0) 110 else it }
                val v = RemoteViews(c.packageName, R.layout.widget_nana)
                v.setImageViewBitmap(R.id.w_bg, render(c, wDp, hDp, shape, style))
                v.setTextViewText(R.id.w_title, "Nana")
                v.setTextViewText(R.id.w_l1, l1)
                v.setTextViewText(R.id.w_l2, l2)
                v.setTextColor(R.id.w_title, txt)
                v.setTextColor(R.id.w_l1, muted)
                v.setTextColor(R.id.w_l2, muted)
                v.setViewVisibility(R.id.w_l2, if (hDp < 100 || l2.isEmpty()) View.GONE else View.VISIBLE)
                for (b in intArrayOf(R.id.w_b1, R.id.w_b2, R.id.w_b3)) v.setInt(b, "setColorFilter", txt)
                v.setOnClickPendingIntent(R.id.w_b1, svc(1, CompanionService.ACT_SUMMON))
                v.setOnClickPendingIntent(R.id.w_b2, svc(2, CompanionService.ACT_BROWSER))
                v.setOnClickPendingIntent(R.id.w_b3, open)
                v.setOnClickPendingIntent(R.id.w_info, PendingIntent.getBroadcast(c, 4, Intent(c, NanaWidget::class.java).setAction(ACT_REFRESH), flags))
                try { mgr.updateAppWidget(id, v) } catch (e: Exception) {}
            }
        }
    }
}
