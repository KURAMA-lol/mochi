package com.sahil.chibi

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** Plain-data VRM/GLB model (CPU side). Only what the overlay renderer needs. */
class VrmModel(
    val vertexCount: Int,
    val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray,
    val jointIdx: IntArray, val weights: FloatArray,
    val indices: List<ShortArray>, val texData: List<ByteArray?>,
    val targets: List<FloatArray>, val targetNames: List<String>,
    val parent: IntArray, val restT: FloatArray, val restR: FloatArray,
    val order: IntArray, val joints: IntArray, val ibm: FloatArray,
    val bones: Map<String, Int>,
    val exprs: Map<String, List<Pair<Int, Float>>>
)

object VrmLoader {
    fun load(f: File): VrmModel {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 20 || bb.getInt(0) != 0x46546C67) throw IllegalArgumentException("not a .vrm / .glb file")
        var json: JSONObject? = null
        var binOff = -1
        var off = 12
        while (off + 8 <= bytes.size) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            if (type == 0x4E4F534A) json = JSONObject(String(bytes, off + 8, len, Charsets.UTF_8))
            else if (type == 0x004E4942) binOff = off + 8
            off += 8 + len
        }
        val j = json ?: throw IllegalArgumentException("no JSON chunk")
        if (binOff < 0) throw IllegalArgumentException("no BIN chunk")
        val accessors = j.getJSONArray("accessors")
        val views = j.getJSONArray("bufferViews")

        fun readF(i: Int): FloatArray {
            val a = accessors.getJSONObject(i)
            val bv = views.getJSONObject(a.getInt("bufferView"))
            val cnt = a.getInt("count")
            val ct = a.getInt("componentType")
            val n = when (a.getString("type")) {
                "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
                else -> throw IllegalArgumentException("accessor type")
            }
            val csz = when (ct) { 5126, 5125 -> 4; 5123 -> 2; 5121 -> 1; else -> throw IllegalArgumentException("component type $ct") }
            var stride = bv.optInt("byteStride", 0)
            if (stride == 0) stride = n * csz
            val base = binOff + bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val out = FloatArray(cnt * n)
            for (k in 0 until cnt) for (c in 0 until n) {
                val p = base + k * stride + c * csz
                out[k * n + c] = when (ct) {
                    5126 -> bb.getFloat(p)
                    5123 -> (bb.getShort(p).toInt() and 0xFFFF).toFloat()
                    5125 -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    else -> (bb.get(p).toInt() and 0xFF).toFloat()
                }
            }
            return out
        }

        fun imageBytes(img: Int): ByteArray? {
            val im = j.getJSONArray("images").getJSONObject(img)
            if (!im.has("bufferView")) return null
            val bv = views.getJSONObject(im.getInt("bufferView"))
            val s = binOff + bv.optInt("byteOffset", 0)
            return bytes.copyOfRange(s, s + bv.getInt("byteLength"))
        }

        val mesh = j.getJSONArray("meshes").getJSONObject(0)
        val prims = mesh.getJSONArray("primitives")
        val at = prims.getJSONObject(0).getJSONObject("attributes")
        val pos = readF(at.getInt("POSITION"))
        val vc = pos.size / 3
        val nrm = if (at.has("NORMAL")) readF(at.getInt("NORMAL")) else FloatArray(vc * 3)
        val uv = if (at.has("TEXCOORD_0")) readF(at.getInt("TEXCOORD_0")) else FloatArray(vc * 2)
        if (!at.has("JOINTS_0") || !at.has("WEIGHTS_0")) throw IllegalArgumentException("model has no skin weights")
        val jf = readF(at.getInt("JOINTS_0"))
        val jointIdx = IntArray(jf.size) { jf[it].toInt() }
        val weights = readF(at.getInt("WEIGHTS_0"))
        if (vc > 65000) throw IllegalArgumentException("model too big ($vc vertices)")

        val indices = ArrayList<ShortArray>()
        val texData = ArrayList<ByteArray?>()
        for (pi in 0 until prims.length()) {
            val p = prims.getJSONObject(pi)
            if (p.getJSONObject("attributes").getInt("POSITION") != at.getInt("POSITION"))
                throw IllegalArgumentException("meshes use different vertex sets")
            val ia = readF(p.getInt("indices"))
            indices.add(ShortArray(ia.size) { ia[it].toInt().toShort() })
            var tb: ByteArray? = null
            val mats = j.optJSONArray("materials")
            if (mats != null && p.has("material")) {
                val bct = mats.getJSONObject(p.getInt("material")).optJSONObject("pbrMetallicRoughness")?.optJSONObject("baseColorTexture")
                if (bct != null) {
                    val src = j.getJSONArray("textures").getJSONObject(bct.getInt("index")).getInt("source")
                    tb = imageBytes(src)
                }
            }
            texData.add(tb)
        }

        val targets = ArrayList<FloatArray>()
        val tj = prims.getJSONObject(0).optJSONArray("targets")
        if (tj != null) for (k in 0 until tj.length()) targets.add(readF(tj.getJSONObject(k).getInt("POSITION")))
        val names = ArrayList<String>()
        val tn = mesh.optJSONObject("extras")?.optJSONArray("targetNames")
        if (tn != null) for (k in 0 until tn.length()) names.add(tn.getString(k))

        val nodes = j.getJSONArray("nodes")
        val nn = nodes.length()
        val parent = IntArray(nn) { -1 }
        val restT = FloatArray(nn * 3)
        val restR = FloatArray(nn * 9)
        for (i in 0 until nn) {
            val nd = nodes.getJSONObject(i)
            restR[i * 9] = 1f; restR[i * 9 + 4] = 1f; restR[i * 9 + 8] = 1f
            val t = nd.optJSONArray("translation")
            if (t != null) for (k in 0..2) restT[i * 3 + k] = t.getDouble(k).toFloat()
            val q = nd.optJSONArray("rotation")
            if (q != null) {
                val x = q.getDouble(0).toFloat(); val y = q.getDouble(1).toFloat()
                val z = q.getDouble(2).toFloat(); val w = q.getDouble(3).toFloat()
                restR[i * 9 + 0] = 1 - 2 * (y * y + z * z); restR[i * 9 + 1] = 2 * (x * y - z * w); restR[i * 9 + 2] = 2 * (x * z + y * w)
                restR[i * 9 + 3] = 2 * (x * y + z * w); restR[i * 9 + 4] = 1 - 2 * (x * x + z * z); restR[i * 9 + 5] = 2 * (y * z - x * w)
                restR[i * 9 + 6] = 2 * (x * z - y * w); restR[i * 9 + 7] = 2 * (y * z + x * w); restR[i * 9 + 8] = 1 - 2 * (x * x + y * y)
            }
            val ch = nd.optJSONArray("children")
            if (ch != null) for (k in 0 until ch.length()) parent[ch.getInt(k)] = i
        }
        val order = ArrayList<Int>()
        fun walk(i: Int) {
            order.add(i)
            val ch = nodes.getJSONObject(i).optJSONArray("children")
            if (ch != null) for (k in 0 until ch.length()) walk(ch.getInt(k))
        }
        val scene = j.getJSONArray("scenes").getJSONObject(j.optInt("scene", 0)).getJSONArray("nodes")
        for (k in 0 until scene.length()) walk(scene.getInt(k))

        val skin = j.getJSONArray("skins").getJSONObject(0)
        val sj = skin.getJSONArray("joints")
        val joints = IntArray(sj.length()) { sj.getInt(it) }
        val ibm = readF(skin.getInt("inverseBindMatrices"))

        val bones = HashMap<String, Int>()
        val hb = j.optJSONObject("extensions")?.optJSONObject("VRM")?.optJSONObject("humanoid")?.optJSONArray("humanBones")
        if (hb != null) for (k in 0 until hb.length()) {
            val o = hb.getJSONObject(k)
            bones[o.getString("bone")] = o.getInt("node")
        }
        if (bones.isEmpty()) {
            val h1 = j.optJSONObject("extensions")?.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")?.optJSONObject("humanBones")
            if (h1 != null) {
                val hk = h1.keys()
                while (hk.hasNext()) {
                    val k = hk.next()
                    bones[k] = h1.getJSONObject(k).getInt("node")
                }
            }
        }
        if (!bones.containsKey("hips") || !bones.containsKey("head")) throw IllegalArgumentException("not a humanoid VRM")

        val exprs = HashMap<String, List<Pair<Int, Float>>>()
        fun alias(n: String): String = when (n.lowercase()) {
            "joy" -> "happy"; "sorrow" -> "sad"; "fun" -> "relaxed"
            "a" -> "aa"; "i" -> "ih"; "u" -> "ou"; "e" -> "ee"; "o" -> "oh"
            "blink_l" -> "blinkLeft"; "blink_r" -> "blinkRight"
            else -> n
        }
        val ext = j.optJSONObject("extensions")
        val groups = ext?.optJSONObject("VRM")?.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")
        if (groups != null) for (gi in 0 until groups.length()) {
            val gr = groups.getJSONObject(gi)
            var nm = gr.optString("presetName", "")
            if (nm.isEmpty() || nm == "unknown") nm = gr.optString("name", "")
            if (nm.isEmpty()) continue
            val binds = gr.optJSONArray("binds") ?: continue
            val l = ArrayList<Pair<Int, Float>>()
            for (bi in 0 until binds.length()) {
                val b = binds.getJSONObject(bi)
                if (b.optInt("mesh", 0) == 0) l.add(Pair(b.getInt("index"), b.optDouble("weight", 100.0).toFloat() / 100f))
            }
            if (l.isNotEmpty()) exprs[alias(nm)] = l
        }
        val pre = ext?.optJSONObject("VRMC_vrm")?.optJSONObject("expressions")?.optJSONObject("preset")
        if (pre != null) {
            val pk = pre.keys()
            while (pk.hasNext()) {
                val k = pk.next()
                val binds = pre.getJSONObject(k).optJSONArray("morphTargetBinds") ?: continue
                val l = ArrayList<Pair<Int, Float>>()
                for (bi in 0 until binds.length()) {
                    val b = binds.getJSONObject(bi)
                    if (nodes.getJSONObject(b.getInt("node")).optInt("mesh", -1) == 0) l.add(Pair(b.getInt("index"), b.optDouble("weight", 1.0).toFloat()))
                }
                if (l.isNotEmpty()) exprs[k] = l
            }
        }

        return VrmModel(vc, pos, nrm, uv, jointIdx, weights, indices, texData, targets, names,
            parent, restT, restR, order.toIntArray(), joints, ibm, bones, exprs)
    }
}

