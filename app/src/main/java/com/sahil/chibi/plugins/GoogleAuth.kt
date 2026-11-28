package com.sahil.chibi.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google OAuth 2.0 for Gmail and Calendar. No password is ever asked.
 * Flow: open Google's consent page in the browser, Google redirects to 127.0.0.1 on this phone, we read the code,
 * swap it for an access token and a refresh token (PKCE), and store them encrypted.
 * Create the client in Google Cloud Console as type "Desktop app" (see README).
 */
object GoogleAuth {
    fun clientId(c: Context) = SecureStore.get(c, "google_client_id")
    private fun clientSecret(c: Context) = SecureStore.get(c, "google_client_secret")
    fun hasClient(c: Context) = clientId(c).isNotEmpty() && clientSecret(c).isNotEmpty()

    fun saveClient(c: Context, id: String, secret: String) {
        if (id.isNotBlank()) SecureStore.put(c, "google_client_id", id.trim())
        if (secret.isNotBlank()) SecureStore.put(c, "google_client_secret", secret.trim())
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun rand(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Opens Google sign-in for the scopes, waits for the redirect, stores tokens under tokenKey. */
    fun start(ctx: Context, scope: String, tokenKey: String, done: (Boolean, String) -> Unit) {
        if (!hasClient(ctx)) { done(false, "Enter the Google client ID and secret first."); return }
        val server = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { done(false, "Could not open the local sign-in port."); return }
        val redirect = "http://127.0.0.1:" + server.localPort
        val verifier = b64(rand(48))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(rand(16))
        val url = "https://accounts.google.com/o/oauth2/v2/auth?" + Http.form(
            "client_id" to clientId(ctx), "redirect_uri" to redirect, "response_type" to "code", "scope" to scope,
            "access_type" to "offline", "prompt" to "consent", "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256"
        )
        Thread {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                server.soTimeout = 180000
                val s = server.accept()
                val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                val q = line.substringAfter("GET ", "").substringBefore(" HTTP").substringAfter('?', "")
                val args = q.split('&').mapNotNull { p -> val i = p.indexOf('='); if (i > 0) p.substring(0, i) to Uri.decode(p.substring(i + 1)) else null }.toMap()
                val ok = args["state"] == state && !args["code"].isNullOrEmpty()
                val page = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body style='font-family:sans-serif;text-align:center;padding:48px'><h3>" +
                    (if (ok) "Connected. You can go back to Nana." else "Sign-in did not finish. Go back to Nana and try again.") + "</h3></body></html>"
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" + page).toByteArray())
                s.close()
                if (!ok) { done(false, if (args["error"] == "access_denied") "You cancelled the Google sign-in." else "Google sign-in failed."); return@Thread }
                val r = Http.call("POST", "https://oauth2.googleapis.com/token", body = Http.form(
                    "code" to args["code"]!!, "client_id" to clientId(ctx), "client_secret" to clientSecret(ctx),
                    "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier
                ), contentType = "application/x-www-form-urlencoded")
                val j = r.json()
                if (!r.ok || j.optString("access_token").isEmpty()) { done(false, "Google refused the code: " + Http.why(r)); return@Thread }
                val rt = j.optString("refresh_token")
                if (rt.isEmpty()) { done(false, "Google gave no refresh token. Remove Nana in your Google account > Security > Third-party access, then connect again."); return@Thread }
                save(ctx, tokenKey, j.optString("access_token"), rt, j.optLong("expires_in", 3600))
                done(true, "Connected.")
            } catch (e: java.net.SocketTimeoutException) {
                done(false, "Sign-in timed out. Tap Connect and finish it within 3 minutes.")
            } catch (e: Exception) {
                done(false, "Sign-in problem: " + (e.message ?: "error").take(100))
            } finally { try { server.close() } catch (e: Exception) {} }
        }.start()
    }

    private fun save(c: Context, key: String, access: String, refresh: String, expiresIn: Long) {
        SecureStore.put(c, key, JSONObject().put("a", access).put("r", refresh).put("e", System.currentTimeMillis() + expiresIn * 1000).toString())
    }

    fun connected(c: Context, key: String) = SecureStore.get(c, key).isNotEmpty()
    fun disconnect(c: Context, key: String) { SecureStore.remove(c, key) }

    /** A valid access token, refreshed when needed. Null when not connected or the refresh failed (message in lastError). */
    @Volatile var lastError = ""
    fun token(c: Context, key: String): String? {
        val raw = SecureStore.get(c, key)
        if (raw.isEmpty()) return null
        val j = try { JSONObject(raw) } catch (e: Exception) { return null }
        if (j.optLong("e") - 60000 > System.currentTimeMillis()) return j.optString("a")
        return try {
            val r = Http.call("POST", "https://oauth2.googleapis.com/token", body = Http.form(
                "client_id" to clientId(c), "client_secret" to clientSecret(c), "refresh_token" to j.optString("r"), "grant_type" to "refresh_token"
            ), contentType = "application/x-www-form-urlencoded")
            val n = r.json()
            if (r.ok && n.optString("access_token").isNotEmpty()) {
                save(c, key, n.optString("access_token"), j.optString("r"), n.optLong("expires_in", 3600)); n.optString("access_token")
            } else {
                lastError = if (n.optString("error") == "invalid_grant") "Google access expired. Reconnect from Settings > Connected Apps." else Http.why(r)
                if (n.optString("error") == "invalid_grant") disconnect(c, key)
                null
            }
        } catch (e: Exception) { lastError = "No connection to Google right now."; null }
    }
}
