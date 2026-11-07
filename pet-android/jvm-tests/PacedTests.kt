import com.corepack.pet.agent.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Tests for PacedSink: the reply is released in slices, in order, and Stop drops what has not been shown.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

class RecSink : ReplySink {
    val ended = CountDownLatch(1)
    val events = CopyOnWriteArrayList<String>()
    override fun onTool(name: String) { events.add("tool:$name") }
    override fun onToken(text: String) { events.add("tok:$text") }
    override fun onDone(text: String) { events.add("done:$text"); ended.countDown() }
    override fun onError(message: String, unreachable: Boolean) { events.add("err:$message"); ended.countDown() }
    fun shown(): String = events.filter { it.startsWith("tok:") }.joinToString("") { it.removePrefix("tok:") }
    fun wait(seconds: Long = 5): Boolean = ended.await(seconds, TimeUnit.SECONDS)
}

fun main() {
    // ================= slicing
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 2000, maxLagSeconds = 60, tickMs = 5)
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(3)
        p.onToken(text)
        p.onDone(text)
        check("the reply ends", s.wait())
        check("the text is released in several slices", s.events.count { it.startsWith("tok:") } >= 5, s.events.count { it.startsWith("tok:") }.toString())
        check("and nothing is lost or reordered", s.shown() == text)
        check("the final text is passed on", s.events.last() == "done:$text")
    }

    // ================= order
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 2000, maxLagSeconds = 60, tickMs = 2)
        p.onToken("Let me check. ")
        p.onTool("get_weather")
        p.onToken("It is sunny.")
        p.onDone("It is sunny.")
        s.wait()
        val flat = s.events.fold(ArrayList<String>()) { acc, e ->
            if (e.startsWith("tok:") && acc.isNotEmpty() && acc.last().startsWith("tok:")) acc[acc.size - 1] = acc.last() + e.removePrefix("tok:") else acc.add(e)
            acc
        }
        check("a tool note waits for the text before it", flat == listOf("tok:Let me check. ", "tool:get_weather", "tok:It is sunny.", "done:It is sunny."), flat.toString())
    }

    // ================= end-of-reply hook
    run {
        val s = RecSink()
        val order = CopyOnWriteArrayList<String>()
        val p = PacedSink(s, charsPerSecond = 100000, tickMs = 1, beforeEnd = { order.add("hook") })
        p.onToken("hi")
        p.onDone("hi")
        s.wait()
        check("the hook runs once, before the final event", order.toList() == listOf("hook") && s.events.last() == "done:hi")
    }
    run {
        val s = RecSink()
        val hooks = CopyOnWriteArrayList<String>()
        val p = PacedSink(s, charsPerSecond = 100000, tickMs = 1, beforeEnd = { hooks.add("hook") })
        p.onToken("oops")
        p.onError("boom", false)
        s.wait()
        check("an error also goes through, after the text", s.events.last() == "err:boom" && s.shown() == "oops" && hooks.size == 1, s.events.toString())
    }

    // ================= stop
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 1000, maxLagSeconds = 600, tickMs = 10)
        val big = "x".repeat(5000)
        p.onToken(big)
        p.onDone(big)
        Thread.sleep(150)
        p.cancel()
        check("stopping still ends the reply promptly", s.wait(2))
        check("text that had not appeared yet is dropped", s.shown().length in 1..1500, s.shown().length.toString())
        check("and no full-text fallback is sent", s.events.last() == "done:", s.events.last().take(20))
    }
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 1000, maxLagSeconds = 600, tickMs = 10)
        p.cancel()
        p.onTool("remember")
        p.onToken("late text")
        p.onDone("late text")
        s.wait()
        check("after stop, nothing more is shown", s.events == listOf("done:"), s.events.toString())
    }

    // ================= pace
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 100, maxLagSeconds = 1, tickMs = 10)
        val big = "y".repeat(3000)
        val t0 = System.nanoTime()
        p.onToken(big)
        p.onDone(big)
        val ok = s.wait(20)
        val secs = (System.nanoTime() - t0) / 1e9
        // At the base pace of 100 characters a second this would take 30 seconds.
        check("a long backlog speeds up instead of crawling", ok && secs < 10.0 && s.shown() == big, "took ${"%.1f".format(secs)}s")
    }
    run {
        val s = RecSink()
        val p = PacedSink(s, charsPerSecond = 1, maxLagSeconds = 60, tickMs = 200)
        val t0 = System.nanoTime()
        p.onToken("a")
        Thread.sleep(50)
        p.onToken("b")
        p.onDone("ab")
        s.wait(3)
        val secs = (System.nanoTime() - t0) / 1e9
        check("text that trickles in live is not slowed down", s.shown() == "ab" && secs < 1.0, "took ${"%.2f".format(secs)}s")
    }

    // ================= a failing screen
    run {
        val ended = CountDownLatch(1)
        val bad = object : ReplySink {
            override fun onTool(name: String) {}
            override fun onToken(text: String) { throw IllegalStateException("screen closed") }
            override fun onDone(text: String) {}
            override fun onError(message: String, unreachable: Boolean) {}
        }
        val p = PacedSink(bad, charsPerSecond = 100000, tickMs = 1, beforeEnd = { ended.countDown() })
        p.onToken("hello")
        check("if the screen fails, the busy flag is still released", ended.await(3, TimeUnit.SECONDS))
    }

    println(if (failures == 0) "\nALL PACED TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
