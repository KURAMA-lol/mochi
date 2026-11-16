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
import android.os.PowerManager
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
import android.widget.FrameLayout
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
        const val ACT_WAKE = "chibi.wake"
        const val ACT_CALL = "chibi.call"
        const val ACT_HANGUP = "chibi.hangup"
        @Volatile var wakeState = "off"
        @Volatile var wakeHeard = ""
        const val ACT_SAY = "chibi.say"
        const val ACT_TALK = "chibi.talk"
        const val ACT_SHOW = "chibi.show"
        const val ACT_ASK = "chibi.ask"
        private const val CH = "chibi_channel_v2"
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
    private var micBtn: android.widget.ImageView? = null
    private var typing: View? = null
    private var chipRow: View? = null
    private var panelOpen = false
    private var panelAttached = false
    private var preY = 0
    private var preX = 0
    private var origW = 0
    private var origH = 0

    private var hidden = false
    private var dragging = false
    private var moved = false
    private var longFired = false
    private var radial: RadialMenu? = null
    private var lastPowerSay = 0L
    private var panelV = -1
    private var lastGreet = 0L
    /** Short time-of-day hello when Nana is summoned (at most once per 30 min). Speaks nothing else, does nothing. */
    private fun greetNow() {
        val now = SystemClock.uptimeMillis()
        if (lastGreet != 0L && now - lastGreet < 30 * 60 * 1000L) return
        lastGreet = now
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when {
            h in 0..4 -> say("It's so late... don't forget to sleep!", "shy")
            h in 5..11 -> say("Good morning! Ready for today?", "happy")
            h in 12..16 -> say("Good afternoon~ need anything?", "happy")
            h in 17..20 -> say("Good evening! How was your day?", "happy")
            else -> say("Getting late. Don't stay up too long!", "neutral")
        }
    }
    private val powerRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            if (hidden || chibi == null) return
            val now = SystemClock.uptimeMillis()
            if (now - lastPowerSay < 8000) return
            lastPowerSay = now
            when (i.action) {
                Intent.ACTION_BATTERY_LOW -> say("Battery's low, plug me in soon!", "sad")
                Intent.ACTION_POWER_CONNECTED -> say("Charging~ ahh, that feels good.", "happy")
                Intent.ACTION_POWER_DISCONNECTED -> say("Unplugged. Back to work!", "neutral")
            }
        }
    }
    private var radialOpen = false
    private var torchOn = false
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
    private var tx = 0f
    private var ty = 0f
    private var fx = 0f
    private var fy = 0f
    private var ventStart = 0L
    private var ventMoved = false
    private var ventX = 0
    private var ventY = 0

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
        Theme.load(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cfg = ConfigStore.load(this)
        createChannel()
        loadChat()
        running = true
        try {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(powerRx, f)
        } catch (ex: Exception) {}
        AgentBrowser.onUse = { main.post { showBrowserFloat() } }
        Agent.approver = { what -> requestApproval(what) }
        Agent.onEvent = { k, t -> main.post { (typing as? Timeline)?.onEvent(k, t) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACT_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACT_RELOAD -> { rebuildChibi() }
            ACT_WAKE -> { endWake(); scheduleWake(300) }
            ACT_CALL -> { ensureChibi(); if (hidden) showChibi(); startCall() }
            ACT_HANGUP -> endCall()
            ACT_TALK -> {
                ensureChibi()
                if (hidden) showChibi()
                if (!panelOpen) openPanel()
                main.postDelayed({ if (!listeningNow()) startListening() }, 500)
            }
            ACT_SHOW -> { ensureChibi(); if (hidden) showChibi(); greetNow() }
            ACT_ASK -> {
                ensureChibi(); if (hidden) showChibi()
                val t = intent?.getStringExtra("text") ?: ""
                if (t.isNotBlank()) {
                    if (!panelOpen) openPanel()
                    val vurl = Watch.videoUrl(t)
                    val q = if (vurl != null) "Watch this with me and react: $vurl (call the watch_video tool first, then tell me what it is about in your own words, like we watched it together)"
                    else if (t.length < 60) "Explain this simply (translate it to English if it is in another language): $t"
                    else "Summarize this in a few short points: $t"
                    sendUserText(q, false)
                }
            }
            ACT_SAY -> {
                ensureChibi(); if (hidden) showChibi()
                val t = intent?.getStringExtra("text") ?: ""
                if (t.isNotBlank()) { addMsg(t, false, null); say(t, "happy", spoken = true) }
            }
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
        scheduleWake(1500)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Call.stop()
        Ears.stopAll()
        endWake()
        AgentBrowser.onUse = null
        Agent.approver = null
        Agent.onEvent = null
        hideBrowserFloat()
        main.removeCallbacksAndMessages(null)
        stopListening()
        try { tts?.shutdown() } catch (ex: Exception) {}
        try { unregisterReceiver(powerRx) } catch (ex: Exception) {}
        closeRadial()
        safeRemove(panel); safeRemove(bubble); safeRemove(chibi)
        super.onDestroy()
    }

    /** Blocks the agent thread until the user taps Allow or Deny (90 s timeout counts as Deny). */
    @Volatile private var apLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var apCard: View? = null

    private fun cancelApproval() {
        val l = apLatch; val cd = apCard
        apLatch = null; apCard = null
        main.post { if (cd != null) try { wm.removeView(cd) } catch (ex: Exception) {} }
        l?.countDown()
    }

    private fun requestApproval(what: String): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var allowed = false
        var card: LinearLayout? = null
        apLatch = latch
        main.post {
            try {
                val c = LinearLayout(this)
                c.orientation = LinearLayout.VERTICAL
                c.setPadding(dp(12), dp(9), dp(12), dp(10))
                c.background = liquid(Theme.panelBg, 16, Theme.alpha(Theme.ac, 0x99))
                val t = TextView(this)
                val h = TextView(this)
                h.text = "Nana asks"
                h.setTextColor(Theme.ac)
                h.textSize = 11f
                h.typeface = Typeface.DEFAULT_BOLD
                c.addView(h)
                t.text = what
                t.setTextColor(0xFFF2F3F5.toInt())
                t.textSize = 13.5f
                t.setPadding(0, dp(3), 0, 0)
                c.addView(t)
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(12), 0, 0)
                fun btn(label: String, bg: Int, fg: Int, ok: Boolean) {
                    val b = TextView(this)
                    b.text = label
                    b.gravity = Gravity.CENTER
                    b.setTextColor(fg)
                    b.typeface = Typeface.DEFAULT_BOLD
                    b.textSize = 13f
                    b.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(10).toFloat() }
                    b.setOnClickListener {
                        allowed = ok
                        try { wm.removeView(c) } catch (ex: Exception) {}
                        card = null
                        latch.countDown()
                    }
                    val lpb = LinearLayout.LayoutParams(0, dp(34), 1f)
                    lpb.setMargins(dp(3), 0, dp(3), 0)
                    row.addView(b, lpb)
                }
                btn("Deny", 0x33FFFFFF, 0xFFF2F3F5.toInt(), false)
                btn("Allow", Theme.ac, Theme.onAc, true)
                c.addView(row)
                val (sw, sh) = screen()
                val l = WindowManager.LayoutParams(
                    dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                )
                l.gravity = Gravity.TOP or Gravity.END
                l.x = dp(10)
                l.y = (sh * 0.36f).toInt()
                wm.addView(c, l)
                card = c
                apCard = c
            } catch (ex: Exception) { latch.countDown() }
        }
        val got = latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
        if (!got) { main.post { card?.let { try { wm.removeView(it) } catch (ex: Exception) {} } } }
        return got && allowed
    }

    private fun safeRemove(v: View?) {
        if (v == null) return
        try { wm.removeView(v) } catch (ex: Exception) {}
    }

    // ------------------------------------------------------------ floating browser

    private var bfCard: LinearLayout? = null
    private var bfLp: WindowManager.LayoutParams? = null
    private var bfHolder: FrameLayout? = null
    private var bfBig = false
    private var bfFocusable = false

    private fun bfFlags(focusable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return f
    }

    private fun setBfFocusable(f: Boolean) {
        val card = bfCard ?: return
        val l = bfLp ?: return
        if (bfFocusable == f) return
        bfFocusable = f
        l.flags = bfFlags(f)
        try { wm.updateViewLayout(card, l) } catch (ex: Exception) {}
    }

    /** Nana's browser as a small floating window above every app, the keyboard and the chat. */
    private fun showBrowserFloat() {
        if (bfCard != null) return
        if (!Settings.canDrawOverlays(this)) return
        val (sw, sh) = screen()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(Theme.panelBg); cornerRadius = dp(16).toFloat(); setStroke(dp(2), Theme.ac)
        }
        card.clipToOutline = true
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(6), dp(6), dp(6))
        val title = TextView(this)
        title.text = "Nana's browser"
        title.setTextColor(Theme.ac)
        title.textSize = 13f
        title.typeface = Typeface.DEFAULT_BOLD
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val size = TextView(this)
        size.text = "\u2922"
        size.setTextColor(0xFFF2F3F5.toInt())
        size.textSize = 17f
        size.setPadding(dp(12), dp(4), dp(12), dp(4))
        size.setOnClickListener { toggleBfSize() }
        bar.addView(size)
        val close = TextView(this)
        close.text = "\u2715"
        close.setTextColor(0xFFF2F3F5.toInt())
        close.textSize = 17f
        close.setPadding(dp(12), dp(4), dp(12), dp(4))
        close.setOnClickListener { hideBrowserFloat() }
        bar.addView(close)
        card.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val holder = FrameLayout(this)
        card.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val w = (sw * 0.94f).toInt()
        val h = (sh * 0.36f).toInt()
        val l = overlayLp(w, h, bfFlags(false))
        l.x = (sw - w) / 2
        l.y = dp(56)
        bfLp = l; bfCard = card; bfHolder = holder; bfBig = false; bfFocusable = false
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = l.x; sy = l.y; setBfFocusable(false) }
                MotionEvent.ACTION_MOVE -> {
                    l.x = sx + (e.rawX - dx).toInt(); l.y = sy + (e.rawY - dy).toInt()
                    try { wm.updateViewLayout(card, l) } catch (ex: Exception) {}
                }
            }
            true
        }
        val web = AgentBrowser.view(this)
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        holder.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // touching the page lets the keyboard work for it
        web.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) setBfFocusable(true)
            false
        }
        try { wm.addView(card, l) } catch (ex: Exception) { bfCard = null; bfHolder = null }
    }

    private fun toggleBfSize() {
        val l = bfLp ?: return
        val card = bfCard ?: return
        val (_, sh) = screen()
        bfBig = !bfBig
        l.height = (sh * (if (bfBig) 0.72f else 0.36f)).toInt()
        try { wm.updateViewLayout(card, l) } catch (ex: Exception) {}
    }

    private fun hideBrowserFloat() {
        val card = bfCard ?: return
        try { bfHolder?.removeAllViews() } catch (ex: Exception) {}
        try { wm.removeView(card) } catch (ex: Exception) {}
        bfCard = null; bfHolder = null
        AgentBrowser.release(this)
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel("chibi_channel") } catch (ex: Exception) {}
        nm.createNotificationChannel(
            NotificationChannel(CH, "Nana companion", NotificationManager.IMPORTANCE_LOW)
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
            .setContentText(if (Call.active) "On a call - " + Call.state else if (hidden) "Hidden - tap Show to bring her back" else "Tap her to chat")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(if (Call.active) act("Hang up", actionPi(4, ACT_HANGUP)) else act("Call", actionPi(1, ACT_CALL)))
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
        val w = dp((cfg.persona.size * 1.25f).toInt())
        val h = dp((cfg.persona.size * 1.45f).toInt())
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
        lp.y = sh - h
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
        chibi?.release()
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

    private var falling = false
    private var lastTapAt = 0L
    private var kbUp = false
    private var kbSavedX = 0
    private var kbTop = 0
    private var kbCheckAt = 0L

    private fun setTouchable(t: Boolean) {
        lp.flags = if (t) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        updateChibiPos()
    }
    private var fallV = 0f

    private fun floorY(): Int = (screen().second - lp.height).coerceAtLeast(0)

    private fun startFall() {
        if (panelOpen || lp.y >= floorY()) { falling = false; return }
        falling = true; fallV = 0f
        chibi?.action = "drag"
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
                if (radialOpen) { closeRadial(); longFired = true; moved = false; return true }
                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                moved = false; longFired = false
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                wake()
            }
            MotionEvent.ACTION_MOVE -> {
                if (radialOpen && longFired) { radial?.feed(e.rawX, e.rawY, false); return true }
                // chat open: she stays on top of the chat and cannot be dragged away
                if (panelOpen && !radialOpen) return true
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
                if (radialOpen && longFired) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) radial?.feed(e.rawX, e.rawY, true) else radial?.cancel()
                    return true
                }
                if (moved) {
                    dragging = false
                    c.action = "idle"
                    nextDecision = SystemClock.uptimeMillis() + 2500
                    startFall()
                    getSharedPreferences("chibi_state", MODE_PRIVATE).edit()
                        .putInt("x", lp.x).apply()
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !longFired) {
                    if (panelOpen) closePanel() else {
                        val nowT = SystemClock.uptimeMillis()
                        lastTapAt = nowT; val (pm, pt) = Persona.pokeLine(); say(pt, pm); chibi?.poke()
                    }
                }
            }
        }
        return true
    }

    private val longPress = Runnable {
        if (!moved && !kbUp) {
            longFired = true
            openRadial()
        }
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Torch on!" else "Torch off", "happy")
        } catch (ex: Exception) { say("Couldn't use the torch", "neutral") }
    }

    private fun openRadial() {
        Theme.load(this)
        if (radialOpen) return
        val items = listOf(
            RadialMenu.Item("chat", "Chat", R.drawable.ni_chat),
            RadialMenu.Item("talk", "Talk", R.drawable.ni_mic),
            RadialMenu.Item("screen", "My screen", R.drawable.ni_eye),
            RadialMenu.Item("notifs", "Notifications", R.drawable.ni_bell),
            RadialMenu.Item("remind", "Remind me", R.drawable.ni_alarm),
            RadialMenu.Item("torch", "Torch", R.drawable.ni_torch),
            RadialMenu.Item("browser", "Browser", R.drawable.ni_globe),
            RadialMenu.Item("settings", "Settings", R.drawable.ni_settings),
            RadialMenu.Item("hide", "Hide Nana", R.drawable.ni_hide),
            RadialMenu.Item("stop", "Stop task", R.drawable.ni_stop)
        )
        val rm = RadialMenu(this)
        rm.items = items
        rm.onClose = { closeRadial() }
        rm.onLook = { nx, ny -> chibi?.lookAt(nx, ny) }
        rm.onPick = { it2 ->
            when (it2.id) {
                "chat" -> openPanel()
                "talk" -> { openPanel(); main.postDelayed({ if (!listeningNow()) startListening() }, 500) }
                "screen" -> { openPanel(); sendUserText("What's on my screen right now?", false) }
                "notifs" -> { openPanel(); sendUserText("Read my recent notifications", false) }
                "remind" -> { openPanel(); fill("Remind me in 30 minutes to ") }
                "torch" -> toggleTorch()
                "browser" -> showBrowserFloat()
                "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "hide" -> hideChibi()
                "stop" -> {
                    Agent.stop(); cancelApproval()
                    endTyping(false)
                    say("Stopped.", "neutral")
                }
            }
        }
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START
        try { wm.addView(rm, l) } catch (ex: Exception) { longFired = false; return }
        val loc = IntArray(2)
        chibi?.getLocationOnScreen(loc)
        rm.open(loc[0] + lp.width / 2f, loc[1] + lp.height * 0.45f)
        radial = rm
        radialOpen = true
        hideBubble()
        chibi?.mood = "neutral"       // eyes open (smile/wave expressions close them)
        chibi?.action = "look"
        chibi?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun closeRadial() {
        val r = radial
        radial = null
        radialOpen = false
        if (r != null) { try { wm.removeView(r) } catch (ex: Exception) {} }
        if (chibi?.action == "look") chibi?.action = "idle"
        nextDecision = SystemClock.uptimeMillis() + 2000
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
            main.postDelayed(this, if (hidden || !attached) 250L else 33L)
        }
    }

    private fun step() {
        val c = chibi ?: return
        c.thinking = typing != null
        if (hidden || !attached) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastStep).coerceIn(0, 100) / 1000f
        lastStep = now

        if (talkUntil in 1..now) { c.talking = false; talkUntil = 0 }
        if (moodResetAt in 1..now) { c.mood = "neutral"; moodResetAt = 0 }
        if (dragging || panelOpen || radialOpen) return

        if (now >= nextWatchAt) { nextWatchAt = now + 8000; watchAlongTick() }

        if (now >= kbCheckAt) {
            kbCheckAt = now + 300
            val top = if (Agent.kbAside(this)) NanaAccess.inst?.imeTop() else null
            if (top != null) {
                kbTop = top
                if (!kbUp) {
                    kbUp = true; kbSavedX = lp.x; falling = false
                    hideBubble(); setTouchable(false); c.action = "idle"
                }
            } else if (kbUp) {
                kbUp = false
                setTouchable(true)
                lp.x = kbSavedX
                fx = lp.x.toFloat(); fy = lp.y.toFloat()
                startFall()
            }
        }
        if (kbUp) {
            // keyboard is open: slide to the right edge, standing on the keyboard, and ignore touches
            val (sw, _) = screen()
            val tx2 = sw - lp.width + dp(8)
            val ty2 = (kbTop - lp.height).coerceAtLeast(0)
            lp.x += ((tx2 - lp.x) * 0.25f).toInt()
            lp.y += ((ty2 - lp.y) * 0.25f).toInt()
            fx = lp.x.toFloat(); fy = lp.y.toFloat()
            updateChibiPos()
            return
        }

        if (falling) {
            fallV += dp(2600) * dt
            val floor = floorY()
            var ny = lp.y + fallV * dt
            if (ny >= floor) {
                if (fallV > dp(450)) { fallV = -fallV * 0.28f; ny = floor.toFloat() }
                else { falling = false; ny = floor.toFloat(); c.action = "idle"; nextDecision = now + 1500 }
            }
            lp.y = ny.toInt(); fx = lp.x.toFloat(); fy = lp.y.toFloat()
            updateChibiPos()
            return
        }

        if (ventStart != 0L) {
            updateVent(now, c)
            return
        }
        if (c.action == "walk" || c.action == "run") {
            val speed = dp(if (c.action == "run") 170 else 75) * dt
            val dx = tx - fx
            val dy = ty - fy
            val dist = kotlin.math.hypot(dx, dy)
            if (dist > dp(3)) {
                val ang = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
                val a = kotlin.math.abs(ang)
                val sg = if (ang > 0) 1f else -1f
                c.yaw = if (a <= 90f) ang * (55f / 90f) else sg * (55f + (a - 90f) * (125f / 90f))
                if (kotlin.math.abs(dx) > dp(2)) c.facingRight = dx > 0
            }
            if (dist <= speed + 1f) {
                fx = tx; fy = ty
                c.action = "idle"; c.yaw = 0f
                nextDecision = now + Random.nextLong(600, 2200)
            } else {
                fx += dx / dist * speed; fy += dy / dist * speed
            }
            lp.x = fx.toInt(); lp.y = fy.toInt()
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

    private fun randomSpot(): Pair<Float, Float> {
        val (sw, sh) = screen()
        val minX = -dp(10).toFloat()
        val maxX = (sw - lp.width + dp(10)).coerceAtLeast(-dp(10)).toFloat()
        val minY = (sh - lp.height).coerceAtLeast(0).toFloat()
        val maxY = minY
        val r = Random.nextFloat()
        return when {
            r < 0.3f -> (if (Random.nextBoolean()) minX else maxX) to (if (Random.nextBoolean()) minY else maxY)
            r < 0.5f -> (if (Random.nextBoolean()) minX else maxX) to (minY + Random.nextFloat() * (maxY - minY))
            r < 0.65f -> (minX + Random.nextFloat() * (maxX - minX)) to (if (Random.nextBoolean()) minY else maxY)
            else -> (minX + Random.nextFloat() * (maxX - minX)) to (minY + Random.nextFloat() * (maxY - minY))
        }
    }

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = x - 1f
        return 1f + c3 * t * t * t + c1 * t * t
    }

    private fun startVent(now: Long, c: ChibiView) {
        val (sw, sh) = screen()
        val minX = -dp(10)
        val maxX = (sw - lp.width + dp(10)).coerceAtLeast(minX)
        val maxY = (sh - lp.height).coerceAtLeast(0)
        ventX = Random.nextInt(minX, maxX + 1)
        ventY = maxY
        ventMoved = false
        ventStart = now
        c.action = "vent"
        hideBubble()
    }

    private fun updateVent(now: Long, c: ChibiView) {
        val t = (now - ventStart).toInt()
        val h = c.height.toFloat().coerceAtLeast(1f)
        val hop = h * 0.05f
        c.vsx = 1f; c.vsy = 1f
        when {
            t < 300 -> { c.vopen = t / 300f; c.vdy = 0f; c.vsy = 1f - 0.08f * (t / 300f) }
            t < 500 -> { c.vopen = 1f; c.vdy = -hop * kotlin.math.sin(Math.PI.toFloat() * (t - 300) / 200f) }
            t < 900 -> { val k = (t - 500) / 400f; c.vopen = 1f; c.vdy = h * 1.1f * k * k }
            t < 1150 -> { c.vopen = 1f - (t - 900) / 250f; c.vdy = h * 1.1f }
            t < 1450 -> {
                c.vdy = h * 1.1f
                if (!ventMoved) {
                    ventMoved = true
                    c.vopen = 0f
                    lp.x = ventX; lp.y = ventY
                    fx = lp.x.toFloat(); fy = lp.y.toFloat()
                    updateChibiPos()
                }
                if (t >= 1200) c.vopen = ((t - 1200) / 250f).coerceAtMost(1f)
            }
            t < 1850 -> { val k = (t - 1450) / 400f; c.vopen = 1f; c.vdy = h * 1.1f * (1f - (1f - (1f - k) * (1f - k))) }
            t < 2150 -> { c.vopen = 1f; c.vdy = -hop * kotlin.math.sin(Math.PI.toFloat() * (t - 1850) / 300f) }
            t < 2450 -> { c.vopen = 1f - (t - 2150) / 300f; c.vdy = 0f }
            else -> {
                c.vopen = 0f; c.vdy = 0f; c.action = "idle"; ventStart = 0L
                nextDecision = now + 1200
            }
        }
        c.invalidate()
    }

    private fun decide(now: Long, c: ChibiView) {
        c.pixel = cfg.persona.style != "smooth"
        val r = Random.nextFloat()
        var dur = Random.nextLong(2500, 5500)
        val walking = cfg.persona.walking
        when {
            !walking && r < 0.7f -> c.action = "idle"
            r < 0.10f -> c.action = "idle"
            r < 0.58f -> {
                val (x, y) = randomSpot()
                tx = x; ty = y
                fx = lp.x.toFloat(); fy = lp.y.toFloat()
                val far = kotlin.math.hypot(tx - fx, ty - fy) > screen().first * 0.6f
                c.action = if (far) "run" else "walk"
                dur = 20_000
            }
            r < 0.68f -> { c.action = "sit"; dur = Random.nextLong(4000, 9000) }
            r < 0.76f -> { c.action = "wave"; dur = 2200 }
            r < 0.86f -> {
                c.action = "sus"; dur = Random.nextLong(3000, 5000)
                if (Random.nextBoolean()) say("Hm? What was that?", "neutral")
            }
            r < 0.93f -> { c.action = "sit"; dur = Random.nextLong(3000, 6000) }
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
                setTextColor(0xFF0E2A2B.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = dp(230)
                background = GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat()
                    setStroke(dp(2), Theme.ac)
                }
            }
            bubble = b
            bubbleLp = overlayLp(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
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

    private var sendBtn: android.widget.ImageView? = null
    private var subtitle: TextView? = null

    /** Liquid-glass surface: translucent base, bright top-left specular sheen and a light rim. */
    private fun liquid(base: Int, r: Int, stroke: Int = 0x38FFFFFF): android.graphics.drawable.Drawable {
        val rr = dp(r).toFloat()
        val a = GradientDrawable().apply { setColor(base); cornerRadius = rr; setStroke(dp(1), stroke) }
        val spec = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x30FFFFFF, 0x0CFFFFFF, 0x00FFFFFF, 0x14FFFFFF)).apply { cornerRadius = rr }
        return android.graphics.drawable.LayerDrawable(arrayOf(a, spec))
    }

    private fun glass(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
    }

    private fun iconBtn(res: Int, fill: Int, tint: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(res); setColorFilter(tint)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pd = dp(10); setPadding(pd, pd, pd, pd)
        background = glass(fill, 0x33FFFFFF, 21)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) }
    }

    private fun endTyping(ok: Boolean, status: String = "stopped") {
        val t = typing; typing = null
        if (t is Timeline) {
            if (t.stepCount() > 0) t.finish(if (ok) "done" else status) else listCol?.removeView(t)
        } else if (t != null) listCol?.removeView(t)
        refreshBusy()
    }

    private fun refreshBusy() {
        val busy = typing != null
        subtitle?.text = if (busy) "working on it..." else "online"
        subtitle?.setTextColor(if (busy) Theme.ac else Theme.ac2)
        val empty = (input?.text?.toString()?.trim() ?: "").isEmpty()
        sendBtn?.setImageResource(if (busy && empty) R.drawable.ni_stop else R.drawable.ni_send)
    }

    private fun md(s: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var last = 0
        for (m in re.findAll(s)) {
            sb.append(s.substring(last, m.range.first))
            val st = sb.length
            if (m.groupValues[1].isNotEmpty()) {
                sb.append(m.groupValues[1])
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(m.groupValues[2])
                sb.setSpan(android.text.style.TypefaceSpan("monospace"), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.BackgroundColorSpan(0x33FFFFFF), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        sb.append(s.substring(last))
        return sb
    }

    private fun saveChat() {
        try {
            val a = org.json.JSONArray()
            history.takeLast(40).forEach { a.put(org.json.JSONObject().put("r", it.role).put("c", it.content)) }
            getSharedPreferences("chat", Context.MODE_PRIVATE).edit().putString("h", a.toString()).apply()
        } catch (ex: Exception) {}
    }

    private fun loadChat() {
        try {
            val a = org.json.JSONArray(getSharedPreferences("chat", Context.MODE_PRIVATE).getString("h", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); history.add(Msg(o.getString("r"), o.getString("c"))) }
        } catch (ex: Exception) {}
    }

    private fun clearChat() {
        history.clear(); saveChat()
        listCol?.removeAllViews()
        chipRow?.visibility = View.VISIBLE
        addMsg("Fresh start! What's up, ${cfg.persona.userName}?", false, null)
    }

    private fun chip(res: Int, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12.5f; setTextColor(0xFFF2F3F5.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(11), dp(7), dp(13), dp(7))
        background = glass(0x1FFFFFFF, 0x33FFFFFF, 16)
        val d = this@CompanionService.getDrawable(res)?.mutate()
        d?.setTint(Theme.ac2); d?.setBounds(0, 0, dp(15), dp(15))
        setCompoundDrawables(d, null, null, null); compoundDrawablePadding = dp(6)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
    }

    private fun fill(text: String) {
        val et = input ?: return
        et.setText(text); et.setSelection(text.length); et.requestFocus()
    }

    private fun ghostBtn(res: Int, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(res); setColorFilter(0xB3FFFFFF.toInt())
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pd = dp(8); setPadding(pd, pd, pd, pd)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun buildPanel() {
        val root = PanelLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(10), dp(12), dp(12))
        root.background = liquid(Theme.alpha(Theme.panelBg, 0xEC), 28, 0x40FFFFFF)
        root.onBack = { closePanel() }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = cfg.persona.name; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
        })
        val sub = TextView(this).apply { text = "online"; textSize = 11f; setTextColor(Theme.ac2); setPadding(dp(8), dp(2), 0, 0) }
        titleRow.addView(sub)
        header.addView(titleRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ghostBtn(R.drawable.ni_clear) { clearChat() })
        header.addView(ghostBtn(R.drawable.ni_close) { closePanel() })
        root.addView(header)

        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(4), dp(4)) }
        sc.addView(col)
        root.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val chipScroll = android.widget.HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip(R.drawable.ni_eye, "My screen") { sendUserText("What's on my screen right now?", false) })
        chips.addView(chip(R.drawable.ni_bell, "Notifications") { sendUserText("Read my recent notifications", false) })
        chips.addView(chip(R.drawable.ni_alarm, "Remind me") { fill("Remind me in 30 minutes to ") })
        chips.addView(chip(R.drawable.ni_search, "Search web") { fill("Search the web for ") })
        chips.addView(chip(R.drawable.ni_apps, "Open app") { fill("Open ") })
        chips.addView(chip(R.drawable.ni_battery, "Phone status") { sendUserText("What's my battery and the time?", false) })
        chipScroll.addView(chips)
        chipRow = chipScroll
        chipScroll.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        root.addView(chipScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); rightMargin = dp(4) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(3), dp(4), dp(3))
            background = glass(0x14FFFFFF, 0x26FFFFFF, 26)
        }
        val et = EditText(this).apply {
            hint = "Message Nana..."
            setHintTextColor(0x77FFFFFF); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            background = null
            setPadding(0, dp(9), dp(8), dp(9))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { doSend(false); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { refreshBusy() }
            })
        }
        val mic = ghostBtn(R.drawable.ni_mic) { if (!listeningNow()) startListening() else stopListening() }
        val send = iconBtn(R.drawable.ni_send, Theme.ac, Theme.onAc) { if (typing != null && (input?.text?.toString()?.trim() ?: "").isEmpty()) stopTask() else doSend(false) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(2) }
            background = glass(Theme.ac, 0x00000000, 18)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(mic); row.addView(send)
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); rightMargin = dp(4) })

        panelV = Theme.version
        panel = root; scroll = sc; listCol = col; input = et; micBtn = mic; sendBtn = send; subtitle = sub
        val (sw, sh) = screen()
        panelLp = WindowManager.LayoutParams(
            sw - dp(16), (sh * 0.5f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 31) {
                try { flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND; blurBehindRadius = dp(26) } catch (ex: Throwable) {}
            }
        }
        if (history.isEmpty()) addMsg("Hi ${cfg.persona.userName}! What's up?", false, null)
        else history.takeLast(16).forEach { addMsg(it.content, it.role == "user", null) }
        refreshBusy()
    }

    private fun openPanel() {
        if (panelOpen) return
        Theme.load(this)
        cfg = ConfigStore.load(this)
        if (panel != null && panelV != Theme.version) panel = null
        if (panel == null) buildPanel()
        hideBubble()
        val (_, sh) = screen()
        panelSbH = statusBarH(); panelBaseH = (sh * 0.5f).toInt()
        val ph = panelLp!!.height
        preY = lp.y; preX = lp.x
        try { wm.addView(panel, panelLp); panelAttached = true } catch (ex: Exception) {}
        panelOpen = true
        // chat open: she is a bit smaller and rests on the panel's top edge
        origW = lp.width; origH = lp.height
        winOffset = 0
        chibi?.let { c0 ->
            if (attached && c0.isAttachedToWindow && c0.height > 0) {
                val cl0 = IntArray(2); c0.getLocationOnScreen(cl0); winOffset = cl0[1] - lp.y
            }
        }
        val nw = (origW * 0.78f).toInt(); val nh = (origH * 0.78f).toInt()
        lp.x += (origW - nw) / 2; lp.width = nw; lp.height = nh
        // keep her over the straight part of the chat's top border, away from its rounded corners
        run {
            val sw0 = screen().first
            val minX0 = dp(8) + dp(28)
            val maxX0 = sw0 - dp(8) - dp(28) - lp.width
            lp.x = lp.x.coerceIn(minX0, maxOf(minX0, maxX0))
        }
        lp.y = sh - ph - dp(8) - lp.height + dp(23) - winOffset
        detachChibi(); attachChibi()
        chibi?.action = "idle"
        chibi?.pose = true
        stableFrames = 0
        android.view.Choreographer.getInstance().removeFrameCallback(anchorFrame)
        android.view.Choreographer.getInstance().postFrameCallback(anchorFrame)
        wake()
        say("Yes?", "happy")
    }

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    /** Keeps her glued to the top edge of the chat, also while the keyboard opens or closes.
     *  Runs every frame (no polling delay), so she moves together with the chat.
     *  If the keyboard leaves too little room above the chat, the chat gets a bit shorter. */
    private val anchorFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!panelOpen) return
            anchorChibi()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var winOffset = 0   // how far her window is drawn from the y we ask for (e.g. status bar)
    private var stableFrames = 0
    private var panelSbH = 0
    private var panelBaseH = 0
    private var lastNx = Int.MIN_VALUE

    private fun anchorChibi() {
        val p = panel ?: return
        if (!panelOpen || !panelAttached || p.height <= 0) return
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        val bottom = loc[1] + p.height
        val minTop = panelSbH + lp.height - dp(23)
        val want = minOf(panelBaseH, bottom - minTop).coerceAtLeast(dp(220))
        val pl = panelLp
        if (pl != null && kotlin.math.abs(want - pl.height) > dp(6)) {
            pl.height = want
            try { wm.updateViewLayout(p, pl) } catch (ex: Exception) {}
            return
        }
        val c = chibi ?: return
        // stay over the straight part of the top border (the corners are rounded, 28dp)
        val minX = loc[0] + dp(28)
        val maxX = loc[0] + p.width - dp(28) - lp.width
        val nx = lp.x.coerceIn(minX, maxOf(minX, maxX))
        if (nx != lp.x) { lp.x = nx; fx = nx.toFloat() }
        val ny = loc[1] + dp(23) - lp.height - winOffset
        if (ny != lp.y || nx != lastNx) {
            lastNx = nx
            stableFrames = 0
            lp.y = ny; fy = ny.toFloat(); updateChibiPos()
        } else if (stableFrames < 5) {
            stableFrames++
        } else if (c.height > 0 && c.isAttachedToWindow) {
            // she stands still: learn the real offset of her window once, so later moves need no feedback
            val cl = IntArray(2)
            c.getLocationOnScreen(cl)
            val off = cl[1] - lp.y
            if (off != winOffset) { winOffset = off; stableFrames = 0 }
        }
    }

    private fun closePanel() {
        if (!panelOpen) return
        stopListening()
        panelOpen = false
        val pv = panel
        if (pv != null && panelAttached) { try { wm.removeView(pv) } catch (ex: Exception) {}; panelAttached = false }
        android.view.Choreographer.getInstance().removeFrameCallback(anchorFrame)
        if (origW > 0) { lp.width = origW; lp.height = origH; lp.x = preX }
        lp.y = preY
        chibi?.pose = false
        panelLp?.let { it.height = (screen().second * 0.5f).toInt() }
        updateChibiPos()
        startFall()
        nextDecision = SystemClock.uptimeMillis() + 2000
    }

    private fun addMsg(msg: String, user: Boolean, meta: String?) {
        val col = listCol ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stamp = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
        val info = TextView(this).apply {
            text = if (meta != null) "$stamp · $meta" else stamp
            textSize = 10f; setTextColor(0x77FFFFFF); setPadding(dp(4), dp(3), dp(4), 0)
            gravity = if (user) Gravity.END else Gravity.START
            visibility = View.GONE
        }
        val t = TextView(this).apply {
            setText(md(msg), TextView.BufferType.SPANNABLE)
            textSize = 15f
            setLineSpacing(0f, 1.12f)
            setLinkTextColor(Theme.ac2)
            if (user) {
                setTextColor(Theme.onAc)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                maxWidth = (screen().first * 0.72f).toInt()
                val r = dp(18).toFloat(); val k = dp(5).toFloat()
                background = GradientDrawable().apply { setColor(Theme.ac); cornerRadii = floatArrayOf(r, r, r, r, k, k, r, r) }
            } else {
                setTextColor(0xFFF2F3F5.toInt())
                setPadding(dp(2), dp(2), dp(10), dp(2))
                maxWidth = (screen().first * 0.88f).toInt()
            }
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            setOnClickListener { info.visibility = if (info.visibility == View.GONE) View.VISIBLE else View.GONE }
            setOnLongClickListener {
                try {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Nana", msg))
                    android.widget.Toast.makeText(this@CompanionService, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {}
                true
            }
        }
        box.addView(t)
        box.addView(info)
        col.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(10) })
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun doSend(voice: Boolean) {
        val et = input ?: return
        val text = et.text.toString().trim()
        if (text.isEmpty()) return
        et.setText("")
        sendUserText(text, voice)
    }

    private val STOP = Regex("^\\s*(stop|ruk|ruko|ruk ja|cancel|rehne do|rehne de|chhod do|chod do|band kar|nahi rehne do)\\b[\\s.!]*$", RegexOption.IGNORE_CASE)

    private fun stopTask() {
        Agent.stop()
        cancelApproval()
        endTyping(false)
        addMsg("Okay, ruk gayi.", false, null)
        chibi?.mood = "neutral"
    }

    private fun sendUserText(text: String, voice: Boolean) {
        wake()
        if (STOP.containsMatchIn(text)) {
            addMsg(text, true, null)
            if (typing != null) stopTask() else addMsg("Kuch chal hi nahi raha tha.", false, null)
            return
        }
        // a new message while she is still working interrupts the old task
        if (typing != null) { Agent.stop(); cancelApproval(); endTyping(false) }
        addMsg(text, true, null)
        history.add(Msg("user", text))
        Memory.bump(this)
        saveChat()
        while (history.size > 60) history.removeAt(0)
        val t = Timeline(this, text)
        typing = t
        refreshBusy()
        listCol?.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        chipRow?.visibility = View.GONE
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        chibi?.mood = "neutral"
        val conf = ConfigStore.load(this)
        cfg = conf
        val snapshot = history.takeLast(conf.persona.history).toList()
        val myTyping = t
        Thread {
            try {
                val res = Agent.run(this, conf, snapshot, voice) { _ -> }
                main.post { if (typing === myTyping) onReply(res, voice) }
            } catch (e: Agent.Interrupted) {
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
        endTyping(true)
        val (mood, clean) = parseMood(res.text)
        history.add(Msg("assistant", clean))
        saveChat()
        addMsg(clean, false, "via ${res.provider.name} · ${res.ms}ms")
        val mode = cfg.persona.ttsMode
        say(clean, mood, spoken = Call.active || mode == "always" || (mode == "voice" && voice))
        Learner.after(this, cfg, history.takeLast(8).toList()) { _ -> main.post { if (typing == null) say("Noted, I'll remember that.", "happy") } }
    }

    private fun onError(msg: String) {
        endTyping(false, "failed")
        addMsg("Error: $msg", false, null)
        say("Something went wrong...", "sad")
    }

    // ---- watch-along: opt-in; reads the on-screen text of video apps and makes one tiny remark ----
    private var nextWatchAt = 0L
    private var lastWatchAt = 0L
    private var lastWatchSig = 0
    private val watchBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val WATCH_APPS = setOf(
        "com.instagram.android", "com.instagram.lite", "com.google.android.youtube", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "com.facebook.katana", "com.facebook.lite", "com.snapchat.android"
    )

    private fun watchAlongTick() {
        if (!getSharedPreferences("nana_watch", MODE_PRIVATE).getBoolean("on", false)) return
        val a = NanaAccess.inst ?: return
        val pkg = a.foregroundPkg() ?: return
        if (pkg !in WATCH_APPS) return
        if (SystemClock.uptimeMillis() - lastWatchAt < 22_000) return
        if (!watchBusy.compareAndSet(false, true)) return
        val conf = cfg
        Thread {
            try {
                val screen = a.read()
                val low = screen.lowercase()
                val video = listOf("reel", "shorts", "original audio", "for you", "remix").any { low.contains(it) }
                val priv = listOf("type a message", "message...", "password", "inbox", "send message").any { low.contains(it) }
                if (!video || priv) return@Thread
                val txt = screen.take(1800)
                val sig = txt.hashCode()
                if (sig == lastWatchSig) return@Thread
                lastWatchSig = sig
                lastWatchAt = SystemClock.uptimeMillis()
                val ask = Msg("user", "(You are watching short videos on the phone together with ${conf.persona.userName}. This is the text visible on screen right now:\n$txt\n\nGive ONE tiny natural reaction, under 14 words, about what the text tells you (caption, creator, comments). If it does not show a video or tells you nothing, reply exactly SKIP. Never mention reading the screen text.)")
                val res = Router.chat(conf, Persona.system(conf), listOf(ask), false)
                val (m, t) = parseMood(res.text)
                if (t.isBlank() || t.trim().startsWith("SKIP", true)) return@Thread
                main.post { if (!panelOpen && !hidden) say(t, m) }
            } catch (ex: Exception) {
            } finally {
                watchBusy.set(false)
            }
        }.start()
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

    @Volatile private var lastSpokenText = ""

    private fun speak(text: String) {
        lastSpokenText = text
        Voice.speak(this, text) { err ->
            if (err != null) main.post { addMsg("Voice problem: $err", false, null) }
        }
    }

    // ---- live voice call (mic stays open until hang up, works over other apps) ----
    private val callHost = object : Call.Host {
        override fun onUtterance(text: String) {
            main.post {
                val x = text.replace(WAKE_HEAD, "").trim()
                if (x.isNotBlank()) sendUserText(x, true)
            }
        }
        override fun speaking(): Boolean = Voice.playing()
        override fun stopSpeaking() { Voice.stop() }
        override fun lastSpoken(): String = lastSpokenText
        override fun state(s: String) { main.post { refreshNotification() } }
        override fun ended(why: String) {
            main.post {
                refreshNotification()
                if (why.startsWith("error")) say("Call ended: " + why.removePrefix("error: "), "sad")
                else if (why == "bye") say("Bye bye!", "happy", spoken = true)
                scheduleWake(1200)
            }
        }
    }

    private fun startCall() {
        if (Call.active) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("I need mic permission first. Open the Nana app and tap Grant mic.", "sad"); return
        }
        if (!Speech.ready(this, "asr")) {
            say("Download the speech recognition model in Settings > Voice first.", "sad"); return
        }
        main.removeCallbacks(wakeDog)
        endWake()
        startFg()
        Call.start(this, callHost)
        refreshNotification()
        say("I'm listening.", "happy", spoken = true)
    }

    private fun endCall() { Call.stop(); Voice.stop() }

    private fun speakSystem(text: String) {
        if (!ttsReady) return
        applyTts()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chibi")
    }

    // ---- "Hey Nana" wake word: listens in short rounds while the screen is on ----
    private var wakeSr: SpeechRecognizer? = null
    private var wakeFails = 0

    // ---- offline wake word + offline dictation (sherpa-onnx) ----
    private var localListening = false
    private val wakeDog = Runnable { startWake() }
    private val earsHost = object : Ears.Host {
        override fun onWake(keyword: String) {
            wakeHeard = "\"" + keyword.replace('_', ' ') + "\" at " + java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            main.post { triggerWake("") }
        }
        override fun speaking(): Boolean = Voice.playing() || (tts?.isSpeaking == true)
        override fun state(s: String) { wakeState = s }
    }
    private val WAKE_HEAD = Regex("^\\s*(?:(?:hey|hi|ok|okay)\\s+)?(?:nana|nanna|na\\s+na)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)

    private fun listeningNow(): Boolean = recognizer != null || localListening

    private fun startLocalWake() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { wakeState = "no-mic"; scheduleWake(6000); return }
        Ears.ensureWake(this, earsHost)
        main.removeCallbacks(wakeDog)
        main.postDelayed(wakeDog, 20000)
    }

    private fun startLocalListening() {
        localListening = true
        micBtn?.setColorFilter(0xFFFF6B7A.toInt()); chibi?.mood = "excited"
        Ears.listen(this, earsHost,
            { t -> main.post { if (localListening && panelOpen) input?.setText(t) } },
            { t -> main.post {
                localListening = false
                micBtn?.setColorFilter(0xFFFFFFFF.toInt())
                input?.setText("")
                val x = t.replace(WAKE_HEAD, "").trim()
                if (x.isNotBlank()) { if (!panelOpen) openPanel(); sendUserText(x, true) }
                scheduleWake(1500)
            } },
            { _ -> main.post {
                if (localListening) { localListening = false; micBtn?.setColorFilter(0xFFFFFFFF.toInt()); if (!panelOpen) say("I didn't catch that.", "neutral") }
                scheduleWake(1500)
            } })
    }

    /** Nana is also the "assistant", and its own recognition service is only a stub, so always pick a real recognizer (Google) explicitly. */
    private fun newRecognizer(): SpeechRecognizer {
        try {
            val list = packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }.filter { it.packageName != packageName }
            val pick = list.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
                ?: list.firstOrNull { it.packageName.contains("google") } ?: list.firstOrNull()
            if (pick != null) return SpeechRecognizer.createSpeechRecognizer(this, android.content.ComponentName(pick.packageName, pick.name))
        } catch (ex: Exception) {}
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun recognizerAvailable(): Boolean = try {
        packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0).any { it.serviceInfo.packageName != packageName }
    } catch (ex: Exception) { SpeechRecognizer.isRecognitionAvailable(this) }
    private val WAKE_STRONG = Regex("\\b(?:n[aeu]+n+[aeu]+h?|na\\s+na|nan\\s+a)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)
    private val WAKE_WEAK = Regex("\\b(?:hey|hi|hello|ok|okay)\\s+(?:anna|hanna|hannah|nina|nona|lana)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)

    /** Returns the words spoken after the wake word, or null when none of the heard alternatives had it. */
    private fun wakeMatch(list: List<String>): String? {
        for (t in list) {
            val m = WAKE_STRONG.find(t) ?: WAKE_WEAK.find(t)
            if (m != null) return t.substring(m.range.last + 1).trim()
        }
        return null
    }

    /** Same wake-up as the power button: she pops up on screen, says "Yes?" and listens (or takes the command you said right after her name). */
    private fun triggerWake(rest: String) {
        ensureChibi()
        if (hidden) showChibi()
        if (rest.length >= 3) { if (!panelOpen) openPanel(); sendUserText(rest, true) }
        else if (Speech.asrLocal(this)) { say("Yes?", "happy"); startListening() }
        else { say("Yes?", "happy"); main.postDelayed({ startListening() }, 350) }
        scheduleWake(3000)
    }

    private fun scheduleWake(delay: Long) { main.postDelayed({ startWake() }, delay) }

    private fun endWake() {
        val s = wakeSr ?: return
        wakeSr = null
        try { s.cancel(); s.destroy() } catch (ex: Exception) {}
    }

    private fun startWake() {
        if (!running) return
        if (Call.active) return
        if (!Agent.wake(this)) { Ears.stopWake(); wakeState = "off"; return }
        if (Speech.wakeLocal(this)) { endWake(); startLocalWake(); return }
        Ears.stopWake()
        if (wakeSr != null || recognizer != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { wakeState = "no-mic"; scheduleWake(6000); return }
        if (!recognizerAvailable()) { wakeState = "no-recognizer"; return }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) { wakeState = "screen-off"; scheduleWake(4000); return }
        if (typing != null) { wakeState = "busy"; scheduleWake(4000); return }
        val sr = newRecognizer()
        wakeSr = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { wakeState = "listening"; wakeFails = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onError(error: Int) {
                endWake()
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { wakeState = "no-mic"; scheduleWake(6000); return }
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (quiet) { wakeFails = 0; wakeState = "listening" } else { wakeFails++; wakeState = "error:$error" }
                scheduleWake(when {
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3000L
                    wakeFails > 5 -> 8000L
                    quiet -> 250L
                    else -> 600L
                })
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                wakeHeard = list.firstOrNull() ?: ""
                val rest = wakeMatch(list)
                endWake()
                if (rest == null) { scheduleWake(200); return }
                triggerWake(rest)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, cfg.persona.voiceLang)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        try { sr.startListening(i) } catch (ex: Exception) { endWake(); wakeState = "error:5"; scheduleWake(5000) }
    }

    private fun startListening() {
        if (Call.active) { addMsg("You're on a call. Just talk, or tap Hang up in the notification.", false, null); return }
        if (Speech.asrLocal(this) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { startLocalListening(); return }
        Ears.pause(true)
        endWake()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addMsg("I need mic permission. Open the Nana app and tap 'Grant mic'.", false, null); return
        }
        if (!recognizerAvailable()) {
            addMsg("No speech recognizer found on this phone (needs the Google app).", false, null); return
        }
        stopListening()
        val sr = newRecognizer()
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { micBtn?.setColorFilter(0xFFFF6B7A.toInt()); chibi?.mood = "excited" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { stopListening(); if (!panelOpen) say("I didn't catch that.", "neutral") }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stopListening()
                input?.setText("")
                if (!text.isNullOrBlank()) { if (!panelOpen) openPanel(); sendUserText(text, true) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank() && recognizer != null) input?.setText(text)
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
        if (localListening) {
            localListening = false; Ears.cancelListen()
            micBtn?.setColorFilter(0xFFFFFFFF.toInt()); scheduleWake(1500); return
        }
        Ears.pause(false)
        val sr = recognizer ?: run { micBtn?.setColorFilter(0xFFFFFFFF.toInt()); return }
        recognizer = null
        try { sr.stopListening(); sr.destroy() } catch (ex: Exception) {}
        micBtn?.setColorFilter(0xFFFFFFFF.toInt())
        scheduleWake(1500)
    }
}