/** 3x3 row-major rotation helpers (degrees). */
object M3 {
    fun rotX(d: Float): FloatArray { val a = Math.toRadians(d.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c) }
    fun rotY(d: Float): FloatArray { val a = Math.toRadians(d.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c) }
    fun rotZ(d: Float): FloatArray { val a = Math.toRadians(d.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f) }
    fun mul(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
        for (r in 0..2) for (c in 0..2)
            out[oo + r * 3 + c] = a[ao + r * 3] * b[bo + c] + a[ao + r * 3 + 1] * b[bo + 3 + c] + a[ao + r * 3 + 2] * b[bo + 6 + c]
    }
    fun mulT(a: FloatArray, ao: Int, b: FloatArray, bo: Int, out: FloatArray, oo: Int) {
        for (r in 0..2) for (c in 0..2)
            out[oo + r * 3 + c] = a[ao + r] * b[bo + c] + a[ao + 3 + r] * b[bo + 3 + c] + a[ao + 6 + r] * b[bo + 6 + c]
    }
    fun mulNew(a: FloatArray, b: FloatArray): FloatArray { val o = FloatArray(9); mul(a, 0, b, 0, o, 0); return o }
}

/** Skeleton posing: world-axis rotation deltas per humanoid bone -> skinning matrices. */
class VrmRig(private val m: VrmModel) {
    private val n = m.parent.size
    private val wr = FloatArray(n * 9)
    private val wt = FloatArray(n * 3)
    private val delta = FloatArray(n * 9)
    private val hasDelta = BooleanArray(n)
    private val localR = FloatArray(n * 9)
    private val hasLocal = BooleanArray(n)
    var hx = 0f
    var hz = 0f
    private val hips = m.bones["hips"] ?: -1
    val jointMats = FloatArray(m.joints.size * 12)

