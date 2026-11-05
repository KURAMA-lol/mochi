package com.sahil.chibi

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.cos

class AnimKey(val t: Float, val r: FloatArray, val p: FloatArray)

/** Animation made in Pixel Studio. Tracks are bone names (euler degrees in r, meters in p) or "expr:<name>" (expression weight 0..100 in r[0]). */
class VrmAnim(val name: String, val loop: Float, val tracks: Map<String, List<AnimKey>>) {
    /** out[track] = [rx, ry, rz, px, py, pz] */
    fun eval(time: Float, out: HashMap<String, FloatArray>) {
        val lp = if (loop > 0.01f) loop else 2f
        var t = time % lp
        if (t < 0f) t += lp
        for ((tn, keys) in tracks) {
            val n = keys.size
            if (n == 0) continue
            val o = FloatArray(6)
            if (n == 1) {
                System.arraycopy(keys[0].r, 0, o, 0, 3)
                System.arraycopy(keys[0].p, 0, o, 3, 3)
            } else {
                val a: AnimKey
                val b: AnimKey
                var u: Float
                if (t < keys[0].t) {
                    a = keys[n - 1]; b = keys[0]
                    val sp = (b.t + lp) - a.t
                    u = if (sp > 0f) ((t + lp) - a.t) / sp else 0f
                } else if (t >= keys[n - 1].t) {
                    a = keys[n - 1]; b = keys[0]
                    val sp = (b.t + lp) - a.t
                    u = if (sp > 0f) (t - a.t) / sp else 0f
                } else {
                    var i = 0
                    while (i < n - 2 && keys[i + 1].t <= t) i++
                    a = keys[i]; b = keys[i + 1]
                    val d = b.t - a.t
                    u = (t - a.t) / (if (d == 0f) 1f else d)
                }
                u = u.coerceIn(0f, 1f)
                u = (1f - cos(Math.PI * u).toFloat()) / 2f
                for (k in 0..2) {
                    o[k] = a.r[k] + (b.r[k] - a.r[k]) * u
                    o[3 + k] = a.p[k] + (b.p[k] - a.p[k]) * u
                }
            }
            out[tn] = o
        }
    }
}

/** Animations the user assigned to actions (idle, walk, ...) from Pixel Studio. Stored in filesDir/anims. */
object Anims {
    private val map = HashMap<String, VrmAnim>()
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "anims")
    private fun clean(a: String) = a.lowercase().replace(Regex("[^a-z0-9_]"), "")

    @Synchronized fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val fs = dir(ctx).listFiles() ?: return
        for (f in fs) {
            if (!f.name.endsWith(".json")) continue
            try {
                val a = parse(f.readText())
                if (a != null) map[f.name.removeSuffix(".json")] = a
            } catch (e: Throwable) {
            }
        }
    }

    @Synchronized fun get(action: String): VrmAnim? = map[action]

    @Synchronized fun put(ctx: Context, action: String, json: String): Boolean {
        ensure(ctx)
        val key = clean(action)
        if (key.isEmpty()) return false
        val a = parse(json) ?: return false
        val d = dir(ctx)
        d.mkdirs()
        File(d, "$key.json").writeText(json)
        map[key] = a
        return true
    }

    @Synchronized fun remove(ctx: Context, action: String) {
        ensure(ctx)
        val key = clean(action)
        File(dir(ctx), "$key.json").delete()
        map.remove(key)
    }

    @Synchronized fun list(ctx: Context): String {
        ensure(ctx)
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v.name)
        return o.toString()
    }

    fun parse(json: String): VrmAnim? {
        val o = JSONObject(json)
        val tr = o.optJSONObject("tracks") ?: return null
        val tracks = HashMap<String, List<AnimKey>>()
        val ks = tr.keys()
        while (ks.hasNext()) {
            val name = ks.next()
            val arr = tr.getJSONArray(name)
            val list = ArrayList<AnimKey>()
            for (i in 0 until arr.length()) {
                val k = arr.getJSONObject(i)
                val r = FloatArray(3)
                val p = FloatArray(3)
                val ra = k.optJSONArray("r")
                val pa = k.optJSONArray("p")
                for (c in 0..2) {
                    r[c] = (ra?.optDouble(c, 0.0) ?: 0.0).toFloat()
                    p[c] = (pa?.optDouble(c, 0.0) ?: 0.0).toFloat()
                }
                list.add(AnimKey(k.optDouble("t", 0.0).toFloat(), r, p))
            }
            list.sortBy { it.t }
            tracks[name] = list
        }
        return VrmAnim(o.optString("name", ""), o.optDouble("loop", 2.0).toFloat(), tracks)
    }
}
