package com.corepack.pet.look

import com.corepack.pet.Phase
import com.corepack.pet.agent.PetMood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class EyeKind { DOT, ARC_UP, ARC_DOWN, DASH, DASH_SLANT, SPIRAL, HEART, STAR }
enum class BadgeKind { NONE, DOTS, DOT, QUESTION, BANG }
enum class ParticleKind { SPARKLE, HEART, ZEE, MOTE, STAR_GOLD }

/** The 11 core states of the reference design, its one-off expressions, and two extra moods of our own. */
enum class PetState {
    IDLE, WORKING, THINKING, SEARCHING, APPROVAL, QUESTION, ERROR, FINISHED, RATE_LIMIT, SLEEPING, DIZZY,
    GREETING, LOVE, SURPRISED, PROUD, WINK, YAWN, ANNOYED, DANCING,
    HAPPY, SAD,
}

/** How a state looks when still: colours were sampled from the reference clips. */
class Look(
    val top: Int,
    val bottom: Int,
    val accent: Int,
    val glowAlpha: Float,
    val blush: Float,
    val left: EyeKind,
    val right: EyeKind,
    val badge: BadgeKind,
)

object Looks {
    private fun c(hex: Int) = Colors.rgb(hex)
    private val WHITE_TOP = c(0xE2E1E6)
    private val WHITE_BOTTOM = c(0xCFD0D5)

    private fun l(
        top: Int, bottom: Int, accent: Int, glow: Float, blush: Float,
        eye: EyeKind, badge: BadgeKind = BadgeKind.NONE, right: EyeKind = eye,
    ) = Look(c(top), c(bottom), c(accent), glow, blush, eye, right, badge)

    private val table: Map<PetState, Look> = mapOf(
        PetState.IDLE to Look(WHITE_TOP, WHITE_BOTTOM, c(0xFFFFFF), 0.10f, 0f, EyeKind.DOT, EyeKind.DOT, BadgeKind.NONE),
        PetState.WORKING to l(0xD1DBE6, 0x8DB9E8, 0x4B98E7, 0.22f, 0.6f, EyeKind.DOT, BadgeKind.DOTS),
        PetState.THINKING to l(0xDAD5E4, 0xB2A0E4, 0x7F5BDF, 0.22f, 0.6f, EyeKind.DOT, BadgeKind.DOTS),
        PetState.SEARCHING to l(0xD7D8E5, 0xA2A4E4, 0x5A56CA, 0.22f, 0.5f, EyeKind.DOT, BadgeKind.DOTS),
        PetState.APPROVAL to l(0xE5DCD3, 0xDFB985, 0xE5A743, 0.24f, 0.5f, EyeKind.DOT, BadgeKind.BANG),
        PetState.QUESTION to l(0xCDDEE5, 0x81D0DA, 0x38CDDA, 0.24f, 0.6f, EyeKind.DOT, BadgeKind.QUESTION),
        PetState.ERROR to l(0xE4D5D9, 0xE0969E, 0xE3526D, 0.24f, 0.7f, EyeKind.DASH, BadgeKind.DOT),
        PetState.FINISHED to l(0xDAE2E4, 0xB0D2CB, 0x35D19E, 0.22f, 0.6f, EyeKind.ARC_UP, BadgeKind.DOT),
        PetState.RATE_LIMIT to l(0xE6DBD7, 0xDFB594, 0xF49242, 0.22f, 0.4f, EyeKind.DASH, BadgeKind.DOT),
        PetState.SLEEPING to l(0xDFE0E5, 0xC8C9D2, 0xC8C9D2, 0.05f, 0f, EyeKind.ARC_DOWN),
        PetState.DIZZY to l(0xE4D8E2, 0xD5A4BD, 0xF472B6, 0.22f, 0.8f, EyeKind.SPIRAL),
        PetState.GREETING to Look(c(0xE4E4E7), c(0xD1D2D6), c(0xFFFFFF), 0.10f, 0f, EyeKind.ARC_UP, EyeKind.ARC_UP, BadgeKind.NONE),
        PetState.LOVE to l(0xE8D6DE, 0xEAA0B8, 0xF43F5E, 0.24f, 1f, EyeKind.HEART),
        PetState.SURPRISED to l(0xE0E1E6, 0xD1D1D6, 0xFFFFFF, 0.10f, 0f, EyeKind.DOT),
        PetState.PROUD to l(0xDDE4E6, 0xAFD1C7, 0x4DC8A6, 0.22f, 0.5f, EyeKind.STAR, BadgeKind.DOT),
        PetState.WINK to l(0xDFE0E5, 0xD1D2D7, 0xFFFFFF, 0.10f, 0f, EyeKind.DOT, right = EyeKind.ARC_UP),
        PetState.YAWN to l(0xDFE0E4, 0xC7C9CE, 0xC7C9CE, 0.05f, 0f, EyeKind.ARC_DOWN),
        PetState.ANNOYED to l(0xE0E1E6, 0xD2D3D8, 0xFFFFFF, 0.08f, 0f, EyeKind.DASH_SLANT),
        PetState.DANCING to l(0xE2E2E5, 0xD2D2D7, 0xFFFFFF, 0.12f, 0f, EyeKind.ARC_UP),
        PetState.HAPPY to l(0xE6E0E4, 0xE8C8D6, 0xF9A8C0, 0.14f, 0.9f, EyeKind.ARC_UP),
        PetState.SAD to l(0xDDE0E8, 0xA9B3C9, 0x8FA0C0, 0.10f, 0f, EyeKind.DOT),
    )

