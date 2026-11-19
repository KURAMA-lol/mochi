package com.sahil.chibi

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * tiny-voca (n4ze3m/tiny-voca, Apache-2.0): a 4.5M-parameter English voice, 24 kHz, two voices (F2 female, M2 male).
 * It runs the same way as the project's web demo: a hidden page with the dizione phonemizer in Pyodide and the model in
 * ONNX Runtime Web (WebAssembly). This does not touch the onnxruntime copy that sherpa-onnx uses, so Piper, Pocket and
 * the wake word are not affected. The model files are downloaded once to the phone. The Pyodide and ONNX Runtime Web
 * files are fetched from jsDelivr on the first start and then kept on the phone too.
 */
object TinyVoca {
    private const val REV = "1e73262226dbf4ef02e9df277e81aaa1db5ce16c"
    const val HF = "https://huggingface.co/n4ze3m/tiny-voca/resolve/" + REV + "/"
    private const val HOST = "tinyvoca.invalid"
    private const val RATE = 24000

    /** remote path to local path inside the model folder */
    val FILES = listOf(
        "onnx/model.onnx" to "model.onnx",
        "config.json" to "config.json",
        "LICENSE" to "LICENSE",
        "phonemizer/lexicon.tsv" to "phonemizer/lexicon.tsv",
        "phonemizer/align.json" to "phonemizer/align.json",
        "phonemizer/g2p.json" to "phonemizer/g2p.json",
        "phonemizer/g2p.npz" to "phonemizer/g2p.npz",
        "phonemizer/homographs.json" to "phonemizer/homographs.json",
        "phonemizer/LICENSE.cmudict" to "phonemizer/LICENSE.cmudict",
        "phonemizer/LICENSE.homograph-data" to "phonemizer/LICENSE.homograph-data",
    )
    private val NEED = listOf("model.onnx", "config.json", "phonemizer/lexicon.tsv", "phonemizer/align.json", "phonemizer/g2p.json", "phonemizer/g2p.npz", "phonemizer/homographs.json")

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    @Volatile private var readyFut: CompletableFuture<Boolean>? = null
    @Volatile private var phase = ""
    @Volatile private var lastErr = ""
    @Volatile private var lastConsole = ""
    private val waiters = ConcurrentHashMap<Int, CompletableFuture<String>>()
    @Volatile private var seq = 0

    fun dir(ctx: Context): File = File(File(ctx.filesDir, "speech"), "tinyvoca")
    private fun cdnDir(ctx: Context): File = File(File(ctx.filesDir, "speech"), "tinyvoca_web").apply { mkdirs() }

    fun ready(ctx: Context): Boolean = NEED.all { File(dir(ctx), it).let { f -> f.exists() && f.length() > 0 } } && File(dir(ctx), "model.onnx").length() > 15_000_000

    /** One line for Settings: what the hidden page is doing. */
    fun stateMsg(): String {
        val f = readyFut ?: return ""
        return when {
            f.isCompletedExceptionally -> "Could not start: " + lastErr.take(160)
            f.isDone -> "Engine ready."
            else -> "Starting: " + phase.ifEmpty { "..." }
        }
    }

    /** Frees the hidden page and any waiting calls. */
    fun release() {
        val w = web
        web = null
        readyFut = null
        for (x in waiters.values) x.completeExceptionally(RuntimeException("tiny-voca was stopped"))
        waiters.clear()
        if (w != null) main.post { try { w.destroy() } catch (e: Throwable) {} }
    }

    fun deleteWebCache(ctx: Context) { File(File(ctx.filesDir, "speech"), "tinyvoca_web").deleteRecursively() }

    private class Bridge {
        @JavascriptInterface fun status(t: String) { phase = t }
        @JavascriptInterface fun ready() { readyFut?.complete(true) }
        @JavascriptInterface fun initFail(m: String) {
            lastErr = m + (if (lastConsole.isNotEmpty()) " [" + lastConsole + "]" else "")
            readyFut?.completeExceptionally(RuntimeException("tiny-voca web engine: " + lastErr.take(240)))
        }
        @JavascriptInterface fun done(id: Int, b64: String) { waiters[id]?.complete(b64) }
        @JavascriptInterface fun fail(id: Int, m: String) { waiters[id]?.completeExceptionally(RuntimeException("tiny-voca: " + m.take(240))) }
    }

    private fun mime(path: String): String = when {
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".html") -> "text/html"
        path.endsWith(".zip") || path.endsWith(".whl") -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun reply(path: String, f: File?, bytes: ByteArray? = null): WebResourceResponse {
        val h = HashMap<String, String>()
        h["Access-Control-Allow-Origin"] = "*"
        h["Cross-Origin-Resource-Policy"] = "cross-origin"
        val s = if (f != null) FileInputStream(f) else ByteArrayInputStream(bytes ?: ByteArray(0))
        return WebResourceResponse(mime(path), null, 200, "OK", h, s)
    }

    private fun missing(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap(), ByteArrayInputStream(ByteArray(0)))

