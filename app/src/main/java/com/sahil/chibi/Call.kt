package com.sahil.chibi

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.k2fsa.sherpa.onnx.OnlineRecognizer

/** Puts the phone in "call" audio mode so the echo canceller can remove Nana's own voice from the mic. */
object CallAudio {
    @Volatile private var on = false
    private var previousMode = AudioManager.MODE_NORMAL

    fun active(): Boolean = on

    @Synchronized fun enter(ctx: Context) {
        if (on) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            previousMode = am.getMode()
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            var headset = false
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val t = d.type
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_USB_HEADSET) headset = true
            }
            if (!headset) {
                if (Build.VERSION.SDK_INT >= 31) {
                    for (d in am.availableCommunicationDevices) {
                        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); break }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
            }
            on = true
        } catch (e: Throwable) {}
    }

    @Synchronized fun exit(ctx: Context) {
        if (!on) return
        on = false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = previousMode
        } catch (e: Throwable) {}
    }
}

/**
 * Live voice call, like ChatGPT voice: the mic stays open until you hang up, even in other apps.
 * What you say is recognised on the phone, sent to Nana, and her answer is spoken. You can talk over her to stop her.
 */
object Call {
    interface Host {
        fun onUtterance(text: String)
        fun speaking(): Boolean
        fun stopSpeaking()
        fun lastSpoken(): String
        fun state(s: String)
        fun ended(why: String)
    }

    @Volatile var active = false
    @Volatile var muted = false
    @Volatile var state = "off"
    @Volatile private var stopReq = false
    @Volatile private var th: Thread? = null

    private val END = Regex("\\b(hang up|end (the )?call|stop (the )?call|bye nana|goodbye nana|good bye nana)\\b", RegexOption.IGNORE_CASE)

    fun start(ctx: Context, h: Host): Boolean {
        synchronized(this) {
            if (active) return true
            active = true
            stopReq = false
            muted = false
        }
        val app = ctx.applicationContext
        CallAudio.enter(app)
        Ears.stopAll()
        val t = Thread({ loop(app, h) }, "nana-call")
        t.isDaemon = true
        th = t
        t.start()
        return true
    }

    fun stop() { stopReq = true }

    private fun setState(h: Host, s: String) {
        if (state != s) { state = s; h.state(s) }
    }

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }

    /** How many heard words are NOT in what Nana was saying (these must be you, not her echo). */
    private fun novel(heard: String, spoken: String): Int {
        val sp = words(spoken).toHashSet()
        return words(heard).count { it !in sp }
    }

    private fun loop(ctx: Context, h: Host) {
        var rec: AudioRecord? = null
        var asr: OnlineRecognizer? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var why = "ended"
        try {
            setState(h, "loading")
            val m = Speech.guarded(ctx, "asr") { OnlineRecognizer(config = Speech.asrConfig(ctx)) }
            asr = m
            val st = m.createStream()
            Thread.sleep(400)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) throw RuntimeException("Microphone is not available")
            try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(r.audioSessionId); aec?.enabled = true } } catch (e: Throwable) {}
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(r.audioSessionId); ns?.enabled = true } } catch (e: Throwable) {}
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw RuntimeException("Microphone is blocked by Android")

            val buf = ShortArray(1600)
            var zero = 0
            var wasSpeaking = false
            var lastReset = System.currentTimeMillis()
            var lastSpeechEnd = 0L
            var shown = ""
            while (!stopReq) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }
                var peak = 0
                val f = FloatArray(n)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                    f[i] = v / 32768f
                }
                if (peak == 0) zero++ else zero = 0
                if (zero > 60) { setState(h, "mic-blocked"); continue }
                if (muted) { setState(h, "muted"); m.reset(st); continue }

                val now = System.currentTimeMillis()
                val speaking = h.speaking()
                st.acceptWaveform(f, 16000)
                while (m.isReady(st)) m.decode(st)
                val t = m.getResult(st).text.trim()

                if (speaking) {
                    wasSpeaking = true
                    setState(h, "speaking")
                    val barge = Speech.ears(ctx).optBoolean("barge", true)
                    if (barge && t.isNotEmpty() && novel(t, h.lastSpoken()) >= 3) {
                        h.stopSpeaking()
                        wasSpeaking = false
                        lastSpeechEnd = 0L
                    } else {
                        if (now - lastReset > 2500) { m.reset(st); lastReset = now }
                        continue
                    }
                } else if (wasSpeaking) {
                    wasSpeaking = false
                    lastSpeechEnd = now
                    m.reset(st)
                    lastReset = now
                    continue
                }

                if (t.isNotEmpty()) { if (t != shown) { shown = t; setState(h, "hearing") } }
                else setState(h, "listening")

                if (m.isEndpoint(st)) {
                    m.reset(st)
                    lastReset = now
                    shown = ""
                    if (t.isBlank()) continue
                    val echo = now - lastSpeechEnd < 2500 && novel(t, h.lastSpoken()) < 2
                    if (echo) continue
                    if (END.containsMatchIn(t)) { why = "bye"; break }
                    h.onUtterance(t)
                }
            }
        } catch (e: Throwable) {
            why = "error: " + (e.message ?: e.javaClass.simpleName).take(80)
        } finally {
            try { rec?.stop() } catch (e: Throwable) {}
            try { rec?.release() } catch (e: Throwable) {}
            try { aec?.release() } catch (e: Throwable) {}
            try { ns?.release() } catch (e: Throwable) {}
            try { asr?.release() } catch (e: Throwable) {}
            CallAudio.exit(ctx)
            synchronized(this) { active = false; th = null }
            state = "off"
            h.ended(why)
        }
    }
}
