package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.view.TextureView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Transparent GL view that draws and animates the VRM character. */
class GlView(ctx: Context, private val owner: ChibiView, private val file: File) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var th: RenderThread? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val t = RenderThread(st)
        th = t
        t.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        th?.quit()
        th = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private inner class RenderThread(private val st: SurfaceTexture) : Thread("hina-gl") {
        @Volatile private var running = true
        fun quit() { running = false; try { join(1000) } catch (e: Exception) {} }

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        private val cur = FloatArray(16)
        private val tgt = FloatArray(16)
        private val LAZ = 0; private val RAZ = 1; private val LAX = 2; private val RAX = 3; private val RLZ = 4
        private val LLX = 5; private val RLX = 6; private val HY = 7; private val HX = 8; private val HZ = 9
        private val HYAW = 10; private val YAW = 11; private val RTW = 12

        private val st9 = HashMap<String, FloatArray>()
        private val evalOut = HashMap<String, FloatArray>()
        private val exprW = HashMap<String, Float>()
        private val hipsOff = FloatArray(3)

        private fun euler9(c: FloatArray): FloatArray =
            M3.mulNew(M3.mulNew(M3.rotX(c[0]), M3.rotY(c[1])), M3.rotZ(c[2]))

        private fun applyAnim(rig: VrmRig, a: VrmAnim, t: Float, dt: Float, snap: Boolean) {
            evalOut.clear()
            a.eval(t, evalOut)
            val k = if (snap) 1f else 1f - exp(-dt * 12f)
            val names = HashSet<String>(st9.keys)
            names.addAll(evalOut.keys)
            hipsOff[0] = 0f; hipsOff[1] = 0f; hipsOff[2] = 0f
            for (name in names) {
                val goal = evalOut[name]
                val c = st9.getOrPut(name) { FloatArray(6) }
                for (i in 0..5) c[i] += ((goal?.get(i) ?: 0f) - c[i]) * k
                if (name.startsWith("expr:")) {
                    exprW[name.substring(5)] = (c[0] / 100f).coerceIn(0f, 1f)
                } else {
                    rig.setLocal(name, euler9(c))
                    if (name == "hips") { hipsOff[0] = c[3]; hipsOff[1] = c[4]; hipsOff[2] = c[5] }
                }
            }
        }

        override fun run() {
            try {
                val m = VrmLoader.load(file)
                initEgl()
                loop(m)
                ChibiView.lastError = ""
            } catch (e: Throwable) {
                ChibiView.lastError = (e.message ?: e.toString()).take(200)
                owner.post { owner.fallback() }
            } finally {
                try {
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        EGL14.eglTerminate(display)
                    }
                } catch (e: Throwable) {}
            }
        }

        private fun initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("EGL init failed")
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            var ok = false
            for (msaa in intArrayOf(4, 0)) {
                val a = if (msaa > 0) intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, msaa, EGL14.EGL_NONE
                ) else intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE
                )
                if (EGL14.eglChooseConfig(display, a, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { ok = true; break }
            }
            if (!ok) throw RuntimeException("no EGL config")
            val cfg = cfgs[0] ?: throw RuntimeException("no EGL config")
            context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw RuntimeException("eglMakeCurrent failed")
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private fun isPot(v: Int) = v > 0 && (v and (v - 1)) == 0

        private fun makeTex(bytes: ByteArray?, color: Int): Int {
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            var bmp: Bitmap? = null
            if (bytes != null) {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var ss = 1
                while (o.outWidth / ss > 512 || o.outHeight / ss > 512) ss *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = ss
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            }
            if (bmp == null) {
                bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(color)
            }
            val pot = isPot(bmp.width) && isPot(bmp.height)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            if (pot) {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            } else {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            bmp.recycle()
            return id[0]
        }

        private fun approach(i: Int, dt: Float) {
            val k = 1f - exp(-dt * 9f)
            cur[i] += (tgt[i] - cur[i]) * k
        }

        private fun idleHead(t: Float) {
            tgt[HX] = 2f * sin(0.9f * t); tgt[HZ] = 2.5f * sin(0.8f * t); tgt[HYAW] = 5f * sin(0.45f * t)
        }

        private fun targets(t: Float) {
            val action = owner.action
            tgt[LAZ] = 45f; tgt[RAZ] = -45f; tgt[LAX] = 0f; tgt[RAX] = 0f; tgt[RLZ] = 0f
            tgt[LLX] = 0f; tgt[RLX] = 0f; tgt[HY] = 0f; tgt[HX] = 0f; tgt[HZ] = 0f; tgt[HYAW] = 0f; tgt[YAW] = 0f; tgt[RTW] = 0f
            when (action) {
                "walk" -> {
                    val ph = t * 6.2832f * 1.7f
                    val s = sin(ph)
                    tgt[LLX] = 28f * s; tgt[RLX] = -28f * s
                    tgt[LAX] = -14f * s; tgt[RAX] = 14f * s
                    tgt[HY] = 0.015f * abs(s); tgt[HZ] = 3f * s
                    tgt[LAZ] = 47f; tgt[RAZ] = -47f
                    tgt[YAW] = owner.yaw
                }
                "run" -> {
                    val ph = t * 6.2832f * 2.8f
                    val s = sin(ph)
                    tgt[LLX] = 40f * s; tgt[RLX] = -40f * s
                    tgt[LAX] = -30f * s; tgt[RAX] = 30f * s
                    tgt[HY] = 0.035f * abs(s); tgt[HZ] = 4f * s
                    tgt[LAZ] = 50f; tgt[RAZ] = -50f
                    tgt[YAW] = owner.yaw
                }
                "sus" -> {
                    tgt[HYAW] = if (sin(t * 1.9f) > 0f) 50f else -50f
                    tgt[HZ] = 4f
                    tgt[HY] = 0.004f * sin(2.4f * t)
                }
                "sit" -> {
                    tgt[HY] = -0.10f; tgt[LLX] = -80f; tgt[RLX] = -80f
                    tgt[LAZ] = 47f; tgt[RAZ] = -47f
                    idleHead(t)
                }
                "wave" -> {
                    tgt[RAZ] = 35f + 8f * sin(t * 6.2832f * 2f)
                    tgt[RLZ] = -10f + 25f * sin(t * 6.2832f * 2.5f)
                    tgt[HZ] = 5f; tgt[RTW] = 90f
                    tgt[HY] = 0.006f * sin(2.4f * t)
                }
                "sleep" -> {
                    tgt[HX] = 24f; tgt[HY] = -0.04f + 0.004f * sin(1.2f * t)
                    tgt[LAZ] = 47f; tgt[RAZ] = -47f
                }
                "drag" -> {
                    tgt[LAZ] = 15f + 8f * sin(7f * t); tgt[RAZ] = -(15f + 8f * sin(7f * t + 1f))
                    tgt[LLX] = 12f * sin(6f * t); tgt[RLX] = -12f * sin(6f * t)
                }
                else -> {
                    tgt[LAZ] = 45f + 3f * sin(1.3f * t); tgt[RAZ] = -(45f + 3f * sin(1.3f * t + 1.2f))
                    tgt[HY] = 0.006f * sin(2.4f * t)
                    idleHead(t)
                }
            }
            if (owner.talking) { tgt[HX] += 4f * sin(t * 16f); tgt[HY] += 0.008f * abs(sin(t * 10f)) }
            if (owner.mood != "neutral" && action != "sleep") tgt[HY] += 0.01f * abs(sin(t * 5f))
        }

        private fun loop(m: VrmModel) {
            val rig = VrmRig(m)
            rig.clear(); rig.hx = 0f; rig.hz = 0f; rig.solve(0f)
            val laP = rig.wpos("leftUpperArm"); val raP = rig.wpos("rightUpperArm")
            val llP = rig.wpos("leftLowerArm"); val rlP = rig.wpos("rightLowerArm")
            val sgn = if (laP != null && raP != null && laP[0] > raP[0]) -1f else 1f
            val yawBase = if (sgn < 0f) 0f else 180f
            fun armAng(a: FloatArray?, b: FloatArray?): Float {
                if (a == null || b == null) return 90f
                return Math.toDegrees(Math.atan2(abs(b[0] - a[0]).toDouble(), (a[1] - b[1]).toDouble())).toFloat()
            }
            val armLow = ((armAng(laP, llP) + armAng(raP, rlP)) / 2f - 45f).coerceIn(0f, 120f)
            var fitCy = 0.56f
            var fitHh = 0.68f
            var fitted = false
            val vc = m.vertexCount

            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, """
                attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUv;
                uniform mat3 uRot; uniform vec4 uProj; uniform vec2 uSc;
                varying vec2 vUv; varying vec3 vN;
                void main() {
                    vec3 p = uRot * aPos; p.xy *= uSc;
                    vUv = aUv; vN = uRot * aNrm;
                    gl_Position = vec4(p.x * uProj.x, (p.y - uProj.z) * uProj.y, -p.z * uProj.w, 1.0);
                }
            """.trimIndent()))
            GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform sampler2D uTex;
                varying vec2 vUv; varying vec3 vN;
                void main() {
                    vec4 tc = texture2D(uTex, vUv); if (tc.a < 0.5) discard; vec3 c = tc.rgb;
                    float l = dot(normalize(vN), normalize(vec3(0.3, 0.6, 0.75)));
                    float sh = mix(0.80, 1.0, smoothstep(-0.1, 0.35, l));
                    gl_FragColor = vec4(c * sh, 1.0);
                }
            """.trimIndent()))
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(prog))
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aNrm = GLES20.glGetAttribLocation(prog, "aNrm")
            val aUv = GLES20.glGetAttribLocation(prog, "aUv")
            val uRot = GLES20.glGetUniformLocation(prog, "uRot")
            val uProj = GLES20.glGetUniformLocation(prog, "uProj")
            val uTex = GLES20.glGetUniformLocation(prog, "uTex")
            val uSc = GLES20.glGetUniformLocation(prog, "uSc")

            val texIds = IntArray(m.indices.size) { makeTex(m.texData[it], m.colors[it]) }
            val idxBufs = m.indices.map {
                val b = ByteBuffer.allocateDirect(it.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                b.put(it); b.position(0); b
            }
            val vdata = FloatArray(vc * 8)
            for (v in 0 until vc) { vdata[v * 8 + 6] = m.uv[v * 2]; vdata[v * 8 + 7] = m.uv[v * 2 + 1] }
            val fb: FloatBuffer = ByteBuffer.allocateDirect(vc * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val curPos = FloatArray(vc * 3)
            val blinkIdx = m.targetNames.indexOfFirst { it.lowercase().contains("blink") || it.equals("Fcl_EYE_Close", true) }
            val tw = FloatArray(m.targets.size)

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 0f)

            val start = SystemClock.uptimeMillis()
            var last = start
            var nextBlink = start + 1500L
            var blinkStart = 0L
            var lw = 0; var lh = 0
            var first = true
            var animFirst = true
            while (running) {
                val now = SystemClock.uptimeMillis()
                val t = (now - start) / 1000f
                val dt = ((now - last) / 1000f).coerceIn(0f, 0.1f)
                last = now
                val w = this@GlView.width; val h = this@GlView.height
                if (w < 2 || h < 2) { sleep(50); continue }
                if (w != lw || h != lh) { GLES20.glViewport(0, 0, w, h); lw = w; lh = h }

                targets(t)
                if (first) { for (i in cur.indices) cur[i] = tgt[i]; first = false }
                else for (i in 0..12) approach(i, dt)

                rig.clear()
                Anims.ensure(this@GlView.context)
                val anim = Anims.get(owner.action) ?: Anims.get("idle")
                var hipsY = cur[HY]
                rig.hx = 0f; rig.hz = 0f
                if (anim != null) {
                    applyAnim(rig, anim, t, dt, animFirst)
                    animFirst = false
                    hipsY = hipsOff[1]; rig.hx = hipsOff[0]; rig.hz = hipsOff[2]
                } else {
                    animFirst = true
                    if (st9.isNotEmpty()) { st9.clear(); exprW.clear() }
                rig.setDelta("leftUpperArm", M3.mulNew(M3.rotX(cur[LAX]), M3.rotZ(sgn * (cur[LAZ] - 45f + armLow))))
                rig.setDelta("rightUpperArm", M3.mulNew(M3.mulNew(M3.rotX(cur[RAX]), M3.rotZ(sgn * (cur[RAZ] + 45f - armLow))), M3.rotX(cur[RTW])))
                rig.setDelta("rightLowerArm", M3.rotZ(sgn * cur[RLZ]))
                rig.setDelta("leftUpperLeg", M3.rotX(cur[LLX] * sgn))
                rig.setDelta("rightUpperLeg", M3.rotX(cur[RLX] * sgn))
                rig.setDelta("head", M3.mulNew(M3.rotZ(sgn * cur[HZ]), M3.mulNew(M3.rotY(cur[HYAW]), M3.rotX(cur[HX] * sgn))))
                }
                rig.solve(hipsY)

                if (now >= nextBlink && blinkStart == 0L) { blinkStart = now }
                var bw = 0f
                if (blinkStart != 0L) {
                    val x = (now - blinkStart) / 160f
                    if (x >= 1f) { blinkStart = 0L; nextBlink = now + Random.nextLong(2500L, 5500L) }
                    else bw = 1f - abs(2f * x - 1f)
                }
                if (owner.action == "sleep") bw = 1f

                java.util.Arrays.fill(tw, 0f)
                fun addExpr(name: String, v: Float) {
                    if (v <= 0.001f) return
                    val binds = m.exprs[name]
                    if (binds != null) {
                        for (bd in binds) if (bd.first in tw.indices) tw[bd.first] += v * bd.second
                    } else if (name == "blink" && blinkIdx >= 0 && blinkIdx < tw.size) tw[blinkIdx] += v
                }
                var blinkW = bw
                val ab = exprW["blink"]
                if (ab != null && ab > blinkW) blinkW = ab
                addExpr("blink", blinkW)
                for ((en, ev) in exprW) if (en != "blink") addExpr(en, ev)
                if (owner.talking) addExpr("aa", 0.55f * abs(sin(t * 11f)))
                val mex = when (owner.mood) {
                    "happy", "excited", "love" -> "happy"
                    "sad" -> "sad"
                    "angry" -> "angry"
                    "surprised", "shocked" -> "surprised"
                    "relaxed", "calm" -> "relaxed"
                    else -> ""
                }
                if (mex.isNotEmpty() && owner.action != "sleep" && !exprW.containsKey(mex)) addExpr(mex, 0.7f)
                System.arraycopy(m.pos, 0, curPos, 0, curPos.size)
                for (ti in tw.indices) {
                    val wv = tw[ti].coerceIn(0f, 1f)
                    if (wv > 0.001f) {
                        val d = m.targets[ti]
                        for (i in curPos.indices) curPos[i] += d[i] * wv
                    }
                }

                val jm = rig.jointMats
                for (v in 0 until vc) {
                    val px = curPos[v * 3]; val py = curPos[v * 3 + 1]; val pz = curPos[v * 3 + 2]
                    val qx = m.nrm[v * 3]; val qy = m.nrm[v * 3 + 1]; val qz = m.nrm[v * 3 + 2]
                    var ox = 0f; var oy = 0f; var oz = 0f; var nx = 0f; var ny = 0f; var nz = 0f
                    for (s in 0..3) {
                        val wgt = m.weights[v * 4 + s]
                        if (wgt == 0f) continue
                        val o = m.jointIdx[v * 4 + s] * 12
                        ox += wgt * (jm[o] * px + jm[o + 1] * py + jm[o + 2] * pz + jm[o + 3])
                        oy += wgt * (jm[o + 4] * px + jm[o + 5] * py + jm[o + 6] * pz + jm[o + 7])
                        oz += wgt * (jm[o + 8] * px + jm[o + 9] * py + jm[o + 10] * pz + jm[o + 11])
                        nx += wgt * (jm[o] * qx + jm[o + 1] * qy + jm[o + 2] * qz)
                        ny += wgt * (jm[o + 4] * qx + jm[o + 5] * qy + jm[o + 6] * qz)
                        nz += wgt * (jm[o + 8] * qx + jm[o + 9] * qy + jm[o + 10] * qz)
                    }
                    val b = v * 8
                    vdata[b] = ox; vdata[b + 1] = oy; vdata[b + 2] = oz
                    vdata[b + 3] = nx; vdata[b + 4] = ny; vdata[b + 5] = nz
                }
                if (!fitted) {
                    var lo = 1e9f; var hi = -1e9f
                    for (v in 0 until vc) { val y = vdata[v * 8 + 1]; if (y < lo) lo = y; if (y > hi) hi = y }
                    if (hi - lo > 0.05f) { fitCy = (lo + hi) / 2f; fitHh = (hi - lo) / 2f * 1.21f }
                    fitted = true
                }
                fb.position(0); fb.put(vdata); fb.position(0)

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                GLES20.glUseProgram(prog)
                val r = M3.rotY(cur[YAW] + yawBase)
                val rc = floatArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
                GLES20.glUniformMatrix3fv(uRot, 1, false, rc, 0)
                val hh = fitHh
                val hw = hh * w / h
                GLES20.glUniform4f(uProj, 1f / hw, 1f / hh, fitCy, 0.5f)
                GLES20.glUniform2f(uSc, owner.vsx, owner.vsy)
                fb.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, fb)
                fb.position(3); GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 32, fb)
                fb.position(6); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 32, fb)
                GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aNrm); GLES20.glEnableVertexAttribArray(aUv)
                for (pi in idxBufs.indices) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[pi])
                    GLES20.glUniform1i(uTex, 0)
                    val ib: ShortBuffer = idxBufs[pi]
                    ib.position(0)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indices[pi].size, GLES20.GL_UNSIGNED_SHORT, ib)
                }
                if (!EGL14.eglSwapBuffers(display, surface)) break

                val spent = SystemClock.uptimeMillis() - now
                if (spent < 33) sleep(33 - spent)
            }
        }
    }
}