    fun of(state: PetState): Look = table.getValue(state)
}

class EyePose(
    val left: EyeKind,
    val right: EyeKind,
    val dx: Float,
    val dy: Float,
    val spacing: Float,
    val open: Float,
    val spin: Float,
    val pulse: Float,
)

class BadgePose(val kind: BadgeKind, val color: Int, val scale: Float, val clock: Float)
class Particle(val kind: ParticleKind, val x: Float, val y: Float, val size: Float, val alpha: Float, val rot: Float)
class Hand(val x: Float, val y: Float, val rx: Float, val ry: Float)

/** Everything the renderer needs for one frame. Positions are in reference pixels (body 228 x 176). */
class PetPose(
    val top: Int,
    val bottom: Int,
    val glow: Int,
    val glowAlpha: Float,
    val blush: Float,
    val sx: Float,
    val sy: Float,
    val dx: Float,
    val dy: Float,
    val rot: Float,
    val eyes: EyePose,
    val badge: BadgePose?,
    val particles: List<Particle>,
    val hands: List<Hand>,
    val shadow: Float,
    /** Glowing aura around the body: 0 = none, 1 = full. Shown while the pet is thinking or searching. */
    val aura: Float = 0f,
    /** Breathing of the aura, 0..1, on a [PetAnimator.AURA_PERIOD_S] cycle. */
    val auraPulse: Float = 0f,
    /** Angle (radians) of the motes that orbit the aura. */
    val auraRot: Float = 0f,
)

object PetAnimator {
    private const val TAU = (2.0 * PI).toFloat()

    /** One breath of the aura, in seconds (matches the thinking cycle of the reference design). */
    const val AURA_PERIOD_S = 1.4f

    /** How strong the aura is in [state]: full while thinking, softer while a tool runs, none otherwise. */
    fun auraFor(state: PetState): Float = when (state) {
        PetState.THINKING -> 1f
        PetState.SEARCHING -> 0.65f
        else -> 0f
    }

    private fun hash(i: Int, salt: Int = 0): Float {
        val x = sin((i * 12.9898 + salt * 78.233)) * 43758.5453
        return (x - floor(x)).toFloat()
    }