    /** Files of the page itself come from the phone. jsDelivr files are fetched once, then served from the phone. */
    private fun serve(app: Context, u: Uri): WebResourceResponse? {
        val host = u.host ?: return null
        val path = u.path ?: "/"
        if (host == HOST) {
            try {
                if (path == "/index.html" || path == "/") return reply("x.html", null, app.assets.open("tinyvoca/index.html").use { it.readBytes() })
                if (path == "/assets/dizione.zip") return reply(path, null, app.assets.open("tinyvoca/dizione.zip").use { it.readBytes() })
                if (path.startsWith("/assets/")) {
                    val root = dir(app).canonicalFile
                    val f = File(root, path.removePrefix("/assets/")).canonicalFile
                    if (f.path.startsWith(root.path + File.separator) && f.isFile) return reply(path, f)
                }
            } catch (e: Throwable) {}
            return missing()
        }
        if (host == "cdn.jsdelivr.net" && (path.startsWith("/pyodide/v0.26.4/") || path.startsWith("/npm/onnxruntime-web@1.20.1/"))) {
            val key = path.trimStart('/').replace(Regex("[^A-Za-z0-9._@-]"), "_")
            val f = File(cdnDir(app), key)
            if (!f.exists() || f.length() == 0L) {
                val tmp = File(f.path + ".part")
                try {
                    val c = URL("https://cdn.jsdelivr.net" + path).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 60000
                    try {
                        if (c.responseCode !in 200..299) return missing()
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    } finally { c.disconnect() }
                    tmp.renameTo(f)
                } catch (e: Throwable) { tmp.delete(); return missing() }
            }
            return reply(path, f)
        }
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", HashMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun startWeb(app: Context) {
        val f = CompletableFuture<Boolean>()
        readyFut = f
        phase = "opening"; lastErr = ""
        main.post {
            try {
                val w = WebView(app)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.allowFileAccess = false
                w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                w.addJavascriptInterface(Bridge(), "TV")
                w.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean { lastConsole = m.message().take(200); return true }
                }
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = serve(app, request.url)
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        lastErr = "the web view process stopped"
                        readyFut?.completeExceptionally(RuntimeException("tiny-voca web engine stopped"))
                        for (x in waiters.values) x.completeExceptionally(RuntimeException("tiny-voca web engine stopped"))
                        web = null
                        try { view.destroy() } catch (e: Throwable) {}
                        return true
                    }
                }
                web = w
                w.loadUrl("https://$HOST/index.html")
            } catch (e: Throwable) {
                lastErr = e.message ?: e.toString()
                f.completeExceptionally(e)
            }
        }
    }

    /** Starts the hidden page early (after the download) so the first sentence is not slow. */
    fun warm(ctx: Context) {
        val app = ctx.applicationContext
        if (ready(app) && (readyFut == null || readyFut!!.isCompletedExceptionally)) startWeb(app)
    }

    private fun silence(): ByteArray = Speech.wav(FloatArray(RATE / 8), RATE)

    /** Makes one WAV for [text]. Settings (Voice tab): voice F2/M2, speed, pitch in semitones, pitch range. */
    @Synchronized
    fun synth(ctx: Context, text: String): ByteArray {
        val app = ctx.applicationContext
        if (!ready(app)) throw RuntimeException("tiny-voca is not downloaded yet (Settings > Voice)")
        var f = readyFut
        if (f == null || f.isCompletedExceptionally) { startWeb(app); f = readyFut!! }
        try { f.get(240, TimeUnit.SECONDS) }
        catch (e: TimeoutException) { throw RuntimeException("tiny-voca is still starting (first start loads about 25 MB from the internet). Try again in a minute.") }
        catch (e: ExecutionException) { throw RuntimeException(e.cause?.message ?: "tiny-voca could not start") }
        val c = Voice.cfg(app).optJSONObject("tiny") ?: JSONObject()
        val voice = if (c.optString("voice", "F2") == "M2") 1 else 0
        val speed = c.optDouble("speed", 1.0).coerceIn(0.5, 2.0)
        // 0.058 added to log F0 is about one semitone
        val shift = c.optDouble("pitch", 0.0).coerceIn(-8.0, 8.0) * 0.058
        val range = c.optDouble("range", 1.0).coerceIn(0.0, 2.0)
        val id = ++seq
        val fut = CompletableFuture<String>()
        waiters[id] = fut
        val js = "tvSpeak($id,${JSONObject.quote(text)},$voice,$speed,$shift,$range)"
        main.post { web?.evaluateJavascript(js, null) ?: fut.completeExceptionally(RuntimeException("tiny-voca web engine is not running")) }
        val b64 = try { fut.get(120, TimeUnit.SECONDS) }
            catch (e: TimeoutException) { throw RuntimeException("tiny-voca took too long") }
            catch (e: ExecutionException) { throw RuntimeException(e.cause?.message ?: "tiny-voca failed") }
            finally { waiters.remove(id) }
        // English only: a piece with nothing to read (other script, signs) stays silent, like the web demo
        if (b64.isEmpty()) return silence()
        return Base64.decode(b64, Base64.DEFAULT)
    }
}
