package com.sahil.chibi

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/** Plain-data VRM/GLB model (CPU side). Merges every skinned mesh of the model into one vertex set. */
class VrmModel(
    val vertexCount: Int,
    val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray,
    val jointIdx: IntArray, val weights: FloatArray,
    val indices: List<ShortArray>, val texData: List<ByteArray?>,
    val targets: List<FloatArray>, val targetNames: List<String>,
    val parent: IntArray, val restT: FloatArray, val restR: FloatArray,
    val order: IntArray, val joints: IntArray, val ibm: FloatArray,
    val bones: Map<String, Int>,
    val exprs: Map<String, List<Pair<Int, Float>>>,
    val colors: List<Int>, val restS: FloatArray
)

object VrmLoader {
    /** Maps VRoid (J_Bip_*) and Mixamo bone names to VRM humanoid names. */
    fun humanName(raw: String): String? {
        val s = raw.lowercase()
        val v = Regex("^j_bip_([clr])_([a-z]+)(\\d?)(_\\d+)?$").find(s)
        if (v != null) {
            val side = v.groupValues[1]
            val part = v.groupValues[2]
            val idx = v.groupValues[3]
            if (side == "c") return when (part) {
                "hips" -> "hips"; "spine" -> "spine"; "chest" -> "chest"; "upperchest" -> "upperChest"
                "neck" -> "neck"; "head" -> "head"; else -> null
            }
            val sd = if (side == "l") "left" else "right"
            val core = when (part) {
                "shoulder" -> "Shoulder"; "upperarm" -> "UpperArm"; "lowerarm" -> "LowerArm"; "hand" -> "Hand"
                "upperleg" -> "UpperLeg"; "lowerleg" -> "LowerLeg"; "foot" -> "Foot"; "toebase" -> "Toes"
                else -> null
            }
            if (core != null) return sd + core
            val fg = when (part) { "thumb" -> "Thumb"; "index" -> "Index"; "middle" -> "Middle"; "ring" -> "Ring"; "little" -> "Little"; else -> null }
            val seg = when (idx) { "1" -> "Proximal"; "2" -> "Intermediate"; "3" -> "Distal"; else -> null }
            if (fg != null && seg != null) return sd + fg + seg
            return null
        }
        val t = s.replace(Regex("^mixamorig\\d*:"), "")
        when (t) {
            "hips" -> return "hips"; "spine" -> return "spine"; "spine1" -> return "chest"; "spine2" -> return "upperChest"
            "neck" -> return "neck"; "head" -> return "head"
        }
        val m = Regex("^(left|right)(shoulder|arm|forearm|hand|upleg|leg|foot|toebase)$").find(t)
        if (m != null) {
            val core = when (m.groupValues[2]) {
                "shoulder" -> "Shoulder"; "arm" -> "UpperArm"; "forearm" -> "LowerArm"; "hand" -> "Hand"
                "upleg" -> "UpperLeg"; "leg" -> "LowerLeg"; "foot" -> "Foot"; else -> "Toes"
            }
            return m.groupValues[1] + core
        }
        return null
    }

    private fun catF(l: List<FloatArray>): FloatArray {
        var n = 0
        for (a in l) n += a.size
        val o = FloatArray(n)
        var k = 0
        for (a in l) { System.arraycopy(a, 0, o, k, a.size); k += a.size }
        return o
    }

