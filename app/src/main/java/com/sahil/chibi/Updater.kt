package com.sahil.chibi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates. The repo is private, so this needs a read-only GitHub token that you paste once in Settings > Data > Updates.
 * It compares the build id baked into this APK (assets/build.txt) with the latest release, downloads the APK and installs it as an update.
 */
object Updater {
    private const val REPO = "KURAMA-lol/mochi"
    private const val ACTION = "com.sahil.chibi.UPDATE_STATUS"
    @Volatile var onMsg: ((String) -> Unit)? = null
    @Volatile private var receiver: BroadcastReceiver? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)
    fun token(c: Context): String = (prefs(c).getString("token", "") ?: "").trim()
    fun setToken(c: Context, t: String) { prefs(c).edit().putString("token", t.trim()).apply() }
    fun dismissed(c: Context): String = prefs(c).getString("dismissed", "") ?: ""
    fun dismiss(c: Context, sha: String) { prefs(c).edit().putString("dismissed", sha).apply() }

    /** sha and build date baked into this APK by CI. */
    fun installed(c: Context): Pair<String, String> = try {
        val l = c.assets.open("build.txt").bufferedReader().use { it.readText() }.lines().map { it.trim() }.filter { it.isNotEmpty() }
        Pair(l.getOrElse(0) { "" }, l.getOrElse(1) { "" })
    } catch (e: Exception) { Pair("", "") }

    class Info(val sha: String, val date: String, val assetId: Long, val size: Long, val name: String) {
        fun json(): String = JSONObject().put("sha", sha).put("short", sha.take(7)).put("date", date).put("size", size).put("name", name).toString()
    }

    private fun api(path: String, token: String, accept: String = "application/vnd.github+json"): HttpURLConnection {
        val cn = URL("https://api.github.com/repos/$REPO$path").openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 20000
        cn.setRequestProperty("Authorization", "Bearer $token")
        cn.setRequestProperty("Accept", accept)
        cn.setRequestProperty("User-Agent", "NanaUpdater")
        cn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return cn
    }

    /** Latest release info, or null when this build is already the latest. Throws with a readable message on problems. */
    fun check(c: Context): Info? {
        val tk = token(c)
        if (tk.isEmpty()) throw RuntimeException("No GitHub token yet.")
        val cn = api("/releases/tags/latest-build", tk)
        val code = cn.responseCode
        if (code == 401 || code == 403) throw RuntimeException("GitHub refused the token (HTTP $code). It needs read access to Contents of $REPO.")
        if (code == 404) throw RuntimeException("No release found. The token may not see the private repo.")
        if (code >= 400) throw RuntimeException("GitHub answered HTTP $code.")
        val o = JSONObject(cn.inputStream.bufferedReader().use { it.readText() })
        val sha = Regex("build=([0-9a-f]{40})").find(o.optString("body"))?.groupValues?.get(1) ?: o.optString("target_commitish")
        val assets = o.optJSONArray("assets") ?: return null
        var asset: JSONObject? = null
        for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.optString("name").endsWith(".apk")) { asset = a; break } }
        if (asset == null || sha.length < 7) return null
        val mine = installed(c).first
        if (mine.isNotEmpty() && mine == sha) return null
        return Info(sha, o.optString("published_at").take(16).replace('T', ' '), asset.optLong("id"), asset.optLong("size"), o.optString("name"))
    }

    /** Downloads the APK into the cache folder. progress gets 0..100. */
    fun download(c: Context, info: Info, progress: (Int) -> Unit): File {
        val tk = token(c)
        var cn = api("/releases/assets/" + info.assetId, tk, "application/octet-stream")
        cn.instanceFollowRedirects = false
        var code = cn.responseCode
        if (code in 300..399) {
            val loc = cn.getHeaderField("Location") ?: throw RuntimeException("Download link missing.")
            cn = URL(loc).openConnection() as HttpURLConnection   // the storage host must not get the token
            cn.connectTimeout = 15000; cn.readTimeout = 30000
            code = cn.responseCode
        }
        if (code >= 400) throw RuntimeException("Download failed (HTTP $code).")
        val f = File(c.cacheDir, "update.apk")
        val total = if (info.size > 0) info.size else cn.contentLengthLong
        var done = 0L; var lastPct = -1
        cn.inputStream.use { i -> f.outputStream().use { o ->
            val buf = ByteArray(65536)
            while (true) {
                val r = i.read(buf); if (r < 0) break
                o.write(buf, 0, r); done += r
                if (total > 0) { val p = (done * 100 / total).toInt(); if (p != lastPct) { lastPct = p; progress(p) } }
            }
        } }
        if (f.length() < 1_000_000) throw RuntimeException("The downloaded file is too small, something went wrong.")
        return f
    }

    fun canInstall(c: Context): Boolean = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    /** Hands the APK to Android's installer. You confirm on the system screen; same signing key, so it installs as an update. */
    fun install(c: Context, apk: File) {
        val app = c.applicationContext
        val pi = app.packageManager.packageInstaller
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                when (st) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(confirm); onMsg?.invoke("Confirm the update on the screen that just opened.") }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { onMsg?.invoke("Updated."); unreg(ctx) }
                    else -> { onMsg?.invoke("Install did not finish: " + msg.ifEmpty { "code $st" }); unreg(ctx) }
                }
            }
        }
        unreg(app)
        receiver = r
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("nana.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), flags)
            s.commit(pend.intentSender)
        }
    }

    private fun unreg(c: Context) {
        val r = receiver ?: return
        receiver = null
        try { c.applicationContext.unregisterReceiver(r) } catch (e: Exception) {}
    }
}
