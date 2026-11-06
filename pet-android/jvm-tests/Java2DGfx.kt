import com.corepack.pet.mochi.Fill
import com.corepack.pet.mochi.Gfx
import com.corepack.pet.mochi.ShapePath
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D

/** Desktop backend for the shared drawing layer: renders the same code the phone runs. */
class Java2DGfx(private val g: Graphics2D) : Gfx {
    private class Saved(val tx: AffineTransform, val clip: Shape?)

    private val stack = ArrayDeque<Saved>()

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    override fun save() { stack.addLast(Saved(g.transform, g.clip)) }
    override fun restore() { val s = stack.removeLast(); g.transform = s.tx; g.clip = s.clip }
    override fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    override fun rotate(deg: Float) = g.rotate(Math.toRadians(deg.toDouble()))
    override fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())
    override fun clip(path: ShapePath) = g.clip(shape(path))

    override fun fill(path: ShapePath, fill: Fill, alpha: Float) {
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        g.paint = when (fill) {
            is Fill.Solid -> Color(fill.argb, true)
            is Fill.Linear -> GradientPaint(fill.x0, fill.y0, Color(fill.c0, true), fill.x1, fill.y1, Color(fill.c1, true))
            is Fill.Radial -> RadialGradientPaint(
                Point2D.Float(fill.cx, fill.cy), fill.r,
                fill.stops.map { it.first }.toFloatArray(), fill.stops.map { Color(it.second, true) }.toTypedArray(),
            )
        }
        g.fill(shape(path))
        g.composite = AlphaComposite.SrcOver
    }

    override fun stroke(path: ShapePath, width: Float, argb: Int, alpha: Float) {
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        g.paint = Color(argb, true)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape(path))
        g.composite = AlphaComposite.SrcOver
    }

    private fun shape(p: ShapePath): Shape {
        (p.native as? Shape)?.let { return it }
        val out = Path2D.Float()
        for (op in p.ops) when (op) {
            is ShapePath.Op.Move -> out.moveTo(op.x, op.y)
            is ShapePath.Op.Line -> out.lineTo(op.x, op.y)
            is ShapePath.Op.Quad -> out.quadTo(op.cx, op.cy, op.x, op.y)
            is ShapePath.Op.Cubic -> out.curveTo(op.c1x, op.c1y, op.c2x, op.c2y, op.x, op.y)
            ShapePath.Op.Close -> out.closePath()
        }
        p.native = out
        return out
    }
}
