package com.sahil.chibi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.hypot
import kotlin.random.Random

class PanelLayout(ctx: Context) : LinearLayout(ctx) {
    var onBack: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

class CompanionService : Service() {

    companion object {
        const val ACT_START = "chibi.start"
        const val ACT_SUMMON = "chibi.summon"
        const val ACT_TOGGLE = "chibi.toggle"
        const val ACT_STOP = "chibi.stop"
        const val ACT_RELOAD = "chibi.reload"
        private const val CH = "chibi_channel"
        private const val NID = 1
        @Volatile var running = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, CompanionService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: AppConfig

    private var chibi: ChibiView? = null
    private var attached = false
    private lateinit var lp: WindowManager.LayoutParams

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleAttached = false

    private var panel: PanelLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var scroll: ScrollView? = null
    private var listCol: LinearLayout? = null
    private var input: EditText? = null
    private var micBtn: TextView? = null
    private var typing: TextView? = null
    private var panelOpen = false
    private var panelAttached = false
    private var preY = 0

    private var hidden = false
    private var dragging = false
    private var moved = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val history = ArrayList<Msg>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private var lastStep = 0L
    private var nextDecision = 0L
    private var nextIdleChat = 0L
    private var nextAiChat = 0L
    private var moodResetAt = 0L
    private var talkUntil = 0L
    private var lastInteract = 0L
    private var dir = 1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun overlayLp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cfg = ConfigStore.load(this)
        createChannel()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) { ttsReady = true; applyTts() }
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACT_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACT_RELOAD -> { rebuildChibi() }
            ACT_TOGGLE -> {
                ensureChibi()
                if (hidden) showChibi() else hideChibi()
            }
            ACT_SUMMON -> {
                ensureChibi()
                if (hidden) showChibi()
                if (!panelOpen) openPanel()
            }
            else -> { ensureChibi(); if (hidden) showChibi() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopListening()
        try { tts?.shutdown() } catch (ex: Exception) {}
        safeRemove(panel); safeRemove(bubble); safeRemove(chibi)
        super.onDestroy()
    }

    private fun safeRemove(v: View?) {
        if (v == null) return
        try { wm.removeView(v) } catch (ex: Exception) {}
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Hina companion", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun actionPi(code: Int, action: String): PendingIntent =
        PendingIntent.getForegroundService(
            this, code, Intent(this, CompanionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun act(label: String, pi: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), label, pi).build()
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("${cfg.persona.name} is on your phone")
            .setContentText(if (hidden) "Hidden - tap Show to bring her back" else "Tap her to chat")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(act("Call", actionPi(1, ACT_SUMMON)))
            .addAction(act(if (hidden) "Show" else "Hide", actionPi(2, ACT_TOGGLE)))
            .addAction(act("Stop", actionPi(3, ACT_STOP)))
            .build()
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NID, n, type)
        } else {
            startForeground(NID, n)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NID, buildNotification())
    }

    // ------------------------------------------------------------ chibi window

