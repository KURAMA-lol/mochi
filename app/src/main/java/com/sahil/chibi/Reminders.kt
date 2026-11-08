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

    fun add(c: Context, text: String, at: Long): String {
        if (text.isBlank()) return "No reminder text given."
        if (at <= System.currentTimeMillis()) return "That time is already in the past."
        val a = load(c)
        val id = System.currentTimeMillis()
        a.put(JSONObject().put("id", id).put("text", text.take(200)).put("at", at))
        save(c, a)
        schedule(c, id, text, at)
        return "Reminder set for " + fmt(at) + ": " + text
    }

    /** args: text + either minutes (from now) or at "HH:mm" (24h, today or tomorrow) with optional date "yyyy-MM-dd". */
    fun fromArgs(c: Context, args: JSONObject): String {
        val text = args.optString("text")
        val mins = args.optDouble("minutes", -1.0)
        if (mins > 0) return add(c, text, System.currentTimeMillis() + (mins * 60000).toLong())
        val at = args.optString("at")
        val m = Regex("(\\d{1,2}):(\\d{2})").find(at) ?: return "Give minutes (from now) or at \"HH:mm\" in 24-hour time."
        val cal = Calendar.getInstance()
        val date = args.optString("date")
        val d = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(date)
        if (d != null) cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
        cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
        cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
        cal.set(Calendar.SECOND, 0)
        if (d == null && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return add(c, text, cal.timeInMillis)
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
            for (i in 0 until a.length()) if (a.getJSONObject(i).getLong("id") != id) out.put(a.getJSONObject(i))
            save(c, out)
        } catch (e: Exception) {}
        AuditLog.add(c, "Reminder fired: " + text)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) { Reminders.rescheduleAll(c); return }
        Reminders.fire(c, i.getLongExtra("id", 0L), i.getStringExtra("text") ?: "Reminder")
    }
}