    fun clear() { java.util.Arrays.fill(hasDelta, false); java.util.Arrays.fill(hasLocal, false) }

    fun setDelta(bone: String, r: FloatArray) {
        val i = m.bones[bone] ?: return
        System.arraycopy(r, 0, delta, i * 9, 9)
        hasDelta[i] = true
    }

    /** Local-axis rotation applied after the rest rotation (what Pixel Studio animations use). */
    fun setLocal(bone: String, r: FloatArray) {
        val i = m.bones[bone] ?: return
        System.arraycopy(r, 0, localR, i * 9, 9)
        hasLocal[i] = true
    }

    fun solve(hipsDy: Float) {
        val l = FloatArray(9); val t1 = FloatArray(9); val t2 = FloatArray(9)
        for (i in m.order) {
            val p = m.parent[i]
            System.arraycopy(m.restR, i * 9, l, 0, 9)
            if (hasLocal[i]) {
                M3.mul(m.restR, i * 9, localR, i * 9, t1, 0)
                System.arraycopy(t1, 0, l, 0, 9)
            } else if (hasDelta[i]) {
                if (p >= 0) {
                    M3.mul(delta, i * 9, wr, p * 9, t1, 0)
                    M3.mulT(wr, p * 9, t1, 0, t2, 0)
                    M3.mul(t2, 0, l, 0, t1, 0)
                } else {
                    M3.mul(delta, i * 9, l, 0, t1, 0)
                }
                System.arraycopy(t1, 0, l, 0, 9)
            }
            var tx = m.restT[i * 3]
            var ty = m.restT[i * 3 + 1]
            var tz = m.restT[i * 3 + 2]
            if (i == hips) { tx += hx; ty += hipsDy; tz += hz }
            if (p < 0) {
                System.arraycopy(l, 0, wr, i * 9, 9)
                wt[i * 3] = tx; wt[i * 3 + 1] = ty; wt[i * 3 + 2] = tz
            } else {
                M3.mul(wr, p * 9, l, 0, wr, i * 9)
                for (r in 0..2) wt[i * 3 + r] = wr[p * 9 + r * 3] * tx + wr[p * 9 + r * 3 + 1] * ty + wr[p * 9 + r * 3 + 2] * tz + wt[p * 3 + r]
            }
        }
        for (k in m.joints.indices) {
            val nd = m.joints[k]
            for (r in 0..2) for (c in 0..3) {
                var s = 0f
                for (kk in 0..2) s += wr[nd * 9 + r * 3 + kk] * m.ibm[k * 16 + c * 4 + kk]
                if (c == 3) s += wt[nd * 3 + r]
                jointMats[k * 12 + r * 4 + c] = s
            }
        }
    }
}
