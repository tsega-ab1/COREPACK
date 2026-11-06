package com.corepack.pet

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.corepack.pet.mochi.Fill
import com.corepack.pet.mochi.Gfx
import com.corepack.pet.mochi.ShapePath

/** Phone backend for the shared drawing layer: the same drawing code runs on Canvas here. */
class AndroidGfx : Gfx {
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun begin(c: Canvas) {
        canvas = c
    }

    private val c: Canvas get() = canvas!!

    override fun save() { c.save() }
    override fun restore() { c.restore() }
    override fun translate(dx: Float, dy: Float) = c.translate(dx, dy)
    override fun rotate(deg: Float) = c.rotate(deg)
    override fun scale(sx: Float, sy: Float) = c.scale(sx, sy)
    override fun clip(path: ShapePath) { c.clipPath(convert(path)) }

    override fun fill(path: ShapePath, fill: Fill, alpha: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        when (fill) {
            is Fill.Solid -> {
                paint.color = fill.argb
                paint.alpha = (alpha.coerceIn(0f, 1f) * (fill.argb ushr 24)).toInt()
            }
            is Fill.Linear -> {
                paint.shader = LinearGradient(fill.x0, fill.y0, fill.x1, fill.y1, fill.c0, fill.c1, Shader.TileMode.CLAMP)
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            }
            is Fill.Radial -> {
                paint.shader = RadialGradient(
                    fill.cx, fill.cy, fill.r,
                    IntArray(fill.stops.size) { fill.stops[it].second },
                    FloatArray(fill.stops.size) { fill.stops[it].first },
                    Shader.TileMode.CLAMP,
                )
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            }
        }
        c.drawPath(convert(path), paint)
    }

    override fun stroke(path: ShapePath, width: Float, argb: Int, alpha: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = argb
        paint.alpha = (alpha.coerceIn(0f, 1f) * (argb ushr 24)).toInt()
        c.drawPath(convert(path), paint)
    }

    private fun convert(p: ShapePath): Path {
        (p.native as? Path)?.let { return it }
        val out = Path()
        for (op in p.ops) when (op) {
            is ShapePath.Op.Move -> out.moveTo(op.x, op.y)
            is ShapePath.Op.Line -> out.lineTo(op.x, op.y)
            is ShapePath.Op.Quad -> out.quadTo(op.cx, op.cy, op.x, op.y)
            is ShapePath.Op.Cubic -> out.cubicTo(op.c1x, op.c1y, op.c2x, op.c2y, op.x, op.y)
            ShapePath.Op.Close -> out.close()
        }
        p.native = out
        return out
    }
}
