import com.corepack.pet.mochi.MochiAnimator
import com.corepack.pet.mochi.MochiPlayer
import com.corepack.pet.mochi.MochiRenderer
import com.corepack.pet.mochi.MochiState
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders contact sheets (and optionally a frame sequence) so the character can be checked by eye. */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "/tmp/mochi-preview" }).also { it.mkdirs() }

    // ---- contact sheets: every state, still frame at 1.2 s into the state
    fun sheet(states: List<MochiState>, bg: Color, fg: Color, name: String, cols: Int) {
        val cw = 300; val ch = (cw / MochiRenderer.ASPECT).toInt() + 22
        val rows = (states.size + cols - 1) / cols
        val img = BufferedImage(cw * cols, ch * rows, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = bg; g.fillRect(0, 0, img.width, img.height)
        states.forEachIndexed { i, st ->
            val ox = (i % cols) * cw; val oy = (i / cols) * ch
            val g2 = img.createGraphics()
            g2.translate(ox, oy)
            g2.clipRect(0, 0, cw, ch)
            MochiRenderer.draw(Java2DGfx(g2), MochiAnimator.pose(st, 1.2f, 7.31f + i * 0.37f), cw.toFloat(), (ch - 22).toFloat())
            g2.dispose()
            g.color = fg; g.font = Font("SansSerif", Font.BOLD, 13)
            g.drawString(st.name.lowercase().replace('_', ' '), ox + 8, oy + ch - 6)
        }
        g.dispose()
        ImageIO.write(img, "png", File(out, name))
    }
    sheet(MochiState.entries, Color(22, 24, 29), Color(200, 200, 205), "sheet_dark.png", 6)
    sheet(listOf(MochiState.IDLE, MochiState.WORKING, MochiState.THINKING, MochiState.FINISHED, MochiState.ERROR, MochiState.QUESTION), Color(244, 244, 247), Color(60, 60, 70), "sheet_light.png", 6)

    // ---- timeline: the player running through states with its real cross-fades
    if (args.size > 1 && args[1] == "frames") {
        val plan = listOf(
            "greeting" to 2600, "idle" to 1800, "thinking" to 1800, "searching" to 1600, "working" to 1800, "finished" to 2000,
            "happy" to 1600, "question" to 1600, "error" to 1600, "rate_limit" to 1600, "dizzy" to 1600, "sleeping" to 2000, "dancing" to 2000, "idle" to 1200,
        )
        val frames = File(out, "frames").also { it.deleteRecursively(); it.mkdirs() }
        val player = MochiPlayer()
        var t = 0L; var n = 0
        for ((name, ms) in plan) {
            val st = MochiState.valueOf(name.uppercase())
            val end = t + ms
            player.setBase(st, t)
            while (t < end) {
                val pose = player.frame(t)
                val img = BufferedImage(720, 340, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = Color(22, 24, 29); g.fillRect(0, 0, 360, 340)
                g.color = Color(243, 243, 246); g.fillRect(360, 0, 360, 340)
                for (half in 0..1) {
                    val g2 = img.createGraphics(); g2.translate(half * 360, 0); g2.clipRect(0, 0, 360, 340)
                    MochiRenderer.draw(Java2DGfx(g2), pose, 360f, 318f); g2.dispose()
                }
                g.color = Color(150, 150, 160); g.font = Font("SansSerif", Font.BOLD, 15)
                g.drawString(name.replace('_', ' '), 12, 328)
                g.dispose()
                ImageIO.write(img, "png", File(frames, "f%05d.png".format(n++)))
                t += 33
            }
        }
        println("frames: $n")
    }
    println("done")
}
