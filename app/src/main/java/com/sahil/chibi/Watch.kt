package com.sahil.chibi

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Lets Nana "watch" a video from a link, in the spirit of Agent-Reach: YouTube and Shorts give captions plus info,
 * TikTok / Instagram / Facebook / X / others give the public caption and page info. She never sees pixels or hears audio.
 */
object Watch {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val BOT_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val HONEST = "NOTE: this is captions and info only, not the picture. React like you watched it together, but never invent visuals or anything this text does not tell you.\n"
    private const val HONEST_CAPTION = "NOTE: only the public caption and info are visible, not the picture or the sound. React to what the caption says, say plainly that you only saw the caption, and do not invent what happens in the video. If the user is watching it right now, screen_read shows what is on screen.\n"

    private val URL_RE = Regex("""https?://[^\s<>"')]+""")
    private val VIDEO_HOST = Regex("""(^|\.)(youtube\.com|youtu\.be|instagram\.com|instagr\.am|tiktok\.com|facebook\.com|fb\.watch|fb\.com|x\.com|twitter\.com|vimeo\.com|dailymotion\.com|twitch\.tv|reddit\.com|v\.redd\.it|snapchat\.com)""")
    private val META_TAG = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val META_KEY = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val META_VAL = Regex("""content\s*=\s*"([^"]*)"|content\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val YT_ID = Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")

    /** The first link in the text that points at a video or reel, or null. */
    fun videoUrl(text: String): String? {
        for (m in URL_RE.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ')', ']')
            val host = try { java.net.URI(u).host?.lowercase() ?: "" } catch (e: Exception) { "" }
            if (!VIDEO_HOST.containsMatchIn(host)) continue
            val low = u.lowercase()
            val ok = host.contains("youtu") || host.contains("tiktok") || host.contains("vimeo") || host.contains("dailymotion") ||
                host.contains("fb.watch") || host.contains("v.redd.it") || low.contains("/reel") || low.contains("/shorts/") ||
                low.contains("/video") || low.contains("/watch") || low.contains("/status/") || low.contains("/clip") ||
                low.contains("/p/") || low.contains("/tv/")
            if (ok) return u
        }
        return null
    }

    fun video(rawUrl: String): String {
        val raw = rawUrl.trim()
        if (raw.isEmpty()) return "No link given."
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return try {
            when {
                host.contains("youtube.com") || host.contains("youtu.be") -> youtube(url)
                host.contains("tiktok.com") -> tiktok(url)
                else -> pageInfo(url, platform(host))
            }.take(5200)
        } catch (e: Exception) {
            "Could not watch that link (" + (e.message ?: e.toString()).take(160) + "). If it needs a login, open it in the app and use screen_read to see what is on screen."
        }
    }

    private fun platform(host: String) = when {
        host.contains("instagram") || host.contains("instagr.am") -> "Instagram"
        host.contains("facebook") || host.contains("fb.") -> "Facebook"
        host.contains("x.com") || host.contains("twitter") -> "X"
        host.contains("vimeo") -> "Vimeo"
        host.contains("reddit") || host.contains("redd.it") -> "Reddit"
        else -> "Web video"
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    private fun youtube(url: String): String {
        val id = YT_ID.find(url)?.groupValues?.get(1) ?: return pageInfo(url, "YouTube")
        val sb = StringBuilder("VIDEO (YouTube) ").append(url).append('\n')
        var tracks: JSONArray? = null
        try {
            val client = JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                .put("androidSdkVersion", 34).put("hl", "en").put("gl", "US")
            val body = JSONObject().put("videoId", id).put("context", JSONObject().put("client", client))
                .put("contentCheckOk", true).put("racyCheckOk", true)
            val res = Http.request("POST", "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                mapOf(
                    "User-Agent" to "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
                    "X-YouTube-Client-Name" to "3", "X-YouTube-Client-Version" to "20.10.38",
                    "Origin" to "https://www.youtube.com"
                ), body.toString(), 20000)
            val o = JSONObject(res)
            val ps = o.optJSONObject("playabilityStatus")
            val status = ps?.optString("status") ?: "OK"
            if (status != "OK") sb.append("Playability: ").append(status).append(' ').append(ps?.optString("reason") ?: "").append('\n')
            val vd = o.optJSONObject("videoDetails")
            if (vd != null) {
                sb.append("Title: ").append(vd.optString("title")).append('\n')
                sb.append("Channel: ").append(vd.optString("author")).append('\n')
                val len = vd.optString("lengthSeconds").toLongOrNull()
                if (len != null) sb.append("Length: ").append(clock(len * 1000)).append('\n')
                val views = vd.optString("viewCount")
                if (views.isNotEmpty()) sb.append("Views: ").append(views).append('\n')
                val d = vd.optString("shortDescription").trim()
                if (d.isNotEmpty()) sb.append("Description: ").append(d.take(600)).append('\n')
            }
            tracks = o.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        } catch (e: Exception) {
            try {
                val o = JSONObject(Http.request("GET", "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"),
                    mapOf("User-Agent" to UA), null, 15000))
                sb.append("Title: ").append(o.optString("title")).append('\n')
                sb.append("Channel: ").append(o.optString("author_name")).append('\n')
            } catch (e2: Exception) {
                sb.append("Could not read the video info (").append((e.message ?: "error").take(120)).append(")\n")
            }
        }
        sb.append(transcript(tracks) ?: "Transcript: none available (no captions, or YouTube blocked them). Only the title and description are known.\n")
        sb.append(HONEST)
        return sb.toString()
    }

    private fun transcript(tracks: JSONArray?): String? {
        if (tracks == null || tracks.length() == 0) return null
        var pick: JSONObject? = null
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            if (t.optString("languageCode").startsWith("en") && t.optString("kind") != "asr") { pick = t; break }
        }
        if (pick == null) for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            if (t.optString("languageCode").startsWith("en")) { pick = t; break }
        }
        val p = pick ?: tracks.getJSONObject(0)
        val base = p.optString("baseUrl")
        if (base.isBlank()) return null
        val raw = try {
            Http.request("GET", base.replace("&fmt=srv3", "") + "&fmt=json3", mapOf("User-Agent" to UA), null, 20000)
        } catch (e: Exception) { return null }
        if (raw.isBlank()) return null
        val events = JSONObject(raw).optJSONArray("events") ?: return null
        val sb = StringBuilder("Transcript (captions, ").append(p.optString("languageCode")).append(", [m:ss]):\n")
        var line = StringBuilder()
        var lineStart = -1L
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val segs = ev.optJSONArray("segs") ?: continue
            val t0 = ev.optLong("tStartMs")
            if (lineStart < 0) lineStart = t0
            if (t0 - lineStart > 15000) {
                if (line.isNotBlank()) sb.append('[').append(clock(lineStart)).append("] ").append(line.toString().trim()).append('\n')
                line = StringBuilder(); lineStart = t0
            }
            for (j in 0 until segs.length()) line.append(segs.getJSONObject(j).optString("utf8"))
            line.append(' ')
            if (sb.length > 4300) break
        }
        if (line.isNotBlank()) sb.append('[').append(clock(lineStart)).append("] ").append(line.toString().trim()).append('\n')
        val out = sb.toString().replace("\n ", "\n")
        if (out.length < 60) return null
        return if (out.length > 4400) out.take(4400) + "\n(transcript trimmed)\n" else out
    }

    private fun tiktok(url: String): String {
        val sb = StringBuilder("VIDEO (TikTok) ").append(url).append('\n')
        var got = false
        try {
            val o = JSONObject(Http.request("GET", "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"),
                mapOf("User-Agent" to UA), null, 15000))
            val cap = o.optString("title")
            if (cap.isNotBlank()) { sb.append("Caption: ").append(cap).append('\n'); got = true }
            val who = o.optString("author_name")
            if (who.isNotBlank()) sb.append("Creator: ").append(who).append('\n')
        } catch (e: Exception) {}
        if (!got) {
            val b = try { pageInfoBody(url) } catch (e: Exception) { "" }
            if (b.isBlank()) return "VIDEO (TikTok) $url\nNo public caption could be read. Say so honestly. If the user is watching it on screen, use screen_read.\n"
            sb.append(b)
        }
        sb.append(HONEST_CAPTION)
        return sb.toString()
    }

    private fun unesc(s: String): String {
        var t = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
        t = Regex("""&#(\d+);""").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return t
    }

