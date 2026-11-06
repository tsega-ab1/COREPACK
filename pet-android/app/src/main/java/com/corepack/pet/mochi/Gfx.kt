package com.corepack.pet.mochi

/**
 * A tiny drawing layer. The character is drawn once, against this interface, and the same code runs
 * on the phone (android.graphics.Canvas) and in the desktop preview/tests (Java2D).
 * Colours are ARGB ints. Angles are degrees. y points down.
 */
class ShapePath {
    sealed class Op {
        class Move(val x: Float, val y: Float) : Op()
        class Line(val x: Float, val y: Float) : Op()
        class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : Op()
        class Cubic(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float) : Op()
        object Close : Op()
    }

    val ops = ArrayList<Op>()

    /** Platform backends cache their converted path here. */
    var native: Any? = null

    fun moveTo(x: Float, y: Float) = apply { ops.add(Op.Move(x, y)) }
    fun lineTo(x: Float, y: Float) = apply { ops.add(Op.Line(x, y)) }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = apply { ops.add(Op.Quad(cx, cy, x, y)) }
    fun cubicTo(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float) = apply { ops.add(Op.Cubic(a, b, c, d, x, y)) }
    fun close() = apply { ops.add(Op.Close) }

    companion object {
        private const val K = 0.5522847f

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): ShapePath = ShapePath()
            .moveTo(cx + rx, cy)
            .cubicTo(cx + rx, cy + ry * K, cx + rx * K, cy + ry, cx, cy + ry)
            .cubicTo(cx - rx * K, cy + ry, cx - rx, cy + ry * K, cx - rx, cy)
            .cubicTo(cx - rx, cy - ry * K, cx - rx * K, cy - ry, cx, cy - ry)
            .cubicTo(cx + rx * K, cy - ry, cx + rx, cy - ry * K, cx + rx, cy)
            .close()

        fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float): ShapePath {
            val q = rad * K
            return ShapePath()
                .moveTo(l + rad, t).lineTo(r - rad, t)
                .cubicTo(r - rad + q, t, r, t + rad - q, r, t + rad).lineTo(r, b - rad)
                .cubicTo(r, b - rad + q, r - rad + q, b, r - rad, b).lineTo(l + rad, b)
                .cubicTo(l + rad - q, b, l, b - rad + q, l, b - rad).lineTo(l, t + rad)
                .cubicTo(l, t + rad - q, l + rad - q, t, l + rad, t)
                .close()
        }
    }
}

sealed class Fill {
    class Solid(val argb: Int) : Fill()
    class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val c0: Int, val c1: Int) : Fill()

    /** [stops] are (position 0..1, argb) pairs with increasing positions. */
    class Radial(val cx: Float, val cy: Float, val r: Float, val stops: List<Pair<Float, Int>>) : Fill()
}

interface Gfx {
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun rotate(deg: Float)
    fun scale(sx: Float, sy: Float)
    fun clip(path: ShapePath)
    fun fill(path: ShapePath, fill: Fill, alpha: Float = 1f)
    fun stroke(path: ShapePath, width: Float, argb: Int, alpha: Float = 1f)
}

object Colors {
    fun rgb(hex: Int): Int = 0xFF000000.toInt() or (hex and 0xFFFFFF)

    fun withAlpha(argb: Int, a: Float): Int {
        val base = (argb ushr 24) / 255f
        val v = (base * a).coerceIn(0f, 1f)
        return ((v * 255f + 0.5f).toInt() shl 24) or (argb and 0xFFFFFF)
    }

    fun lerp(a: Int, b: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
