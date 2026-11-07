package com.corepack.pet.mochi

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Draws one [MochiPose]. Everything is in "reference pixels", measured from the design clips:
 * the body is 228 x 176 and the whole stage is 340 x 300 with the body centre 25 px below the middle.
 */
object MochiRenderer {
    const val STAGE_W = 340f
    const val STAGE_H = 300f
    const val ASPECT = STAGE_W / STAGE_H

    private const val A = 114f
    private const val B = 88f
    private val EYE = Colors.rgb(0x1B1416)
    private val OUTLINE = Colors.rgb(0x14161B)
    private val WHITE = Colors.rgb(0xFFFFFF)
    private val BLACK = Colors.rgb(0x000000)
    private val BLUSH = Colors.rgb(0xFF7FA6)
    private val HEART = Colors.rgb(0xF43F5E)
    private val GOLD = Colors.rgb(0xFACC15)

    /** The measured silhouette: a superellipse with exponent 2.75. */
    private val BODY: ShapePath = run {
        val n = 2.75f
        val p = ShapePath()
        val steps = 96
        for (i in 0 until steps) {
            val t = (2.0 * PI * i / steps).toFloat()
            val c = cos(t)
            val s = sin(t)
            val x = A * sign(c) * kotlin.math.abs(c).pow(2f / n)
            val y = B * sign(s) * kotlin.math.abs(s).pow(2f / n)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private val UNIT_CIRCLE = ShapePath.ellipse(0f, 0f, 1f, 1f)
    private val EYE_DOT = ShapePath.ellipse(0f, 0f, 12f, 13.5f)
    private val ARC_UP = ShapePath().moveTo(-19f, 6f).quadTo(0f, -26f, 19f, 6f)
    private val ARC_DOWN = ShapePath().moveTo(-19f, -6f).quadTo(0f, 26f, 19f, -6f)
    private val DASH = ShapePath().moveTo(-12f, 0f).lineTo(12f, 0f)
    private val SPARKLE = ShapePath().moveTo(0f, -1f).quadTo(0.10f, -0.10f, 1f, 0f).quadTo(0.10f, 0.10f, 0f, 1f)
        .quadTo(-0.10f, 0.10f, -1f, 0f).quadTo(-0.10f, -0.10f, 0f, -1f).close()
    private val HEART_PATH = ShapePath().moveTo(0f, 0.9f).cubicTo(-1.1f, 0.1f, -0.85f, -0.85f, 0f, -0.35f)
        .cubicTo(0.85f, -0.85f, 1.1f, 0.1f, 0f, 0.9f).close()
    private val ZEE = ShapePath().moveTo(-1f, -1f).lineTo(1f, -1f).lineTo(-1f, 1f).lineTo(1f, 1f)
    private val QUESTION = ShapePath().moveTo(-8f, -9f).cubicTo(-8f, -20f, 9f, -20f, 9f, -9f).cubicTo(9f, -2f, 0f, -2f, 0f, 6f)
    private val BANG_STEM = ShapePath().moveTo(0f, -13f).lineTo(0f, 3f)
    private val PILL_OUT = ShapePath.roundRect(-40f, -23f, 40f, 23f, 23f)
    private val PILL_IN = ShapePath.roundRect(-33f, -16.5f, 33f, 16.5f, 16.5f)
    private val SPIRAL = ShapePath().also { p ->
        val steps = 60
        for (i in 0..steps) {
            val th = i / steps.toFloat() * 4.4f * PI.toFloat()
            val r = 1.2f * th
            val x = r * cos(th)
            val y = r * sin(th)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
    }

    fun draw(g: Gfx, p: MochiPose, width: Float, height: Float) {
        val s = min(width / STAGE_W, height / STAGE_H)
        g.save()
        g.translate(width / 2f, height / 2f)
        g.scale(s, s)
        g.translate(0f, 25f) // origin = body centre

        drawAura(g, p)
        drawGlow(g, p)
        drawShadow(g, p)
        for (h in p.hands) drawHand(g, p, h)
        drawBody(g, p)
        for (q in p.particles) drawParticle(g, q)
        g.restore()
    }

    /**
     * The thinking aura: a soft halo that breathes, a thin rim just outside the body, and three motes
     * that orbit it. Radial gradients only (no blur), so it is cheap and works on every Android version.
     * It is drawn behind the body, so the inside of the halo is left clear.
     */
    private fun drawAura(g: Gfx, p: MochiPose) {
        val k = p.aura
        if (k <= 0.01f) return
        val breathe = 0.82f + 0.18f * p.auraPulse
        g.save()
        // Must stay inside the stage (340 x 300, body centre 25 px low) or the pet window clips it:
        // at most 170 px sideways and 125 px downwards from the body centre.
        g.scale(158f + 8f * p.auraPulse, 122f + 3f * p.auraPulse)
        g.fill(
            UNIT_CIRCLE,
            Fill.Radial(0f, 0f, 1f, listOf(
                0f to Colors.withAlpha(p.glow, 0f), 0.64f to Colors.withAlpha(p.glow, 0f),
                0.74f to Colors.withAlpha(p.glow, 0.5f * k * breathe), 0.87f to Colors.withAlpha(p.glow, 0.2f * k * breathe),
                1f to Colors.withAlpha(p.glow, 0f),
            )),
        )
        g.restore()

        g.save()
        val s = 1.075f + 0.025f * p.auraPulse
        g.scale(s, s)
        g.stroke(BODY, 2.4f, p.glow, 0.55f * k)
        g.restore()

        for (i in 0 until 3) {
            val ang = p.auraRot + i * 2.0944f
            val tw = 0.75f + 0.25f * sin(ang * 2.3f + i)
            g.save()
            g.translate((A + 24f) * cos(ang), (B + 24f) * sin(ang))
            g.scale(8f * tw, 8f * tw)
            g.fill(
                UNIT_CIRCLE,
                Fill.Radial(0f, 0f, 1f, listOf(
                    0f to Colors.withAlpha(WHITE, 0.95f * k), 0.35f to Colors.withAlpha(p.glow, 0.8f * k), 1f to Colors.withAlpha(p.glow, 0f),
                )),
            )
            g.restore()
        }
    }

    private fun drawGlow(g: Gfx, p: MochiPose) {
        if (p.glowAlpha <= 0f) return
        val a = p.glowAlpha
        g.save()
        g.scale(190f, 190f * 0.82f)
        g.fill(
            UNIT_CIRCLE,
            Fill.Radial(0f, 0f, 1f, listOf(
                0f to Colors.withAlpha(p.glow, a), 0.5f to Colors.withAlpha(p.glow, a * 0.9f), 0.62f to Colors.withAlpha(p.glow, a * 0.55f),
                0.8f to Colors.withAlpha(p.glow, a * 0.15f), 1f to Colors.withAlpha(p.glow, 0f),
            )),
        )
        g.restore()
    }

    private fun drawShadow(g: Gfx, p: MochiPose) {
        g.save()
        g.translate(0f, B + 26f)
        val r = 96f * p.shadow
        g.scale(r, r * 0.14f)
        g.fill(
            UNIT_CIRCLE,
            Fill.Radial(0f, 0f, 1f, listOf(0f to Colors.withAlpha(BLACK, 0.34f * p.shadow), 0.7f to Colors.withAlpha(BLACK, 0.16f * p.shadow), 1f to Colors.withAlpha(BLACK, 0f))),
        )
        g.restore()
    }

    private fun drawHand(g: Gfx, p: MochiPose, h: Hand) {
        if (h.rx <= 0.5f) return
        g.save()
        g.translate(h.x, h.y)
        g.scale(h.rx, h.ry)
        g.fill(UNIT_CIRCLE, Fill.Linear(0f, -1f, 0f, 1f, p.top, p.bottom))
        g.restore()
    }

    private fun drawBody(g: Gfx, p: MochiPose) {
        g.save()
        g.translate(p.dx, p.dy)
        g.translate(0f, B)
        g.rotate(p.rot)
        g.scale(p.sx, p.sy)
        g.translate(0f, -B)

        g.fill(BODY, Fill.Linear(0f, -B, 0f, B, p.top, p.bottom))
        g.save()
        g.clip(BODY)
        // Soft darker rim: a gradient that only shows near the edge (no hard outline).
        g.save()
        g.scale(A * 1.35f, B * 1.35f)
        g.fill(
            UNIT_CIRCLE,
            Fill.Radial(0f, 0f, 1f, listOf(
                0f to Colors.withAlpha(BLACK, 0f), 0.62f to Colors.withAlpha(BLACK, 0f),
                0.76f to Colors.withAlpha(BLACK, 0.07f), 0.86f to Colors.withAlpha(BLACK, 0.17f), 1f to Colors.withAlpha(BLACK, 0.30f),
            )),
        )
        g.restore()
        // glossy highlight, upper right
        g.save()
        g.translate(0.30f * A, -0.42f * B)
        g.scale(52f, 52f * 0.55f)
        g.fill(
            UNIT_CIRCLE,
            Fill.Radial(0f, 0f, 1f, listOf(0f to Colors.withAlpha(WHITE, 0.62f), 0.6f to Colors.withAlpha(WHITE, 0.22f), 1f to Colors.withAlpha(WHITE, 0f))),
        )
        g.restore()
        if (p.blush > 0.02f) {
            for (side in listOf(-1f, 1f)) {
                g.save()
                g.translate(side * 64f, 26f + p.eyes.dy * 0.3f)
                g.scale(24f, 12f)
                g.fill(
                    UNIT_CIRCLE,
                    Fill.Radial(0f, 0f, 1f, listOf(0f to Colors.withAlpha(BLUSH, 0.55f * p.blush), 0.6f to Colors.withAlpha(BLUSH, 0.28f * p.blush), 1f to Colors.withAlpha(BLUSH, 0f))),
                )
                g.restore()
            }
        }
        g.restore()

        drawEyes(g, p.eyes)
        p.badge?.let { drawBadge(g, it) }
        g.restore()
    }

    private fun drawEyes(g: Gfx, e: EyePose) {
        for (side in listOf(-1, 1)) {
            val kind = if (side < 0) e.left else e.right
            g.save()
            g.translate(side * 41f * e.spacing + e.dx, 2f + e.dy)
            when (kind) {
                EyeKind.DOT -> {
                    g.scale(e.pulse, e.pulse * maxOf(e.open, 0.08f))
                    g.fill(EYE_DOT, Fill.Solid(EYE))
                }
                EyeKind.ARC_UP -> g.stroke(ARC_UP, 8f, EYE)
                EyeKind.ARC_DOWN -> g.stroke(ARC_DOWN, 8f, EYE)
                EyeKind.DASH -> {
                    g.scale(e.pulse, 1f)
                    g.stroke(DASH, 10f, EYE)
                }
                EyeKind.DASH_SLANT -> {
                    g.rotate(side * 12f)
                    g.stroke(DASH, 10f, EYE)
                }
                EyeKind.SPIRAL -> {
                    g.rotate(Math.toDegrees((e.spin * side).toDouble()).toFloat())
                    g.stroke(SPIRAL, 4.5f, EYE)
                }
                EyeKind.HEART -> {
                    g.scale(15f * e.pulse, 15f * e.pulse)
                    g.fill(HEART_PATH, Fill.Solid(HEART))
                }
                EyeKind.STAR -> {
                    g.scale(17f * e.pulse, 17f * e.pulse)
                    g.fill(SPARKLE, Fill.Solid(GOLD))
                }
            }
            g.restore()
        }
    }

    private fun drawBadge(g: Gfx, b: BadgePose) {
        if (b.scale <= 0.01f) return
        g.save()
        val pill = b.kind == BadgeKind.DOTS
        g.translate(if (pill) -69f else -71f, if (pill) -73f else -70f)
        g.scale(b.scale, b.scale)
        when (b.kind) {
            BadgeKind.DOTS -> {
                g.fill(PILL_OUT, Fill.Solid(OUTLINE))
                g.fill(PILL_IN, Fill.Solid(b.color))
                for (i in 0..2) {
                    val pulse = 0.5f + 0.5f * sin((2.0 * PI * (b.clock - i * 0.18f)).toFloat())
                    g.save()
                    g.translate((i - 1) * 19f, 0f)
                    g.scale(5.5f * (0.85f + 0.2f * pulse), 5.5f * (0.85f + 0.2f * pulse))
                    g.fill(UNIT_CIRCLE, Fill.Solid(WHITE), 0.5f + 0.5f * pulse)
                    g.restore()
                }
            }
            BadgeKind.DOT -> disc(g, 19f, 13.5f, b.color)
            BadgeKind.QUESTION -> {
                disc(g, 29f, 23f, b.color)
                g.stroke(QUESTION, 6f, WHITE)
                g.save(); g.translate(0f, 15f); g.scale(3.4f, 3.4f); g.fill(UNIT_CIRCLE, Fill.Solid(WHITE)); g.restore()
            }
            BadgeKind.BANG -> {
                disc(g, 27f, 21f, b.color)
                g.stroke(BANG_STEM, 6.5f, WHITE)
                g.save(); g.translate(0f, 13f); g.scale(3.6f, 3.6f); g.fill(UNIT_CIRCLE, Fill.Solid(WHITE)); g.restore()
            }
            BadgeKind.NONE -> {}
        }
        g.restore()
    }

    private fun disc(g: Gfx, outer: Float, inner: Float, color: Int) {
        g.save()
        g.scale(outer, outer)
        g.fill(UNIT_CIRCLE, Fill.Solid(OUTLINE))
        g.restore()
        g.save()
        g.scale(inner, inner)
        g.fill(UNIT_CIRCLE, Fill.Solid(color))
        g.restore()
    }

    private fun drawParticle(g: Gfx, q: Particle) {
        if (q.alpha <= 0.01f) return
        g.save()
        g.translate(q.x, q.y)
        when (q.kind) {
            ParticleKind.SPARKLE, ParticleKind.STAR_GOLD -> {
                g.rotate(q.rot * 20f)
                g.scale(q.size, q.size)
                // A faint outline keeps white sparkles visible over light apps.
                g.stroke(SPARKLE, 0.14f, Colors.rgb(0x5A6070), q.alpha * 0.45f)
                g.fill(SPARKLE, Fill.Solid(if (q.kind == ParticleKind.STAR_GOLD) GOLD else WHITE), q.alpha)
            }
            ParticleKind.HEART -> {
                g.scale(q.size, q.size)
                g.fill(HEART_PATH, Fill.Solid(Colors.rgb(0xFF6B8E)), q.alpha)
            }
            ParticleKind.ZEE -> {
                g.scale(q.size * 0.5f, q.size * 0.5f)
                g.stroke(ZEE, 0.34f, Colors.rgb(0x9AA3B8), q.alpha)
            }
            ParticleKind.MOTE -> {
                g.scale(q.size, q.size)
                g.fill(UNIT_CIRCLE, Fill.Solid(Colors.rgb(0xC9D0DE)), q.alpha)
            }
        }
        g.restore()
    }
}
