package com.sahil.chibi

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Nana's own gallery: recent pictures in a grid, tap to select (up to 6), Send. */
class GalleryActivity : Activity() {
    private val ids = ArrayList<Long>()
    private val sel = LinkedHashSet<Long>()
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = android.util.LruCache<Long, Bitmap>(160)
    private lateinit var grid: GridView
    private lateinit var sendBtn: TextView
    private lateinit var head: TextView
    private val adapter = object : BaseAdapter() {
        override fun getCount() = ids.size
        override fun getItem(position: Int): Any = ids[position]
        override fun getItemId(position: Int): Long = ids[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? FrameLayout ?: object : FrameLayout(this@GalleryActivity) {
                override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
            }.also { c ->
                c.addView(ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                c.addView(TextView(this@GalleryActivity).apply {
                    text = "✓"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF7C5CFF.toInt()) }
                    visibility = View.GONE
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
            }
            val iv = cell.getChildAt(0) as ImageView
            val tick = cell.getChildAt(1) as TextView
            val id = ids[position]
            iv.tag = id
            val c = cache.get(id)
            if (c != null) iv.setImageBitmap(c) else {
                iv.setImageDrawable(null); iv.setBackgroundColor(0xFF222222.toInt())
                pool.execute {
                    val b = thumbFor(id)
                    if (b != null) { cache.put(id, b); iv.post { if (iv.tag == id) iv.setImageBitmap(b) } }
                }
            }
            val on = sel.contains(id)
            tick.visibility = if (on) View.VISIBLE else View.GONE
            iv.alpha = if (on) 0.6f else 1f
            return cell
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    @Suppress("DEPRECATION")
    private fun thumbFor(id: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uriOf(id), android.util.Size(300, 300), null)
        else MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    } catch (e: Exception) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(0, dp(30), 0, 0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        top.addView(TextView(this).apply { text = "✕"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(6), dp(6), dp(14), dp(6)); setOnClickListener { finishWith(emptyList()) } })
        head = TextView(this).apply { text = "Pick photos"; textSize = 17f; setTextColor(Color.WHITE) }
        top.addView(head, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)
        grid = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(2); verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            this.adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(ids[pos]) }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        sendBtn = TextView(this).apply {
            text = "Send"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(0xFF7C5CFF.toInt()); cornerRadius = dp(24).toFloat() }
            alpha = 0.4f
            setOnClickListener { send() }
        }
        root.addView(sendBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        setContentView(root)

        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) load() else requestPermissions(arrayOf(perm), 5)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) load()
        else {
            // no access to all photos: use the system picker instead
            try { startActivity(Intent(this, AttachActivity::class.java).putExtra("mode", "gallery")) } catch (e: Exception) {}
            finish()
        }
    }

    private fun load() {
        Thread {
            val list = ArrayList<Long>()
            try {
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext() && list.size < 400) list.add(c.getLong(0))
                }
            } catch (e: Exception) {}
            runOnUiThread {
                ids.clear(); ids.addAll(list); adapter.notifyDataSetChanged()
                if (list.isEmpty()) head.text = "No pictures found"
            }
        }.start()
    }

    private fun toggle(id: Long) {
        if (!sel.remove(id)) {
            if (sel.size >= 6) { Toast.makeText(this, "Up to 6 at a time", Toast.LENGTH_SHORT).show(); return }
            sel.add(id)
        }
        adapter.notifyDataSetChanged()
        sendBtn.text = if (sel.isEmpty()) "Send" else "Send (${sel.size})"
        sendBtn.alpha = if (sel.isEmpty()) 0.4f else 1f
    }

    private fun send() {
        if (sel.isEmpty()) return
        sendBtn.isEnabled = false; sendBtn.text = "Sending..."
        val picked = sel.toList()
        Thread {
            val out = ArrayList<Att>()
            for (id in picked) Attach.copyIn(this, uriOf(id))?.let { out.add(it) }
            runOnUiThread { finishWith(out) }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishWith(emptyList()) }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun finishWith(atts: List<Att>) {
        try {
            startForegroundService(Attach.putExtras(Intent(this, CompanionService::class.java).setAction(CompanionService.ACT_ATTACH), atts))
        } catch (e: Exception) {}
        finish()
    }
}