    private fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + 2.70158f * x * x * x + 1.70158f * x * x
    }

    /** Eyes close briefly at slightly irregular intervals (about every 4 s). */
    fun blinkOpen(now: Float): Float {
        val period = 4.1f
        val k = floor(now / period).toInt()
        var open = 1f
        for (j in k - 1..k) {
            val at = j * period + 0.9f * sin(j * 1.7f) + 2.2f
            val d = abs(now - at)
            if (d < 0.07f) open = min(open, d / 0.07f * 0.9f + 0.1f)
        }
        return open
    }

    /**
     * The pose of [state]. [tIn] is seconds since the state began (for entrance moves),
     * [now] is a free-running clock in seconds (for loops). Pure: same inputs, same pose.
     */
    fun pose(state: PetState, tIn: Float, now: Float): PetPose {
        val look = Looks.of(state)
        var sx = 1f
        var sy = 1f
        var dx = 0f
        var dy = 0f
        var rot = 0f
        var gx = 3f * sin(now * 0.7f) + 2f * sin(now * 1.3f + 1f)
        var gy = 1.5f * sin(now * 0.9f)
        var eyeDy = 0f
        var spacing = 1f
        var spin = 0f
        var pulse = 1f
        var shadowLift = 0f
        val breath = sin(TAU * now / 3.4f)

        // Every state change gets a small settle, so switching never looks like a cut.
        val settle = exp(-tIn / 0.22f) * cos(TAU * 3.2f * tIn)
        sy *= 1f + 0.035f * settle
        sx *= 1f - 0.02f * settle

        when (state) {
            PetState.IDLE -> { sy *= 1f + 0.018f * breath; sx *= 1f - 0.008f * breath }
            PetState.WORKING -> { eyeDy = 3f; sy *= 1f + 0.010f * sin(TAU * 2.2f * now) }
            PetState.THINKING -> { gx = 34f + gx; gy = -20f + gy; spacing = 0.92f; rot = 1.6f * sin(now * 4.4f) }
            PetState.SEARCHING -> { gx = 18f * sin(TAU * 0.85f * now); gy = -3f; rot = sin(TAU * 0.85f * now + 1f) }
            PetState.APPROVAL -> { val tt = tIn % 2.4f; if (tt < 0.7f) dy = -9f * abs(sin(PI.toFloat() * tt / 0.35f)) }
            PetState.QUESTION -> { rot = 9f + 1.5f * sin(now * 1.8f); gx = -3f }
            PetState.ERROR -> { dx = 5f * exp(-tIn / 0.35f) * sin(TAU * 7f * tIn); eyeDy = 8f; sy *= 0.985f }
            PetState.FINISHED -> {
                val p = (tIn / 0.55f)
                if (p < 1f) {
                    dy = -18f * sin(PI.toFloat() * p)
                    shadowLift = -dy
                } else {
                    val land = exp(-(tIn - 0.55f) / 0.2f) * cos(TAU * 3f * (tIn - 0.55f))
                    sy *= 1f - 0.07f * land
                    sx *= 1f + 0.05f * land
                }
                sy *= 1f + 0.012f * breath
            }
            PetState.RATE_LIMIT -> {
                eyeDy = 14f; pulse = 0.85f
                val slow = sin(TAU * now / 4.5f)
                sy *= 0.97f + 0.02f * slow
            }
            PetState.SLEEPING -> {
                eyeDy = 10f
                val slow = sin(TAU * now / 4.6f)
                sy *= 1f + 0.03f * slow; sx *= 1f - 0.012f * slow
            }
            PetState.DIZZY -> {
                rot = 6f * sin(TAU * 1.1f * now); dx = 3f * sin(TAU * 1.1f * now + 1.5f)
                spin = TAU * 1.3f * now
            }
            PetState.GREETING -> rot = 3f * sin(TAU * 0.9f * now)
            PetState.LOVE -> {
                pulse = 1f + 0.12f * sin(TAU * 2.2f * now)
                dy = -3f * abs(sin(TAU * 1.1f * now / 2f))
            }
            PetState.SURPRISED -> { eyeDy = -6f; spacing = 1.1f; pulse = 0.85f; sy *= 1f + 0.08f * exp(-tIn / 0.3f) }
            PetState.PROUD -> { sy *= 1f + 0.015f * breath; if (tIn < 0.4f) dy = -8f * sin(PI.toFloat() * tIn / 0.4f) }
            PetState.WINK -> {}
            PetState.YAWN -> { eyeDy = 8f; sy *= 1f + 0.05f * sin(TAU * 0.5f * now) }
            PetState.ANNOYED -> { eyeDy = 6f; sy *= 0.97f }
            PetState.DANCING -> {
                val beat = abs(sin(PI.toFloat() * now / 0.49f))
                dy = -21f * beat; shadowLift = -dy
                sy *= 1f - 0.05f * (1f - beat); sx *= 1f + 0.03f * (1f - beat)
                rot = 7f * sin(TAU * now / 0.98f)
            }
            PetState.HAPPY -> { rot = 2f * sin(TAU * 0.6f * now); sy *= 1f + 0.018f * breath }
            PetState.SAD -> { eyeDy = 12f; spacing = 0.95f; gx *= 0.4f; sy *= 0.95f + 0.012f * breath }
        }

        val dotsOpen = if (look.left == EyeKind.DOT) blinkOpen(now) else 1f
        val eyes = EyePose(look.left, look.right, gx, eyeDy + gy, spacing, dotsOpen, spin, pulse)

        val badge = if (look.badge == BadgeKind.NONE) null
        else BadgePose(look.badge, look.accent, easeOutBack(tIn / 0.35f).coerceAtLeast(0f), now)

        return PetPose(
            top = look.top, bottom = look.bottom, glow = look.accent, glowAlpha = look.glowAlpha, blush = look.blush,
            sx = sx, sy = sy, dx = dx, dy = dy, rot = rot, eyes = eyes, badge = badge,
            particles = particles(state, tIn, now), hands = hands(state, tIn, now),
            shadow = (1f - shadowLift / 70f).coerceIn(0.6f, 1.1f),
            aura = auraFor(state),
            auraPulse = 0.5f + 0.5f * sin(TAU * now / AURA_PERIOD_S),
            auraRot = 1.1f * now,
        )
    }

    private fun hands(state: PetState, tIn: Float, now: Float): List<Hand> {
        if (state != PetState.GREETING) return emptyList()
        val pop = easeOutBack(tIn / 0.3f).coerceAtLeast(0f)
        return listOf(
            Hand(-116f + 5f * sin(TAU * 1.6f * now), 69f + 4f * cos(TAU * 1.6f * now), 26f * pop, 21f * pop),
            Hand(118f + 5f * cos(TAU * 1.6f * now), -44f + 6f * sin(TAU * 1.6f * now), 24f * pop, 21f * pop),
        )
    }

    private fun particles(state: PetState, tIn: Float, now: Float): List<Particle> {
        val out = ArrayList<Particle>()
        when (state) {
            PetState.FINISHED, PetState.PROUD -> {
                val base = listOf(Triple(-61f, -126f, 20f), Triple(5f, -149f, 24f), Triple(3f, -121f, 16f))
                val fade = (min(tIn / 0.25f, 1f)) * (0.55f + 0.45f * min(1f, max(0f, (2.4f - tIn) / 0.8f)))
                val kind = if (state == PetState.PROUD) ParticleKind.STAR_GOLD else ParticleKind.SPARKLE
                base.forEachIndexed { i, (x, y, s) ->
                    val tw = 0.65f + 0.35f * sin(TAU * (now * 1.1f + i * 0.33f))
                    out.add(Particle(kind, x, y - 6f * min(tIn, 1f), s * tw, fade, now * 0.4f + i))
                }
            }
            PetState.SLEEPING, PetState.YAWN -> {
                for (i in 0 until 3) {
                    val ph = ((now / 2.6f) + i / 3f) % 1f
                    val a = sin(PI.toFloat() * ph)
                    out.add(Particle(ParticleKind.ZEE, 62f + 26f * ph, -70f - 62f * ph, 12f + 12f * ph, a * 0.9f, 0f))
                }
            }
            PetState.LOVE -> {
                for (i in 0 until 4) {
                    val ph = ((now / 2.2f) + i / 4f) % 1f
                    val a = sin(PI.toFloat() * ph)
                    out.add(Particle(ParticleKind.HEART, -50f + i * 34f + 8f * sin(TAU * ph + i), -100f - 55f * ph, 11f + 5f * hash(i), a, 0f))
                }
            }
            PetState.GREETING -> {
                val fade = min(tIn / 0.4f, 1f)
                for (i in 0 until 90) {
                    val ang = TAU * hash(i, 1) + now * 0.16f
                    val rad = 0.75f + 0.45f * hash(i, 2)
                    val tw = 0.5f + 0.5f * sin(TAU * (now * 0.7f + hash(i, 3)))
                    out.add(Particle(ParticleKind.MOTE, 170f * rad * cos(ang) * 0.95f, -10f + 105f * rad * sin(ang), 0.9f + 1.1f * hash(i, 4), 0.2f + 0.6f * tw * fade, 0f))
                }
            }
            else -> {}
        }
        return out
    }
}

