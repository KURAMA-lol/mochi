package com.sahil.chibi

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reminders that survive restarts. They fire as a notification, even when Nana is not running. */
object Reminders {
    @Volatile private var curRep = ""
    private const val CH = "nana_reminders"
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private fun load(c: Context): JSONArray = try { JSONArray(prefs(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { prefs(c).edit().putString("list", a.toString()).apply() }
    private fun fmt(at: Long) = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(at))

    private fun pending(c: Context, id: Long, text: String): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(c, (id % 1000000000L).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(c: Context, id: Long, text: String, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, id, text)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun add(c: Context, text: String, at: Long, rep: String = ""): String {
        if (text.isBlank()) return "No reminder text given."
        if (at <= System.currentTimeMillis()) return "That time is already in the past."
        val a = load(c)
        val id = System.currentTimeMillis()
        val rep2 = if (rep.isNotEmpty()) rep else curRep
        curRep = ""
        a.put(JSONObject().put("id", id).put("text", text.take(200)).put("at", at).put("rep", rep2))
        save(c, a)
        schedule(c, id, text, at)
        return "Reminder set for " + fmt(at) + (if (rep2.isNotEmpty()) " (repeats " + rep2 + ")" else "") + ": " + text
    }

    /** args: text + a time. Accepts minutes / seconds / hours (numbers or text like "1 min"), or at/time/when like "18:30", "6pm", "6:30 pm". */
    fun fromArgs(c: Context, args: JSONObject): String {
        val rp = args.optString("repeat", "").lowercase().let { if (it.startsWith("day") || it == "daily") "daily" else if (it.startsWith("week")) "weekly" else "" }
        curRep = rp
        val text = args.optString("text", args.optString("message", args.optString("label", ""))).trim()
        val now = System.currentTimeMillis()
        val rel = Regex("(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\b", RegexOption.IGNORE_CASE)
        fun relMs(v: Double, u: String): Long = when (u.lowercase().first()) { 's' -> (v * 1000).toLong(); 'h' -> (v * 3600000).toLong(); else -> (v * 60000).toLong() }
        // numeric fields
        for ((k, unit) in listOf("seconds" to "s", "minutes" to "m", "hours" to "h")) {
            if (args.has(k)) {
                val raw = args.opt(k).toString()
                val num = raw.toDoubleOrNull()
                if (num != null && num > 0) return add(c, text, now + relMs(num, unit))
                val m = rel.find(raw)
                if (m != null) return add(c, text, now + relMs(m.groupValues[1].toDouble(), m.groupValues[2]))
            }
        }
        // text fields
        for (k in listOf("at", "time", "when", "in", "after", "delay")) {
            val v = args.optString(k, "").trim()
            if (v.isEmpty()) continue
            val abs = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(v)
            if (abs != null && (v.contains(":") || abs.groupValues[3].isNotEmpty())) {
                var h = abs.groupValues[1].toInt()
                val mi = abs.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = abs.groupValues[3].lowercase()
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                if (h in 0..23 && mi in 0..59) {
                    val cal = Calendar.getInstance()
                    val d = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(args.optString("date"))
                    if (d != null) cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); cal.set(Calendar.SECOND, 0)
                    if (d == null && cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                    return add(c, text, cal.timeInMillis)
                }
            }
            val m = rel.find(v)
            if (m != null) return add(c, text, now + relMs(m.groupValues[1].toDouble(), m.groupValues[2]))
            val bare = v.toDoubleOrNull()
            if (bare != null && bare > 0 && (k == "in" || k == "after" || k == "delay")) return add(c, text, now + (bare * 60000).toLong())
        }
        return "I could not read the time. Call set_reminder again with minutes (a number, e.g. 1) or at \"HH:mm\" in 24-hour time."
    }

    fun list(c: Context): String {
        val a = load(c)
        if (a.length() == 0) return "No reminders set."
        val sb = StringBuilder()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); sb.append("- ").append(fmt(o.getLong("at"))).append(": ").append(o.getString("text")).append('\n') }
        return sb.toString()
    }

    fun json(c: Context): String = load(c).toString()

    fun cancelId(c: Context, id: Long) {
        val a = load(c)
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("id") == id) {
                try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id, o.getString("text"))) } catch (e: Exception) {}
            } else out.put(o)
        }
        save(c, out)
    }

    fun cancel(c: Context, match: String): String {
        val low = match.lowercase().trim()
        val a = load(c)
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (low.isEmpty() || o.getString("text").lowercase().contains(low)) { cancelId(c, o.getLong("id")); n++ }
        }
        return if (n == 0) "No reminder matches '" + match + "'." else "Cancelled " + n + " reminder(s)."
    }

    fun rescheduleAll(c: Context) {
        val a = load(c)
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("at") > now) schedule(c, o.getLong("id"), o.getString("text"), o.getLong("at"))
        }
    }

    fun fire(c: Context, id: Long, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Nana reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c)
        b.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Nana: reminder").setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open)
        nm.notify((id % 100000000L).toInt(), b.build())
        try {
            val a = load(c)
            val out = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getLong("id") != id) { out.put(o); continue }
                val rp = o.optString("rep", "")
                if (rp.isNotEmpty()) {
                    var next = o.getLong("at")
                    val step = if (rp == "weekly") 7L * 86400000L else 86400000L
                    while (next <= System.currentTimeMillis()) next += step
                    o.put("at", next); out.put(o)
                    schedule(c, id, o.getString("text"), next)
                }
            }
            save(c, out)
        } catch (e: Exception) {}
        AuditLog.add(c, "Reminder fired: " + text)
        if (CompanionService.running) {
            try { c.startForegroundService(Intent(c, CompanionService::class.java).setAction(CompanionService.ACT_SAY).putExtra("text", text)) } catch (e: Exception) {}
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Reminders.rescheduleAll(c); return }
        Reminders.fire(c, i.getLongExtra("id", 0L), i.getStringExtra("text") ?: "Reminder")
    }
}
