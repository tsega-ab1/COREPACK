import com.corepack.pet.Phase
import com.corepack.pet.agent.PetMood
import com.corepack.pet.mochi.*
import kotlin.math.abs

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

/** Records what the renderer asks for, so bad numbers and unbalanced save/restore are caught without a screen. */
class RecGfx : Gfx {
    var depth = 0; var maxDepth = 0; var fills = 0; var strokes = 0; var bad = 0
    private fun f(vararg v: Float) { if (v.any { it.isNaN() || it.isInfinite() }) bad++ }
    override fun save() { depth++; maxDepth = maxOf(maxDepth, depth) }
    override fun restore() { depth-- }
    override fun translate(dx: Float, dy: Float) = f(dx, dy)
    override fun rotate(deg: Float) = f(deg)
    override fun scale(sx: Float, sy: Float) = f(sx, sy)
    override fun clip(path: ShapePath) {}
    override fun fill(path: ShapePath, fill: Fill, alpha: Float) { fills++; f(alpha) }
    override fun stroke(path: ShapePath, width: Float, argb: Int, alpha: Float) { strokes++; f(width, alpha) }
}

fun poseSig(p: MochiPose) = listOf(p.sx, p.sy, p.dx, p.dy, p.rot, p.eyes.dx, p.eyes.dy, p.eyes.open, p.glowAlpha, p.blush, p.shadow).map { "%.5f".format(it) } +
    listOf(p.top, p.bottom, p.glow, p.particles.size, p.hands.size)