    private fun metas(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in META_TAG.findAll(html)) {
            val tag = m.value
            val key = META_KEY.find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            val vm = META_VAL.find(tag) ?: continue
            val v = vm.groupValues[1].ifEmpty { vm.groupValues[2] }
            if (v.isNotBlank() && !out.containsKey(key)) out[key] = unesc(v)
        }
        return out
    }

    private fun pageInfoBody(url: String): String {
        val html = Http.request("GET", url, mapOf("User-Agent" to BOT_UA, "Accept" to "text/html,*/*"), null, 20000)
        val m = metas(html)
        val title = m["og:title"] ?: m["twitter:title"] ?: TITLE_TAG.find(html)?.groupValues?.get(1)?.let { unesc(it).trim() } ?: ""
        val desc = m["og:description"] ?: m["twitter:description"] ?: m["description"] ?: ""
        if (title.isBlank() && desc.isBlank()) return ""
        if (desc.contains("create an account", true) || desc.contains("log in to", true) || desc.contains("sign up for", true)) return ""
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append("Title: ").append(title.take(300)).append('\n')
        if (desc.isNotBlank()) sb.append("Caption/description: ").append(desc.take(900)).append('\n')
        m["og:site_name"]?.let { sb.append("Site: ").append(it).append('\n') }
        return sb.toString()
    }

    private fun pageInfo(url: String, platform: String): String {
        val body = pageInfoBody(url)
        if (body.isBlank()) return "VIDEO ($platform) $url\nNo public caption or info could be read (it probably needs a login). Say so honestly. If the user is watching it on screen, use screen_read to see what is showing.\n"
        return "VIDEO ($platform) $url\n" + body + HONEST_CAPTION
    }
}