    private fun ensureChibi() {
        if (chibi != null) return
        cfg = ConfigStore.load(this)
        val c = ChibiView(this)
        c.pixel = cfg.persona.style != "smooth"
        val w = dp((cfg.persona.size * (if (ChibiView.has3d(this)) 1.7f else 0.8f)).toInt())
        val h = dp(cfg.persona.size)
        lp = overlayLp(
            w, h,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        val (sw, sh) = screen()
        val st = getSharedPreferences("chibi_state", MODE_PRIVATE)
        lp.x = st.getInt("x", sw - w - dp(16))
        lp.y = st.getInt("y", sh - h - dp(120))
        clampPos()
        c.setOnTouchListener { _, e -> onChibiTouch(e) }
        chibi = c
        attachChibi()
        val now = SystemClock.uptimeMillis()
        lastStep = now
        lastInteract = now
        nextDecision = now + 2500
        nextIdleChat = now + 40_000
        nextAiChat = now + cfg.persona.aiChatterMin * 60_000L
        main.removeCallbacks(tick)
        main.post(tick)
        main.postDelayed({ say("Hi ${cfg.persona.userName}! Tap me anytime~", "happy") }, 700)
        refreshNotification()
    }

    private fun attachChibi() {
        val c = chibi ?: return
        if (attached) return
        try { wm.addView(c, lp); attached = true } catch (ex: Exception) {}
    }

    private fun detachChibi() {
        val c = chibi ?: return
        if (!attached) return
        try { wm.removeView(c) } catch (ex: Exception) {}
        attached = false
    }

    private fun rebuildChibi() {
        closePanel()
        hideBubble()
        detachChibi()
        chibi = null
        hidden = false
        panel = null
        ensureChibi()
    }

    private fun hideChibi() {
        hidden = true
        closePanel(); hideBubble(); detachChibi()
        refreshNotification()
    }

    private fun showChibi() {
        hidden = false
        attachChibi()
        refreshNotification()
    }

    private fun clampPos() {
        val (sw, sh) = screen()
        lp.x = lp.x.coerceIn(-dp(10), (sw - lp.width + dp(10)).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (sh - lp.height).coerceAtLeast(0))
    }

    private fun updateChibiPos() {
        val c = chibi ?: return
        if (!attached) return
        try { wm.updateViewLayout(c, lp) } catch (ex: Exception) {}
        positionBubble()
    }

    private fun onChibiTouch(e: MotionEvent): Boolean {
        val c = chibi ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                moved = false; longFired = false
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                wake()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > ViewConfiguration.get(this).scaledTouchSlop) {
                    moved = true; dragging = true
                    main.removeCallbacks(longPress)
                    c.action = "drag"
                }
                if (moved) {
                    lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                    clampPos(); updateChibiPos()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (moved) {
                    dragging = false
                    c.action = "idle"
                    nextDecision = SystemClock.uptimeMillis() + 2500
                    getSharedPreferences("chibi_state", MODE_PRIVATE).edit()
                        .putInt("x", lp.x).putInt("y", if (panelOpen) preY else lp.y).apply()
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                    if (panelOpen) closePanel() else openPanel()
                }
            }
        }
        return true
    }

    private val longPress = Runnable {
        if (!moved) {
            longFired = true
            val (m, t) = Persona.pokeLine()
            say(t, m)
        }
    }

    private fun wake() {
        lastInteract = SystemClock.uptimeMillis()
        val c = chibi ?: return
        if (c.action == "sleep") c.action = "idle"
    }

    // ------------------------------------------------------------ behaviour loop

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (ex: Exception) {}
            main.postDelayed(this, 33)
        }
    }

    private fun step() {
        val c = chibi ?: return
        if (hidden || !attached) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastStep).coerceIn(0, 100) / 1000f
        lastStep = now

        if (talkUntil in 1..now) { c.talking = false; talkUntil = 0 }
        if (moodResetAt in 1..now) { c.mood = "neutral"; moodResetAt = 0 }
        if (dragging || panelOpen) return

        if (c.action == "walk") {
            val (sw, _) = screen()
            lp.x += (dir * dp(46) * dt).toInt()
            val maxX = sw - lp.width + dp(10)
            if (lp.x <= -dp(10)) { lp.x = -dp(10); dir = 1; c.facingRight = true }
            if (lp.x >= maxX) { lp.x = maxX; dir = -1; c.facingRight = false }
            updateChibiPos()
        }
        if (now >= nextDecision) decide(now, c)

        if (cfg.persona.idleChatter && now >= nextIdleChat && !panelOpen && c.action != "sleep") {
            val (m, t) = Persona.idleLine()
            say(t, m)
            nextIdleChat = now + Random.nextLong(70_000, 160_000)
        }
        if (cfg.persona.aiChatterMin > 0 && now >= nextAiChat && !panelOpen) {
            nextAiChat = now + cfg.persona.aiChatterMin * 60_000L
            aiChatter()
        }
    }

    private fun decide(now: Long, c: ChibiView) {
        c.pixel = cfg.persona.style != "smooth"
        val r = Random.nextFloat()
        val (sw, _) = screen()
        var dur = Random.nextLong(2500, 5500)
        when {
            !cfg.persona.walking || r < 0.2f -> c.action = "idle"
            r < 0.62f -> {
                dir = if (lp.x < sw / 4) 1 else if (lp.x > sw * 0.6f) -1 else if (Random.nextBoolean()) 1 else -1
                c.facingRight = dir > 0
                c.action = "walk"
                dur = Random.nextLong(2500, 6500)
            }
            r < 0.80f -> { c.action = "sit"; dur = Random.nextLong(4000, 9000) }
            r < 0.88f -> { c.action = "wave"; dur = 2200 }
            else -> {
                if (now - lastInteract > 90_000) { c.action = "sleep"; dur = Random.nextLong(12_000, 25_000) }
                else c.action = "idle"
            }
        }
        nextDecision = now + dur
    }

    // ------------------------------------------------------------ speech bubble

    private fun say(text: String, mood: String, spoken: Boolean = false) {
        val c = chibi ?: return
        val ms = (1800 + text.length * 55L).coerceAtMost(9000)
        val now = SystemClock.uptimeMillis()
        c.mood = if (mood in Persona.MOODS) mood else "neutral"
        c.talking = true
        talkUntil = now + (ms * 0.8).toLong()
        moodResetAt = now + ms + 3000
        if (spoken) speak(text)
        if (!panelOpen && attached) showBubble(text, ms)
    }

    private fun showBubble(text: String, ms: Long) {
        var b = bubble
        if (b == null) {
            b = TextView(this).apply {
                textSize = 14f
                setTextColor(0xFF241B3A.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = dp(230)
                background = GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat()
                    setStroke(dp(2), 0xFFFF8FB8.toInt())
                }
            }
            bubble = b
            bubbleLp = overlayLp(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        b.text = text
        positionBubble()
        if (!bubbleAttached) {
            try { wm.addView(b, bubbleLp); bubbleAttached = true } catch (ex: Exception) {}
        }
        main.removeCallbacks(hideBubbleRun)
        main.postDelayed(hideBubbleRun, ms)
    }

    private val hideBubbleRun = Runnable { hideBubble() }

    private fun hideBubble() {
        main.removeCallbacks(hideBubbleRun)
        val b = bubble ?: return
        if (bubbleAttached) { try { wm.removeView(b) } catch (ex: Exception) {}; bubbleAttached = false }
    }

    private fun positionBubble() {
        val b = bubble ?: return
        val l = bubbleLp ?: return
        if (!bubbleAttached && b.text.isNullOrEmpty()) return
        b.measure(
            View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val (sw, _) = screen()
        val w = b.measuredWidth
        val h = b.measuredHeight
        l.x = (lp.x + lp.width / 2 - w / 2).coerceIn(dp(4), (sw - w - dp(4)).coerceAtLeast(dp(4)))
        l.y = if (lp.y - h + dp(10) >= 0) lp.y - h + dp(10) else lp.y + lp.height - dp(10)
        if (bubbleAttached) { try { wm.updateViewLayout(b, l) } catch (ex: Exception) {} }
    }

    // ------------------------------------------------------------ chat panel

    private fun tvBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        val root = PanelLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.background = GradientDrawable().apply { setColor(0xF2211A36.toInt()); cornerRadius = dp(22).toFloat() }
        root.onBack = { closePanel() }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = cfg.persona.name; textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(tvBtn("⚙") {
            closePanel()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(tvBtn("✕") { closePanel() })
        root.addView(header)

        val sc = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(col)
        root.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply {
            hint = "Say something..."
            setHintTextColor(0x88FFFFFF.toInt()); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(20).toFloat() }
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { doSend(false); true } else false
            }
        }
        val mic = tvBtn("🎙") { if (recognizer == null) startListening() else stopListening() }
        val send = tvBtn("➤") { doSend(false) }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(mic); row.addView(send)
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        panel = root; scroll = sc; listCol = col; input = et; micBtn = mic
        val (sw, sh) = screen()
        panelLp = WindowManager.LayoutParams(
            sw - dp(16), (sh * 0.46f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (history.isEmpty()) addMsg("Hi ${cfg.persona.userName}! What's up?", false, null)
    }

    private fun openPanel() {
        if (panelOpen) return
        cfg = ConfigStore.load(this)
        if (panel == null) buildPanel()
        hideBubble()
        val (_, sh) = screen()
        val ph = panelLp!!.height
        preY = lp.y
        try { wm.addView(panel, panelLp); panelAttached = true } catch (ex: Exception) {}
        panelOpen = true
        // put her on the panel's top edge, drawn above it
        lp.y = (sh - ph - dp(8) - lp.height + dp(26)).coerceAtLeast(0)
        detachChibi(); attachChibi()
        chibi?.action = "idle"
        wake()
        say("Yes?", "happy")
    }

    private fun closePanel() {
        if (!panelOpen) return
        stopListening()
        panelOpen = false
        val pv = panel
        if (pv != null && panelAttached) { try { wm.removeView(pv) } catch (ex: Exception) {}; panelAttached = false }
        lp.y = preY
        updateChibiPos()
        nextDecision = SystemClock.uptimeMillis() + 2000
    }

    private fun addMsg(msg: String, user: Boolean, meta: String?) {
        val col = listCol ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            text = msg; textSize = 14.5f
            setTextColor(if (user) 0xFFFFFFFF.toInt() else 0xFF241B3A.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (screen().first * 0.72f).toInt()
            background = GradientDrawable().apply {
                setColor(if (user) 0xFF7E6BFF.toInt() else 0xFFFFE6F0.toInt()); cornerRadius = dp(16).toFloat()
            }
        }
        box.addView(t)
        if (meta != null) box.addView(TextView(this).apply {
            text = meta; textSize = 10f; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(6), dp(2), dp(6), 0)
        })
        col.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) })
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun doSend(voice: Boolean) {
        val et = input ?: return
        val text = et.text.toString().trim()
        if (text.isEmpty()) return
        et.setText("")
        sendUserText(text, voice)
    }

    private fun sendUserText(text: String, voice: Boolean) {
        wake()
        addMsg(text, true, null)
        history.add(Msg("user", text))
        while (history.size > 60) history.removeAt(0)
        val t = TextView(this).apply {
            this.text = "..."; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        typing = t
        listCol?.addView(t)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        chibi?.mood = "neutral"
        val conf = ConfigStore.load(this)
        cfg = conf
        val snapshot = history.takeLast(conf.persona.history).toList()
        Thread {
            try {
                val res = Router.chat(conf, Persona.system(conf), snapshot, voice)
                main.post { onReply(res, voice) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun parseMood(raw: String): Pair<String, String> {
        val m = Regex("^\\s*\\[(\\w+)]\\s*").find(raw)
        return if (m != null) m.groupValues[1].lowercase() to raw.substring(m.range.last + 1).trim()
        else "neutral" to raw.trim()
    }

    private fun onReply(res: Router.Result, voice: Boolean) {
        typing?.let { listCol?.removeView(it) }; typing = null
        val (mood, clean) = parseMood(res.text)
        history.add(Msg("assistant", clean))
        addMsg(clean, false, "via ${res.provider.name} · ${res.ms}ms")
        val mode = cfg.persona.ttsMode
        say(clean, mood, spoken = mode == "always" || (mode == "voice" && voice))
    }

    private fun onError(msg: String) {
        typing?.let { listCol?.removeView(it) }; typing = null
        addMsg("⚠ $msg", false, null)
        say("Something went wrong...", "sad")
    }

    private fun aiChatter() {
        val conf = cfg
        Thread {
            try {
                val ask = Msg("user", "(Say one tiny spontaneous cute remark to ${conf.persona.userName}, under 12 words. Don't ask for a reply.)")
                val res = Router.chat(conf, Persona.system(conf), listOf(ask), false)
                main.post { val (m, t) = parseMood(res.text); say(t, m) }
            } catch (ex: Exception) {}
        }.start()
    }

    // ------------------------------------------------------------ voice

    private fun applyTts() {
        val t = tts ?: return
        try {
            t.language = Locale.forLanguageTag(cfg.persona.voiceLang)
            t.setPitch(cfg.persona.pitch.toFloat())
            t.setSpeechRate(1.08f)
        } catch (ex: Exception) {}
    }

    private fun speak(text: String) {
        if (Voice.engine(this) != "system") {
            try { tts?.stop() } catch (ex: Exception) {}
            Voice.speak(this, text) { err -> if (err != null) main.post { speakSystem(text) } }
            return
        }
        speakSystem(text)
    }

    private fun speakSystem(text: String) {
        if (!ttsReady) return
        applyTts()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chibi")
    }

    private fun startListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addMsg("I need mic permission. Open the Hina app and tap 'Grant mic'.", false, null); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMsg("No speech recognizer found on this phone (needs the Google app).", false, null); return
        }
        stopListening()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { micBtn?.text = "🔴"; chibi?.mood = "excited" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { stopListening() }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stopListening()
                if (!text.isNullOrBlank()) sendUserText(text, true)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) input?.setText(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = sr
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, cfg.persona.voiceLang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        sr.startListening(i)
    }

    private fun stopListening() {
        val sr = recognizer ?: run { micBtn?.text = "🎙"; return }
        recognizer = null
        try { sr.stopListening(); sr.destroy() } catch (ex: Exception) {}
        micBtn?.text = "🎙"
    }
}