/** Plays states with a short cross-fade, and can play a one-off expression before falling back. */
class PetPlayer {
    private var base = PetState.IDLE
    private var override: PetState? = null
    private var overrideUntilMs = 0L
    private var current = PetState.IDLE
    private var previous = PetState.IDLE
    private var changedAtMs = Long.MIN_VALUE / 4
    private var previousSinceMs = 0L

    fun setBase(state: PetState, nowMs: Long) {
        base = state
        refresh(nowMs)
    }

    fun playOnce(state: PetState, nowMs: Long, durationMs: Long) {
        override = state
        overrideUntilMs = nowMs + durationMs
        refresh(nowMs)
    }

    val state: PetState get() = current

    private fun effective(nowMs: Long): PetState {
        val o = override
        return if (o != null && nowMs < overrideUntilMs) o else base
    }

    private fun refresh(nowMs: Long) {
        val next = effective(nowMs)
        if (next != current) {
            previous = current
            previousSinceMs = changedAtMs
            current = next
            changedAtMs = nowMs
        }
    }

    fun frame(nowMs: Long): PetPose {
        refresh(nowMs)
        val now = nowMs / 1000f
        val tIn = (nowMs - changedAtMs) / 1000f
        val cur = PetAnimator.pose(current, tIn, now)
        val k = (tIn / FADE_S).coerceIn(0f, 1f)
        if (k >= 1f) return cur
        val prev = PetAnimator.pose(previous, (nowMs - previousSinceMs) / 1000f, now)
        return blend(prev, cur, k * k * (3f - 2f * k))
    }

