package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Smooth anime-inspired mascot drawn directly in canvas instead of a low-poly bitmap.
 * This keeps the app lightweight while matching the softer character reference more closely.
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"
    var action = "idle"
    var facingRight = true
    var talking = false
    var pixel = false

    private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F4D8D6") }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D1831") }
    private val hairDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A1227") }
    private val hoodiePurple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C3B1DB") }
    private val hoodieGray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D9D6E8") }
    private val ninjaBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#243A67") }
    private val navy = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#172B4D") }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D4A64")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E7A7B0") }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7D8EAA") }
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8747A")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val headband = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F5E8A")
        style = Paint.Style.FILL
    }
    private val boot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1428") }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9CCD7")
        alpha = 80
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = min(w / 420f, h / 920f).coerceAtLeast(0.38f)

        c.save()
        c.translate((w - 420f * scale) / 2f, (h - 920f * scale) / 2f)
        c.scale(scale, scale)

        drawShadow(c)
        drawMascot(c)

        c.restore()
        postInvalidateDelayed(250)
    }

    private fun drawShadow(c: Canvas) {
        c.drawOval(RectF(135f, 760f, 285f, 800f), shadow)
    }

    private fun drawMascot(c: Canvas) {
        val headCenterX = 210f
        val headCenterY = 150f
        val headRadius = 86f

        if (!facingRight) c.translate(420f, 0f); c.scale(-1f, 1f)

        // hair back
        val hairBack = Path().apply {
            moveTo(120f, 150f)
            cubicTo(112f, 110f, 125f, 60f, 170f, 40f)
            cubicTo(210f, 18f, 250f, 20f, 292f, 52f)
            cubicTo(335f, 92f, 338f, 142f, 318f, 175f)
            cubicTo(304f, 200f, 280f, 220f, 260f, 223f)
            lineTo(160f, 223f)
            cubicTo(140f, 220f, 120f, 200f, 120f, 170f)
            close()
        }
        c.drawPath(hairBack, hairDark)

        // head
        c.drawOval(RectF(headCenterX - headRadius, headCenterY - headRadius, headCenterX + headRadius, headCenterY + headRadius), skin)

        // headband
        c.drawRoundRect(112f, 60f, 308f, 94f, 18f, 18f, headband)
        c.drawRoundRect(128f, 58f, 292f, 78f, 12f, 12f, outline)

        // hair front, bangs and long sides
        val hairFront = Path().apply {
            moveTo(116f, 132f)
            cubicTo(106f, 98f, 118f, 62f, 160f, 52f)
            cubicTo(178f, 48f, 196f, 58f, 206f, 72f)
            lineTo(213f, 82f)
            lineTo(215f, 60f)
            cubicTo(235f, 48f, 254f, 46f, 270f, 52f)
            cubicTo(302f, 60f, 316f, 88f, 310f, 124f)
            lineTo(296f, 150f)
            lineTo(262f, 155f)
            lineTo(286f, 220f)
            lineTo(153f, 220f)
            lineTo(178f, 150f)
            close()
        }
        c.drawPath(hairFront, hair)

        // face details
        c.drawCircle(180f, 150f, 12f, eye)
        c.drawCircle(240f, 150f, 12f, eye)
        c.drawCircle(180f, 150f, 4f, outline)
        c.drawCircle(240f, 150f, 4f, outline)
        c.drawOval(RectF(170f, 175f, 250f, 185f), mouth)
        c.drawCircle(172f, 169f, 8f, blush)
        c.drawCircle(248f, 169f, 8f, blush)

        // neck and collar
        c.drawRoundRect(190f, 240f, 230f, 290f, 10f, 10f, skin)
        c.drawRoundRect(150f, 280f, 270f, 360f, 22f, 22f, hoodieGray)

        // hood opening
        val hood = Path().apply {
            moveTo(150f, 300f)
            cubicTo(160f, 260f, 180f, 232f, 210f, 218f)
            cubicTo(230f, 206f, 250f, 208f, 270f, 218f)
            cubicTo(300f, 232f, 320f, 262f, 330f, 300f)
            lineTo(300f, 360f)
            lineTo(120f, 360f)
            close()
        }
        c.drawPath(hood, hoodiePurple)
        c.drawPath(hood, outline)

        // torso body
        c.drawRoundRect(132f, 298f, 288f, 430f, 28f, 28f, hoodieGray)
        c.drawRoundRect(160f, 316f, 260f, 430f, 20f, 20f, hoodiePurple)
        c.drawRoundRect(148f, 300f, 272f, 330f, 18f, 18f, hoodieGray)
        c.drawRect(186f, 346f, 234f, 405f, darkBlueToPaint())

        // arms
        c.drawRoundRect(114f, 334f, 152f, 510f, 18f, 18f, hoodiePurple)
        c.drawRoundRect(268f, 334f, 306f, 510f, 18f, 18f, hoodiePurple)
        c.drawRoundRect(120f, 500f, 160f, 560f, 20f, 20f, navy)
        c.drawRoundRect(260f, 500f, 300f, 560f, 20f, 20f, navy)

        // hands
        c.drawRoundRect(140f, 548f, 170f, 573f, 10f, 10f, skin)
        c.drawRoundRect(250f, 548f, 280f, 573f, 10f, 10f, skin)

        // lower body / skirt / pants
        c.drawRoundRect(125f, 430f, 295f, 620f, 28f, 28f, ninjaBlue)
        c.drawRoundRect(164f, 480f, 256f, 620f, 16f, 16f, navy)
        c.drawRect(120f, 430f, 300f, 465f, hoodieGray)

        // leg stripes
        c.drawRect(150f, 470f, 190f, 620f, outline)
        c.drawRect(230f, 470f, 270f, 620f, outline)

        // shorts / covered legs
        c.drawRoundRect(118f, 620f, 302f, 640f, 12f, 12f, navy)

        // thigh and calf shapes
        c.drawRoundRect(160f, 640f, 205f, 710f, 15f, 15f, ninjaBlue)
        c.drawRoundRect(215f, 640f, 260f, 710f, 15f, 15f, ninjaBlue)
        c.drawRoundRect(155f, 700f, 205f, 760f, 14f, 14f, navy)
        c.drawRoundRect(215f, 700f, 265f, 760f, 14f, 14f, navy)

        // boots
        c.drawRoundRect(150f, 760f, 212f, 810f, 12f, 12f, boot)
        c.drawRoundRect(208f, 760f, 270f, 810f, 12f, 12f, boot)
        c.drawRoundRect(150f, 780f, 212f, 794f, 6f, 6f, outline)
        c.drawRoundRect(208f, 780f, 270f, 794f, 6f, 6f, outline)

        // belt / detail around waist
        c.drawRoundRect(138f, 495f, 282f, 515f, 10f, 10f, navy)
    }

    private fun darkBlueToPaint(): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#3C4F87")
        return p
    }
}
