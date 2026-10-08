package com.corepack.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** A simple blob drawn with Canvas. The pose follows PetBus.phase. Swap this for real art later. */
class PetView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7F77DD.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF534AB7.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26215C.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF26215C.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C3489.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val oval = RectF()
    private val path = Path()

    private var phase: Phase = PetBus.phase
    private val listener: (Phase) -> Unit = {
        phase = it
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        phase = PetBus.phase
        PetBus.listen(listener)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        PetBus.unlisten(listener)
        super.onDetachedFromWindow()
    }

    private fun happyEye(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x - r * 0.12f, y + r * 0.05f)
        path.quadTo(x, y - r * 0.2f, x + r * 0.12f, y + r * 0.05f)
        c.drawPath(path, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val r = s * 0.34f
        val t = SystemClock.uptimeMillis() / 1000f

        var sway = 0f
        var bob = 0f
        var sy = 1f
        when (phase) {
            Phase.IDLE -> sy = 1f + 0.03f * sin(t * 2f)
            Phase.QUEUED -> sway = 4f * sin(t * 2.2f)
            Phase.THINKING -> sway = 5f * sin(t * 1.6f)
            Phase.TALKING -> bob = -0.05f * s * abs(sin(t * 9f))
            Phase.SETTLE -> bob = -0.07f * s * abs(sin(t * 6f))
            Phase.ERROR -> sway = 7f * sin(t * 10f)
            Phase.OFFLINE, Phase.THROTTLED -> sy = 0.94f + 0.02f * sin(t * 1.2f)
        }

        canvas.save()
        canvas.translate(w / 2f, h * 0.93f)
        canvas.rotate(sway)
        canvas.scale(1f, sy)
        canvas.translate(0f, bob)

        oval.set(-r, -r * 1.9f, r, -r * 0.1f)
        canvas.drawOval(oval, fill)
        canvas.drawOval(oval, edge)

        val ey = -r * 1.15f
        val ex = r * 0.38f
        val er = r * 0.11f
        val blink = (t % 4f) > 3.85f
        when (phase) {
            Phase.SETTLE -> {
                happyEye(canvas, -ex, ey, r)
                happyEye(canvas, ex, ey, r)
            }
            Phase.ERROR, Phase.THROTTLED -> {
                canvas.drawLine(-ex - er, ey, -ex + er, ey, stroke)
                canvas.drawLine(ex - er, ey, ex + er, ey, stroke)
            }
            else -> {
                val dx = if (phase == Phase.QUEUED || phase == Phase.OFFLINE) r * 0.07f else 0f
                val dy = if (phase == Phase.THINKING) -r * 0.09f else 0f
                val ry = if (blink) er * 0.15f else er
                oval.set(-ex - er + dx, ey - ry + dy, -ex + er + dx, ey + ry + dy)
                canvas.drawOval(oval, ink)
                oval.set(ex - er + dx, ey - ry + dy, ex + er + dx, ey + ry + dy)
                canvas.drawOval(oval, ink)
            }
        }

        val my = -r * 0.62f
        when (phase) {
            Phase.TALKING -> {
                val mo = r * (0.05f + 0.12f * abs(sin(t * 9f)))
                oval.set(-r * 0.14f, my - mo, r * 0.14f, my + mo)
                canvas.drawOval(oval, ink)
            }
            Phase.SETTLE -> {
                path.reset()
                path.moveTo(-r * 0.2f, my - r * 0.04f)
                path.quadTo(0f, my + r * 0.18f, r * 0.2f, my - r * 0.04f)
                canvas.drawPath(path, stroke)
            }
            Phase.ERROR -> {
                path.reset()
                path.moveTo(-r * 0.22f, my)
                path.quadTo(-r * 0.11f, my - r * 0.1f, 0f, my)
                path.quadTo(r * 0.11f, my + r * 0.1f, r * 0.22f, my)
                canvas.drawPath(path, stroke)
            }
            Phase.OFFLINE -> {
                path.reset()
                path.moveTo(-r * 0.18f, my + r * 0.06f)
                path.quadTo(0f, my - r * 0.1f, r * 0.18f, my + r * 0.06f)
                canvas.drawPath(path, stroke)
            }
            Phase.THROTTLED -> {
                oval.set(-r * 0.08f, my - r * 0.1f, r * 0.08f, my + r * 0.1f)
                canvas.drawOval(oval, ink)
            }
            else -> canvas.drawLine(-r * 0.14f, my, r * 0.14f, my, stroke)
        }
        canvas.restore()

        val g = when (phase) {
            Phase.QUEUED -> "."
            Phase.THINKING -> ".".repeat(1 + (t * 2f).toInt() % 3)
            Phase.SETTLE -> "*"
            Phase.ERROR -> "?"
            Phase.OFFLINE -> "x"
            Phase.THROTTLED -> "zz"
            else -> ""
        }
        if (g.isNotEmpty()) {
            glyph.textSize = s * 0.26f
            canvas.drawText(g, w * 0.8f, h * 0.2f, glyph)
        }
        postInvalidateDelayed(33)
    }
}