    private fun catI(l: List<IntArray>): IntArray {
        var n = 0
        for (a in l) n += a.size
        val o = IntArray(n)
        var k = 0
        for (a in l) { System.arraycopy(a, 0, o, k, a.size); k += a.size }
        return o
    }

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
            else if (type == 0x004E4942 && binOff < 0) binOff = off + 8
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
            val csz = when (ct) { 5126, 5125 -> 4; 5123, 5122 -> 2; 5121, 5120 -> 1; else -> throw IllegalArgumentException("component type $ct") }
            var stride = bv.optInt("byteStride", 0)
            if (stride == 0) stride = n * csz
            val base = binOff + bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val out = FloatArray(cnt * n)
            for (k in 0 until cnt) for (c in 0 until n) {
                val p = base + k * stride + c * csz
                out[k * n + c] = when (ct) {
                    5126 -> bb.getFloat(p)
                    5123 -> (bb.getShort(p).toInt() and 0xFFFF).toFloat()
                    5122 -> bb.getShort(p).toFloat()
                    5125 -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    5120 -> bb.get(p).toFloat()
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

        val meshes = j.getJSONArray("meshes")
        val nodes = j.getJSONArray("nodes")
        val nn = nodes.length()
        if (j.optJSONArray("skins") == null) throw IllegalArgumentException("model has no skeleton (not rigged)")

        // every mesh that is skinned by the same skin as the first skinned mesh
        var skinIdx = -1
        val meshList = ArrayList<Int>()
        for (i in 0 until nn) {
            val nd = nodes.getJSONObject(i)
            if (nd.has("mesh") && nd.has("skin")) {
                if (skinIdx < 0) skinIdx = nd.getInt("skin")
                if (nd.getInt("skin") == skinIdx && !meshList.contains(nd.getInt("mesh"))) meshList.add(nd.getInt("mesh"))
            }
        }
        if (skinIdx < 0) throw IllegalArgumentException("model has no skinned mesh (not rigged)")

        val vrm0 = j.optJSONObject("extensions")?.optJSONObject("VRM")
        val mats = j.optJSONArray("materials")
        val vrmMats = vrm0?.optJSONArray("materialProperties")

        val posL = ArrayList<FloatArray>()
        val nrmL = ArrayList<FloatArray>()
        val uvL = ArrayList<FloatArray>()
        val jL = ArrayList<IntArray>()
        val wL = ArrayList<FloatArray>()
        val bases = HashMap<Int, Int>()
        val indices = ArrayList<ShortArray>()
        val texData = ArrayList<ByteArray?>()
        val colors = ArrayList<Int>()
        val tNames = ArrayList<String>()
        val tParts = ArrayList<ArrayList<Pair<Int, FloatArray>>>()
        val tmap = HashMap<Long, Int>()
        var vc = 0

        for (mi in meshList) {
            val mesh = meshes.getJSONObject(mi)
            val prims = mesh.getJSONArray("primitives")
            val meshNames = mesh.optJSONObject("extras")?.optJSONArray("targetNames")
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi)
                if (p.optInt("mode", 4) != 4) continue
                val at = p.getJSONObject("attributes")
                if (!at.has("POSITION") || !at.has("JOINTS_0") || !at.has("WEIGHTS_0")) continue
                val pa = at.getInt("POSITION")
                var base = bases[pa] ?: -1
                val cnt = accessors.getJSONObject(pa).getInt("count")
                if (base < 0) {
                    base = vc
                    bases[pa] = base
                    posL.add(readF(pa))
                    nrmL.add(if (at.has("NORMAL")) readF(at.getInt("NORMAL")) else FloatArray(cnt * 3))
                    uvL.add(if (at.has("TEXCOORD_0")) readF(at.getInt("TEXCOORD_0")) else FloatArray(cnt * 2))
                    val jf = readF(at.getInt("JOINTS_0"))
                    jL.add(IntArray(jf.size) { jf[it].toInt() })
                    wL.add(readF(at.getInt("WEIGHTS_0")))
                    vc += cnt
                    val tj = p.optJSONArray("targets")
                    val names = meshNames ?: p.optJSONObject("extras")?.optJSONArray("targetNames")
                    if (tj != null) for (k in 0 until tj.length()) {
                        val tp = tj.getJSONObject(k)
                        if (!tp.has("POSITION")) continue
                        val nm = if (names != null && k < names.length()) names.getString(k) else "morph$k"
                        var gi = tNames.indexOf(nm)
                        if (gi < 0) { gi = tNames.size; tNames.add(nm); tParts.add(ArrayList()) }
                        tParts[gi].add(Pair(base, readF(tp.getInt("POSITION"))))
                        tmap[mi.toLong() * 100000L + k] = gi
                    }
                }
                val ia = if (p.has("indices")) readF(p.getInt("indices")) else FloatArray(cnt) { it.toFloat() }
                val b0 = base
                indices.add(ShortArray(ia.size) { (ia[it].toInt() + b0).toShort() })

                var tb: ByteArray? = null
                var col = 0xFFCCCCCC.toInt()
                if (mats != null && p.has("material")) {
                    val mIdx = p.getInt("material")
                    val pbr = mats.getJSONObject(mIdx).optJSONObject("pbrMetallicRoughness")
                    var texIdx = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    if (texIdx < 0 && vrmMats != null && mIdx < vrmMats.length())
                        texIdx = vrmMats.getJSONObject(mIdx).optJSONObject("textureProperties")?.optInt("_MainTex", -1) ?: -1
                    if (texIdx >= 0) {
                        try {
                            val src = j.getJSONArray("textures").getJSONObject(texIdx).getInt("source")
                            tb = imageBytes(src)
                        } catch (e: Exception) { tb = null }
                    }
                    val bf = pbr?.optJSONArray("baseColorFactor")
                    if (bf != null && bf.length() >= 3) {
                        val r = (bf.getDouble(0) * 255).toInt().coerceIn(0, 255)
                        val g = (bf.getDouble(1) * 255).toInt().coerceIn(0, 255)
                        val b = (bf.getDouble(2) * 255).toInt().coerceIn(0, 255)
                        col = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                texData.add(tb)
                colors.add(col)
            }
        }
        if (vc == 0 || indices.isEmpty()) throw IllegalArgumentException("no skinned triangles found in this model")
        if (vc > 65535) throw IllegalArgumentException("model too big ($vc vertices, max 65535)")
        val pos = catF(posL)
        val nrm = catF(nrmL)
        val uv = catF(uvL)
        val jointIdx = catI(jL)
        val weights = catF(wL)
        val targets = ArrayList<FloatArray>()
        for (parts in tParts) {
            val d = FloatArray(vc * 3)
            for (pr in parts) System.arraycopy(pr.second, 0, d, pr.first * 3, pr.second.size)
            targets.add(d)
        }

        val parent = IntArray(nn) { -1 }
        val restT = FloatArray(nn * 3)
        val restR = FloatArray(nn * 9)
        val restS = FloatArray(nn) { 1f }
        for (i in 0 until nn) {
            val nd = nodes.getJSONObject(i)
            restR[i * 9] = 1f; restR[i * 9 + 4] = 1f; restR[i * 9 + 8] = 1f
            val mt = nd.optJSONArray("matrix")
            if (mt != null && mt.length() == 16) {
                val a = FloatArray(16) { mt.getDouble(it).toFloat() }
                for (r in 0..2) for (c in 0..2) restR[i * 9 + r * 3 + c] = a[c * 4 + r]
                for (k in 0..2) restT[i * 3 + k] = a[12 + k]
                val sx = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()).toFloat()
                val sy = Math.sqrt((a[4] * a[4] + a[5] * a[5] + a[6] * a[6]).toDouble()).toFloat()
                val sz = Math.sqrt((a[8] * a[8] + a[9] * a[9] + a[10] * a[10]).toDouble()).toFloat()
                val av = (sx + sy + sz) / 3f
                restS[i] = if (av > 1e-6f) av else 1f
            } else {
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
                val sc = nd.optJSONArray("scale")
                if (sc != null && sc.length() == 3) {
                    val sx = sc.getDouble(0).toFloat(); val sy = sc.getDouble(1).toFloat(); val sz = sc.getDouble(2).toFloat()
                    for (r in 0..2) { restR[i * 9 + r * 3] *= sx; restR[i * 9 + r * 3 + 1] *= sy; restR[i * 9 + r * 3 + 2] *= sz }
                    val av = (sx + sy + sz) / 3f
                    restS[i] = if (av > 1e-6f) av else 1f
                }
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

        val skin = j.getJSONArray("skins").getJSONObject(skinIdx)
        val sj = skin.getJSONArray("joints")
        val joints = IntArray(sj.length()) { sj.getInt(it) }
        val ibm = if (skin.has("inverseBindMatrices")) readF(skin.getInt("inverseBindMatrices"))
            else FloatArray(joints.size * 16) { if (it % 5 == 0) 1f else 0f }

        val bones = HashMap<String, Int>()
        val hb = vrm0?.optJSONObject("humanoid")?.optJSONArray("humanBones")
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
        if (!bones.containsKey("hips") || !bones.containsKey("head")) {
            // plain GLB: guess from bone names (VRoid J_Bip_*, Mixamo)
            for (i in 0 until nn) {
                val hn = humanName(nodes.getJSONObject(i).optString("name", "")) ?: continue
                if (!bones.containsKey(hn)) bones[hn] = i
            }
        }
        if (!bones.containsKey("hips") || !bones.containsKey("head"))
            throw IllegalArgumentException("no humanoid skeleton found (needs VRM, VRoid or Mixamo bone names)")

        val exprs = HashMap<String, List<Pair<Int, Float>>>()
        fun alias(n: String): String = when (n.lowercase()) {
            "joy" -> "happy"; "sorrow" -> "sad"; "fun" -> "relaxed"
            "a" -> "aa"; "i" -> "ih"; "u" -> "ou"; "e" -> "ee"; "o" -> "oh"
            "blink_l" -> "blinkLeft"; "blink_r" -> "blinkRight"
            else -> n
        }
        val groups = vrm0?.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")
        if (groups != null) for (gi in 0 until groups.length()) {
            val gr = groups.getJSONObject(gi)
            var nm = gr.optString("presetName", "")
            if (nm.isEmpty() || nm == "unknown") nm = gr.optString("name", "")
            if (nm.isEmpty()) continue
            val binds = gr.optJSONArray("binds") ?: continue
            val l = ArrayList<Pair<Int, Float>>()
            for (bi in 0 until binds.length()) {
                val b = binds.getJSONObject(bi)
                val g = tmap[b.optInt("mesh", 0).toLong() * 100000L + b.getInt("index")]
                if (g != null) l.add(Pair(g, b.optDouble("weight", 100.0).toFloat() / 100f))
            }
            if (l.isNotEmpty()) exprs[alias(nm)] = l
        }
        val pre = j.optJSONObject("extensions")?.optJSONObject("VRMC_vrm")?.optJSONObject("expressions")?.optJSONObject("preset")
        if (pre != null) {
            val pk = pre.keys()
            while (pk.hasNext()) {
                val k = pk.next()
                val binds = pre.getJSONObject(k).optJSONArray("morphTargetBinds") ?: continue
                val l = ArrayList<Pair<Int, Float>>()
                for (bi in 0 until binds.length()) {
                    val b = binds.getJSONObject(bi)
                    val mIdx = nodes.getJSONObject(b.getInt("node")).optInt("mesh", -1)
                    val g = tmap[mIdx.toLong() * 100000L + b.getInt("index")]
                    if (g != null) l.add(Pair(g, b.optDouble("weight", 1.0).toFloat()))
                }
                if (l.isNotEmpty()) exprs[k] = l
            }
        }

        return VrmModel(vc, pos, nrm, uv, jointIdx, weights, indices, texData, targets, tNames,
            parent, restT, restR, order.toIntArray(), joints, ibm, bones, exprs, colors, restS)
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
    private val wsc = FloatArray(n)
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

    /** World position of a humanoid bone after the last solve (null if the model lacks it). */
    fun wpos(bone: String): FloatArray? {
        val i = m.bones[bone] ?: return null
        return floatArrayOf(wt[i * 3], wt[i * 3 + 1], wt[i * 3 + 2])
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
                    val ik = 1f / (wsc[p] * wsc[p])
                    for (q in 0..8) t2[q] *= ik
                    M3.mul(t2, 0, l, 0, t1, 0)
                } else {
                    M3.mul(delta, i * 9, l, 0, t1, 0)
                }
                System.arraycopy(t1, 0, l, 0, 9)
            }
            wsc[i] = (if (p < 0) 1f else wsc[p]) * m.restS[i]
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
