package com.sahil.chibi

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs

/** Nana's own camera: live preview, shutter, flip, and a Retake / Use photo review. */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var tv: TextureView
    private lateinit var root: FrameLayout
    private lateinit var review: ImageView
    private lateinit var shutter: View
    private lateinit var flip: TextView
    private lateinit var reviewRow: LinearLayout
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewReq: CaptureRequest? = null
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var front = false
    private var sensor = 90
    private var opening = false
    private var active = false
    private var shot: File? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { setColor(0x88000000.toInt()); cornerRadius = dp(22).toFloat() }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this).apply { surfaceTextureListener = this@CameraActivity }
        root.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        review = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(review, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(pill("✕") { finishWith(emptyList()) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dp(36); leftMargin = dp(16) })

        shutter = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), 0x88FFFFFF.toInt()) }
            setOnClickListener { capture() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(74), dp(74), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        flip = pill("Flip") { front = !front; closeCam(); openCam() }
        root.addView(flip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(52); rightMargin = dp(20) })

        reviewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        reviewRow.addView(pill("Retake") { retake() })
        reviewRow.addView(pill("Use photo") { usePhoto() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
        root.addView(reviewRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        active = true
        thread = HandlerThread("nana-cam").also { it.start(); h = Handler(it.looper) }
        if (tv.isAvailable) openCam()
    }

    override fun onPause() {
        active = false
        closeCam()
        thread?.quitSafely(); thread = null; h = null
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (review.visibility == View.VISIBLE) retake() else finishWith(emptyList())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) { if (active && tv.isAvailable) openCam() }
        else { Toast.makeText(this, "Camera permission is needed to take a photo", Toast.LENGTH_LONG).show(); finishWith(emptyList()) }
    }

    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, hh: Int) { if (active) openCam() }
    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, hh: Int) {}
    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}

    private fun closeCam() {
        try { session?.close() } catch (e: Exception) {}
        try { cam?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        session = null; cam = null; reader = null; previewReq = null; opening = false
    }

    private fun openCam() {
        if (opening || cam != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7); return
        }
        val th = h ?: return
        opening = true
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull() ?: throw RuntimeException("no camera found")
            val ch = cm.getCameraCharacteristics(id)
            sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw RuntimeException("camera has no stream map")
            val jpegs = map.getOutputSizes(ImageFormat.JPEG)
            val js = jpegs.filter { it.width * it.height <= 8_000_000 }.maxByOrNull { it.width * it.height } ?: jpegs.first()
            val ratio = js.width.toFloat() / js.height
            val prevs = map.getOutputSizes(SurfaceTexture::class.java)
            val ps = prevs.filter { it.width <= 1920 && abs(it.width.toFloat() / it.height - ratio) < 0.02f }.maxByOrNull { it.width * it.height }
                ?: prevs.minByOrNull { abs(it.width.toFloat() / it.height - ratio) }!!
            val swap = sensor % 180 == 90
            val pw = if (swap) ps.height else ps.width
            val ph = if (swap) ps.width else ps.height
            val vw = resources.displayMetrics.widthPixels
            val vh = vw * ph / pw
            tv.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
            val rd = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    var f: File? = null
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        f = File(Attach.dir(this), System.currentTimeMillis().toString() + "_photo.jpg")
                        f.writeBytes(bytes)
                    } catch (e: Exception) { f = null } finally { img.close() }
                    val ff = f
                    runOnUiThread { if (ff != null) showReview(ff) else Toast.makeText(this, "Could not save the photo", Toast.LENGTH_SHORT).show() }
                }
            }, th)
            reader = rd
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (!active) { c.close(); opening = false; return }
                    cam = c; opening = false
                    startPreview(c, ps)
                }
                override fun onDisconnected(c: CameraDevice) { c.close(); cam = null; opening = false }
                override fun onError(c: CameraDevice, error: Int) {
                    c.close(); cam = null; opening = false
                    runOnUiThread { Toast.makeText(this@CameraActivity, "Camera error $error", Toast.LENGTH_LONG).show() }
                }
            }, th)
        } catch (e: Exception) {
            opening = false
            Toast.makeText(this, "Camera did not open: " + (e.message ?: e.toString()).take(100), Toast.LENGTH_LONG).show()
            finishWith(emptyList())
        }
    }

    private fun startPreview(c: CameraDevice, ps: android.util.Size) {
        try {
            val st = tv.surfaceTexture ?: return
            st.setDefaultBufferSize(ps.width, ps.height)
            val surf = Surface(st)
            val rb = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            rb.addTarget(surf)
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            val rd = reader ?: return
            @Suppress("DEPRECATION")
            c.createCaptureSession(listOf(surf, rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cam == null) return
                    session = s
                    previewReq = rb.build()
                    try { s.setRepeatingRequest(previewReq!!, null, h) } catch (e: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@CameraActivity, "Camera preview failed", Toast.LENGTH_LONG).show() }
                }
            }, h)
        } catch (e: Exception) {}
    }

    private fun capture() {
        val c = cam ?: return
        val s = session ?: return
        val rd = reader ?: return
        try {
            val rb = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            rb.addTarget(rd.surface)
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            rb.set(CaptureRequest.JPEG_ORIENTATION, sensor)
            rb.set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            s.capture(rb.build(), null, h)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not take the photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReview(f: File) {
        shot = f
        try { session?.stopRepeating() } catch (e: Exception) {}
        review.setImageBitmap(Attach.decode(f, 1600))
        review.visibility = View.VISIBLE
        reviewRow.visibility = View.VISIBLE
        shutter.visibility = View.GONE; flip.visibility = View.GONE
    }

    private fun retake() {
        shot?.delete(); shot = null
        review.visibility = View.GONE; reviewRow.visibility = View.GONE
        shutter.visibility = View.VISIBLE; flip.visibility = View.VISIBLE
        try { previewReq?.let { session?.setRepeatingRequest(it, null, h) } } catch (e: Exception) {}
    }

    private fun usePhoto() {
        val f = shot ?: return
        finishWith(listOf(Att(f, "photo.jpg", "image/jpeg", f.length())))
    }

    private fun finishWith(atts: List<Att>) {
        try {
            startForegroundService(Attach.putExtras(Intent(this, CompanionService::class.java).setAction(CompanionService.ACT_ATTACH), atts))
        } catch (e: Exception) {}
        finish()
    }
}