    private fun blend(a: PetPose, b: PetPose, k: Float): PetPose {
        fun f(x: Float, y: Float) = x + (y - x) * k
        // Eyes close at the midpoint, swap shape while shut, then open again.
        val shut = abs(2f * k - 1f)
        val src = if (k < 0.5f) a.eyes else b.eyes
        val eyes = EyePose(src.left, src.right, f(a.eyes.dx, b.eyes.dx), f(a.eyes.dy, b.eyes.dy), f(a.eyes.spacing, b.eyes.spacing),
            min(src.open, shut * 0.9f + 0.1f), src.spin, src.pulse)
        val badge = if (k < 0.5f) a.badge?.let { BadgePose(it.kind, it.color, it.scale * (1f - 2f * k), it.clock) } else b.badge
        return PetPose(
            Colors.lerp(a.top, b.top, k), Colors.lerp(a.bottom, b.bottom, k), Colors.lerp(a.glow, b.glow, k), f(a.glowAlpha, b.glowAlpha),
            f(a.blush, b.blush), f(a.sx, b.sx), f(a.sy, b.sy), f(a.dx, b.dx), f(a.dy, b.dy), f(a.rot, b.rot),
            eyes, badge, b.particles, b.hands, f(a.shadow, b.shadow),
            aura = f(a.aura, b.aura), auraPulse = b.auraPulse, auraRot = b.auraRot,
        )
    }

    companion object {
        const val FADE_S = 0.35f
    }
}

/** Which look the pet wears for what the agent is doing. */
object PetMap {
    fun stateFor(phase: Phase, mood: PetMood): PetState = when (phase) {
        Phase.IDLE -> idleFor(mood)
        Phase.QUEUED -> PetState.RATE_LIMIT
        Phase.THINKING -> PetState.THINKING
        Phase.TOOL -> PetState.SEARCHING
        Phase.TALKING -> PetState.WORKING
        Phase.SETTLE -> PetState.FINISHED
        Phase.ERROR -> PetState.ERROR
        Phase.OFFLINE -> PetState.SLEEPING
        Phase.THROTTLED -> PetState.DIZZY
        Phase.ALERT -> PetState.APPROVAL
    }

    fun idleFor(mood: PetMood): PetState = when (mood) {
        PetMood.NEUTRAL -> PetState.IDLE
        PetMood.HAPPY -> PetState.HAPPY
        PetMood.SAD -> PetState.SAD
        PetMood.CURIOUS -> PetState.QUESTION
        PetMood.SLEEPY -> PetState.YAWN
        PetMood.EXCITED -> PetState.DANCING
    }
}