fun main() {
    // ---- mapping from what the agent is doing to how the pet looks
    run {
        val expect = mapOf(
            Phase.QUEUED to MochiState.RATE_LIMIT, Phase.THINKING to MochiState.THINKING, Phase.TOOL to MochiState.SEARCHING,
            Phase.TALKING to MochiState.WORKING, Phase.SETTLE to MochiState.FINISHED, Phase.ERROR to MochiState.ERROR,
            Phase.OFFLINE to MochiState.SLEEPING, Phase.THROTTLED to MochiState.DIZZY,
            Phase.ALERT to MochiState.APPROVAL,
        )
        check("every busy phase has its look, whatever the mood", expect.all { (ph, st) -> PetMood.entries.all { MochiMap.stateFor(ph, it) == st } })
        val idle = mapOf(PetMood.NEUTRAL to MochiState.IDLE, PetMood.HAPPY to MochiState.HAPPY, PetMood.SAD to MochiState.SAD,
            PetMood.CURIOUS to MochiState.QUESTION, PetMood.SLEEPY to MochiState.YAWN, PetMood.EXCITED to MochiState.DANCING)
        val hops = (0 until 300).map { MochiAnimator.pose(MochiState.APPROVAL, it * 0.05f, 9f).dy }
        check("the alert look keeps hopping (not just once) so it gets noticed", hops.count { it < -8f } > 20 && hops.min() > -9.5f, "${hops.min()}")
        check("mood only colours the calm state", idle.all { (m, st) -> MochiMap.stateFor(Phase.IDLE, m) == st })
        check("all phases x moods map without error", Phase.entries.all { ph -> PetMood.entries.all { MochiMap.stateFor(ph, it) in MochiState.entries } })
    }

    // ---- palette sampled from the reference (regression guard)
    run {
        check("working is the sampled blue", Looks.of(MochiState.WORKING).bottom == Colors.rgb(0x8DB9E8) && Looks.of(MochiState.WORKING).accent == Colors.rgb(0x4B98E7))
        check("error is the sampled pink-red", Looks.of(MochiState.ERROR).accent == Colors.rgb(0xE3526D) && Looks.of(MochiState.ERROR).bottom == Colors.rgb(0xE0969E))
        check("finished is the sampled green", Looks.of(MochiState.FINISHED).accent == Colors.rgb(0x35D19E))
        check("11 core states + expressions all have a look", MochiState.entries.size == 21 && MochiState.entries.all { Looks.of(it).top != 0 })
        check("badge kinds match the reference", Looks.of(MochiState.WORKING).badge == BadgeKind.DOTS && Looks.of(MochiState.THINKING).badge == BadgeKind.DOTS &&
            Looks.of(MochiState.QUESTION).badge == BadgeKind.QUESTION && Looks.of(MochiState.APPROVAL).badge == BadgeKind.BANG && Looks.of(MochiState.IDLE).badge == BadgeKind.NONE)
        check("eye kinds match the reference", Looks.of(MochiState.FINISHED).left == EyeKind.ARC_UP && Looks.of(MochiState.ERROR).left == EyeKind.DASH &&
            Looks.of(MochiState.DIZZY).left == EyeKind.SPIRAL && Looks.of(MochiState.SLEEPING).left == EyeKind.ARC_DOWN && Looks.of(MochiState.WINK).right == EyeKind.ARC_UP)
    }

    // ---- poses are pure and sane
    run {
        val states = MochiState.entries
        check("pose is a pure function of its inputs", states.all { s -> poseSig(MochiAnimator.pose(s, 0.7f, 12.3f)) == poseSig(MochiAnimator.pose(s, 0.7f, 12.3f)) })
        var bad = ""
        for (s in states) for (tIn in listOf(0f, 0.1f, 0.5f, 1f, 5f)) for (now in listOf(0f, 3.3f, 100f)) {
            val p = MochiAnimator.pose(s, tIn, now)
            val fs = listOf(p.sx, p.sy, p.dx, p.dy, p.rot, p.eyes.dx, p.eyes.dy, p.eyes.open, p.glowAlpha, p.blush, p.shadow)
            if (fs.any { it.isNaN() || it.isInfinite() }) bad += "$s nan; "
            else if (p.sx !in 0.85f..1.15f || p.sy !in 0.85f..1.15f) bad += "$s scale ${p.sx}/${p.sy}; "
            else if (abs(p.dx) > 10f || abs(p.dy) > 25f || abs(p.rot) > 15f) bad += "$s move; "
            else if (p.eyes.open !in 0f..1f) bad += "$s open; "
        }
        check("every state at every time stays finite and within sane limits", bad.isEmpty(), bad)
    }

    // ---- motion matches the numbers measured from the reference clips
    run {
        // Dance: 0.49 s beat, about 21 px high
        val dys = (0 until 400).map { MochiAnimator.pose(MochiState.DANCING, 5f, it * 0.01f + 2f).dy }
        check("dance height is about 21 px", abs(dys.min() + 21f) < 0.5f && dys.max() <= 0.01f, "min ${dys.min()} max ${dys.max()}")
        var beats = 0
        for (i in 1 until dys.size - 1) if (dys[i] < dys[i - 1] && dys[i] <= dys[i + 1] && dys[i] < -20f) beats++
        check("dance beats at about 2 Hz (8 beats in 4 s)", beats in 7..9, "beats $beats")
        val a = MochiAnimator.pose(MochiState.DANCING, 5f, 3.1f).dy; val b = MochiAnimator.pose(MochiState.DANCING, 5f, 3.1f + 0.49f).dy
        check("dance repeats every 0.49 s", abs(a - b) < 0.05f, "$a vs $b")
        // Working and Thinking: the reference body does not move at all
        val still = listOf(MochiState.WORKING, MochiState.THINKING).all { s ->
            (0 until 300).all { i -> val p = MochiAnimator.pose(s, 5f, i * 0.05f); abs(p.dx) < 0.01f && abs(p.dy) < 0.01f && abs(p.rot) < 2f && abs(p.sy - 1f) < 0.015f }
        }
        check("working and thinking keep the body still, as in the reference", still)
        // Idle breathing: 3.4 s period, small
        val s1 = MochiAnimator.pose(MochiState.IDLE, 9f, 1.0f).sy; val s2 = MochiAnimator.pose(MochiState.IDLE, 9f, 1.0f + 3.4f).sy
        val range = (0 until 340).map { MochiAnimator.pose(MochiState.IDLE, 9f, it * 0.01f).sy }
        check("idle breathes with a 3.4 s period and under 2% amplitude", abs(s1 - s2) < 1e-4 && range.max() - range.min() < 0.04f && range.max() - range.min() > 0.02f, "range ${range.max() - range.min()}")
        // Thinking looks up and to the side
        val th = MochiAnimator.pose(MochiState.THINKING, 5f, 0f)
        check("thinking glances up and to the side", th.eyes.dx > 25f && th.eyes.dy < -15f)
        // Question tilts
        check("question tilts the head about 9 degrees", abs(MochiAnimator.pose(MochiState.QUESTION, 5f, 0f).rot - 9f) < 2.5f)
        // Error shakes then settles
        val sh = (0 until 40).map { abs(MochiAnimator.pose(MochiState.ERROR, it * 0.01f, 9f).dx) }.max()
        check("error shakes on entry and settles", sh > 2f && abs(MochiAnimator.pose(MochiState.ERROR, 3f, 9f).dx) < 0.01f, "peak $sh")
        // Finished hops
        val hop = (0 until 55).map { MochiAnimator.pose(MochiState.FINISHED, it * 0.01f, 9f).dy }.min()
        check("finished hops about 18 px", abs(hop + 18f) < 1.5f, "hop $hop")
    }

    // ---- blinking
    run {
        val dt = 0.01f; val n = 6000
        val open = (0 until n).map { MochiAnimator.blinkOpen(it * dt) }
        var blinks = 0; var inBlink = false; var longest = 0; var run = 0
        for (o in open) { if (o < 0.5f) { if (!inBlink) { blinks++; inBlink = true; run = 0 }; run++; longest = maxOf(longest, run) } else inBlink = false }
        check("blinks about every 4 s (60 s gives 12-18)", blinks in 12..18, "blinks $blinks")
        check("a blink is quick (under 0.15 s)", longest * dt < 0.15f, "longest ${longest * dt}")
        check("eyes are open almost all the time", open.count { it > 0.99f } > n * 0.93)
    }

    // ---- badge pops in with a little overshoot
    run {
        val sc = (0..40).map { MochiAnimator.pose(MochiState.WORKING, it * 0.01f, 5f).badge!!.scale }
        check("badge starts at zero, overshoots, settles at one", sc.first() < 0.05f && sc.max() > 1.02f && abs(sc.last() - 1f) < 0.05f, "first ${sc.first()} max ${sc.max()} last ${sc.last()}")
        check("no badge on calm states", listOf(MochiState.IDLE, MochiState.SLEEPING, MochiState.HAPPY, MochiState.DANCING).all { MochiAnimator.pose(it, 1f, 5f).badge == null })
    }

    // ---- particles and hands
    run {
        check("finished has 3 sparkles", MochiAnimator.pose(MochiState.FINISHED, 1f, 5f).particles.count { it.kind == ParticleKind.SPARKLE } == 3)
        check("proud has gold stars", MochiAnimator.pose(MochiState.PROUD, 1f, 5f).particles.all { it.kind == ParticleKind.STAR_GOLD })
        check("sleeping floats 3 zzz", MochiAnimator.pose(MochiState.SLEEPING, 1f, 5f).particles.count { it.kind == ParticleKind.ZEE } == 3)
        val g = MochiAnimator.pose(MochiState.GREETING, 1f, 5f)
        check("greeting has a dust halo and two hands", g.particles.size == 90 && g.hands.size == 2)
        check("idle has no particles or hands", MochiAnimator.pose(MochiState.IDLE, 1f, 5f).let { it.particles.isEmpty() && it.hands.isEmpty() })
    }

    // ---- the player: cross-fades and one-off expressions
    run {
        val pl = MochiPlayer()
        pl.setBase(MochiState.IDLE, 0)
        val idleTop = Looks.of(MochiState.IDLE).top; val thTop = Looks.of(MochiState.THINKING).top
        pl.setBase(MochiState.THINKING, 10_000)
        check("a switch starts from the old look", pl.frame(10_000).top == idleTop)
        val mid = pl.frame(10_175)
        check("halfway through, colours are in between", mid.top != idleTop && mid.top != thTop && mid.bottom != Looks.of(MochiState.THINKING).bottom)
        check("eyes shut at the midpoint while the shape swaps", mid.eyes.open < 0.25f, "open ${mid.eyes.open}")
        val done = pl.frame(10_400)
        check("after the fade it is exactly the new state", done.top == thTop && done.bottom == Looks.of(MochiState.THINKING).bottom && done.badge != null)
        val t1 = pl.frame(11_000).sy; pl.setBase(MochiState.THINKING, 11_100); val t2 = pl.frame(11_000 + 100)
        check("re-setting the same state does not restart it", pl.state == MochiState.THINKING && abs(t1 - t2.sy) < 0.01f)
        val p2 = MochiPlayer()
        p2.setBase(MochiState.IDLE, 0)
        p2.playOnce(MochiState.GREETING, 1_000, 2_600)
        check("a one-off expression plays", p2.also { it.frame(1_500) }.state == MochiState.GREETING)
        p2.setBase(MochiState.THINKING, 2_000)
        check("a busy phase waits for the one-off to finish", p2.also { it.frame(2_500) }.state == MochiState.GREETING)
        check("then the pet falls back to the current state", p2.also { it.frame(3_700) }.state == MochiState.THINKING)
    }

    // ---- the renderer
    run {
        var problems = ""
        for (s in MochiState.entries) {
            val r = RecGfx()
            MochiRenderer.draw(r, MochiAnimator.pose(s, 0.8f, 6.5f), 372f, 328f)
            if (r.depth != 0) problems += "$s unbalanced(${r.depth}); "
            if (r.bad > 0) problems += "$s non-finite; "
            if (r.fills + r.strokes > 140) problems += "$s too many draw calls (${r.fills + r.strokes}); "
        }
        check("every state renders with balanced save/restore, finite numbers and a draw-call budget", problems.isEmpty(), problems)
        val r = RecGfx(); MochiRenderer.draw(r, MochiAnimator.pose(MochiState.WORKING, 1f, 5f), 372f, 328f)
        check("a typical frame is cheap (under 40 draw calls)", r.fills + r.strokes < 40, "${r.fills + r.strokes}")
        check("stage keeps the measured 340:300 proportions", abs(MochiRenderer.ASPECT - 340f / 300f) < 1e-4f)
        check("ellipse path is 1 move + 4 curves + close", ShapePath.ellipse(0f, 0f, 1f, 1f).ops.size == 6)
    }

    // ---- the thinking aura
    run {
        val auras = MochiState.entries.associateWith { MochiAnimator.pose(it, 1f, 5f).aura }
        check("aura is full while thinking, softer while searching, absent in every other state",
            auras[MochiState.THINKING] == 1f && auras[MochiState.SEARCHING]!! in 0.3f..0.9f && auras.filterKeys { it != MochiState.THINKING && it != MochiState.SEARCHING }.values.all { it == 0f })
        val period = MochiAnimator.AURA_PERIOD_S
        val pulses = (0 until 200).map { MochiAnimator.pose(MochiState.THINKING, 1f, it * period / 100f).auraPulse }
        check("aura breathes between 0 and 1", pulses.min() < 0.02f && pulses.max() > 0.98f && pulses.all { it in 0f..1f })
        check("aura breathes with the 1.4 s cycle", abs(MochiAnimator.pose(MochiState.THINKING, 1f, 3.3f).auraPulse - MochiAnimator.pose(MochiState.THINKING, 1f, 3.3f + period).auraPulse) < 1e-3f)
        val pl = MochiPlayer(); pl.setBase(MochiState.IDLE, 0); pl.frame(1_000)
        pl.setBase(MochiState.THINKING, 10_000)
        val start = pl.frame(10_000).aura; val mid = pl.frame(10_175).aura; val full = pl.frame(10_500).aura
        check("aura fades in with the state change (not a cut)", start < 0.05f && mid > 0.2f && mid < 0.8f && full == 1f, "start $start mid $mid full $full")
        pl.setBase(MochiState.IDLE, 20_000)
        check("aura fades out when thinking ends", pl.frame(20_150).aura in 0.05f..0.95f && pl.frame(20_500).aura == 0f)
        class Box : Gfx {
            var sx = 1f; var sy = 1f; var tx = 0f; var ty = 0f; var maxX = 0f; var maxDown = 0f; var first = true
            val stack = ArrayList<FloatArray>()
            override fun save() { stack.add(floatArrayOf(sx, sy, tx, ty)) }
            override fun restore() { stack.removeAt(stack.size - 1).let { sx = it[0]; sy = it[1]; tx = it[2]; ty = it[3] } }
            override fun translate(dx: Float, dy: Float) { tx += dx * sx; ty += dy * sy }
            override fun rotate(deg: Float) {}
            override fun scale(sx: Float, sy: Float) { this.sx *= sx; this.sy *= sy }
            override fun clip(path: ShapePath) {}
            override fun fill(path: ShapePath, fill: Fill, alpha: Float) {
                // the first radial fill of a frame is the aura halo (it is drawn before the ordinary glow)
                if (fill is Fill.Radial && first) { first = false; maxX = maxOf(maxX, kotlin.math.abs(tx - 170f) + sx); maxDown = maxOf(maxDown, ty + sy) }
            }
            override fun stroke(path: ShapePath, width: Float, argb: Int, alpha: Float) {}
        }
        val box = Box()
        for (t in 0 until 40) { box.first = true; MochiRenderer.draw(box, MochiAnimator.pose(MochiState.THINKING, 2f, t * 0.07f), 340f, 300f) }
        check("aura halo stays inside the 340 x 300 stage (so the pet window never clips it)", box.maxX in 100f..170.5f && box.maxDown in 100f..300.5f, "x ${box.maxX} down ${box.maxDown}")
        val gone = RecGfx(); MochiRenderer.draw(gone, MochiAnimator.pose(MochiState.IDLE, 1f, 5f), 372f, 328f)
        val on = RecGfx(); MochiRenderer.draw(on, MochiAnimator.pose(MochiState.THINKING, 1f, 5f), 372f, 328f)
        check("aura adds only 5 draw calls and stays balanced and finite", on.fills + on.strokes - (RecGfx().also { MochiRenderer.draw(it, MochiAnimator.pose(MochiState.WORKING, 1f, 5f), 372f, 328f) }.let { it.fills + it.strokes }) <= 6 && on.depth == 0 && on.bad == 0 && gone.depth == 0,
            "${on.fills + on.strokes} vs idle ${gone.fills + gone.strokes}")
    }

    // ---- colour helpers
    run {
        check("lerp endpoints", Colors.lerp(0xFF102030.toInt(), 0xFF807060.toInt(), 0f) == 0xFF102030.toInt() && Colors.lerp(0xFF102030.toInt(), 0xFF807060.toInt(), 1f) == 0xFF807060.toInt())
        check("withAlpha scales and clamps", (Colors.withAlpha(0xFFFFFFFF.toInt(), 0.5f) ushr 24) in 127..128 && (Colors.withAlpha(0xFFFFFFFF.toInt(), 3f) ushr 24) == 255)
    }

    println(if (failures == 0) "\nALL MOCHI TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
