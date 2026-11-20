package com.sahil.chibi

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/** Invisible helper: opens the gallery, the file picker or the camera for the chat (a Service cannot wait for a result). */
class AttachActivity : Activity() {
    private var camUri: Uri? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { started = true; camUri = savedInstanceState.getString("cam")?.let { Uri.parse(it) } }
        if (started) return
        started = true
        try {
            when (intent.getStringExtra("mode")) {
                "camera" -> {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "nana_" + System.currentTimeMillis() + ".jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nana")
                    }
                    camUri = try { contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) } catch (e: Exception) { null }
                    val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    camUri?.let { i.putExtra(MediaStore.EXTRA_OUTPUT, it); i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    startActivityForResult(i, 3)
                }
                "file" -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 2)
                else -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open that: " + (e.message ?: "no app found"), Toast.LENGTH_LONG).show()
            done(emptyList())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        camUri?.let { outState.putString("cam", it.toString()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val ok = resultCode == RESULT_OK
        val cam = camUri
        if (requestCode == 3 && !ok && cam != null) { try { contentResolver.delete(cam, null, null) } catch (e: Exception) {} }
        Thread {
            val out = ArrayList<Att>()
            try {
                val uris = ArrayList<Uri>()
                if (ok) {
                    if (requestCode == 3) {
                        if (cam != null) uris.add(cam)
                        else {
                            val bm = data?.extras?.get("data") as? Bitmap
                            if (bm != null) {
                                val f = File(Attach.dir(this), System.currentTimeMillis().toString() + "_photo.jpg")
                                f.outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                out.add(Att(f, "photo.jpg", "image/jpeg", f.length()).also { it.thumb = Attach.thumb(f) })
                            }
                        }
                    } else {
                        val cd = data?.clipData
                        if (cd != null) for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
                        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                    }
                }
                for (u in uris.take(6)) Attach.copyIn(this, u)?.let { out.add(it) }
            } catch (e: Exception) {}
            runOnUiThread { done(out) }
        }.start()
    }

    private fun done(atts: List<Att>) {
        try {
            startForegroundService(Attach.putExtras(Intent(this, CompanionService::class.java).setAction(CompanionService.ACT_ATTACH), atts))
        } catch (e: Exception) {}
        finish()
    }
}
