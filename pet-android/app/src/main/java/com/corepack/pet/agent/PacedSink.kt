package com.corepack.pet.agent

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** What the chat screen needs to hear about a reply. */
interface ReplySink {
    fun onTool(name: String)
    fun onToken(text: String)
    fun onDone(text: String)
    fun onError(message: String, unreachable: Boolean)
}

/**
 * Hands a reply to the chat at a readable pace. Gemini can send a whole story in about a second, which leaves no
 * time to read along or press Stop; this releases the text in small slices instead. Events keep their order: a
 * tool note, or the end of the reply, only arrives after the text before it has been shown. When a lot of text is
 * waiting, the pace picks up (waiting characters divided by [maxLagSeconds], per second), so a long reply does not
 * crawl on for a minute.
 *
 * [beforeEnd] runs just before the final event reaches [out] (and also if this sink dies unexpectedly).
 */
class PacedSink(
    private val out: ReplySink,
    private val charsPerSecond: Int = 160,
    private val maxLagSeconds: Int = 8,
    private val tickMs: Long = 30,
    private val beforeEnd: () -> Unit = {},
) : ReplySink {
    private sealed class Item {
        class Text(val text: String) : Item()
        class Tool(val name: String) : Item()
        class Done(val text: String) : Item()
        class Error(val message: String, val unreachable: Boolean) : Item()
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val waiting = AtomicInteger(0)

    @Volatile
    private var skipping = false

    init {
        Thread { loop() }.apply {
            isDaemon = true
            start()
        }
    }

    override fun onToken(text: String) {
        if (text.isEmpty()) return
        waiting.addAndGet(text.length)
        queue.add(Item.Text(text))
    }

    override fun onTool(name: String) {
        queue.add(Item.Tool(name))
    }

    override fun onDone(text: String) {
        queue.add(Item.Done(text))
    }

    override fun onError(message: String, unreachable: Boolean) {
        queue.add(Item.Error(message, unreachable))
    }

    /** Drops the text that has not been shown yet. The final event still arrives, so the screen can reset. */
    fun cancel() {
        skipping = true
    }

    private fun loop() {
        var ended = false
        try {
            while (true) {
                when (val item = queue.take()) {
                    is Item.Text -> show(item.text)
                    is Item.Tool -> if (!skipping) out.onTool(item.name)
                    is Item.Done -> {
                        ended = true
                        beforeEnd()
                        out.onDone(if (skipping) "" else item.text)
                        return
                    }
                    is Item.Error -> {
                        ended = true
                        beforeEnd()
                        out.onError(item.message, item.unreachable)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // The screen failed or this was interrupted: give up on this reply, but still release the busy flag below.
        } finally {
            if (!ended) beforeEnd()
        }
    }

    private fun show(text: String) {
        var i = 0
        while (i < text.length && !skipping) {
            val perSecond = maxOf(charsPerSecond, waiting.get() / maxOf(1, maxLagSeconds))
            val n = maxOf(1, (perSecond * tickMs / 1000).toInt())
            val end = minOf(text.length, i + n)
            out.onToken(text.substring(i, end))
            waiting.addAndGet(-(end - i))
            i = end
            // Only wait when more is queued: text that trickles in live is never slowed down.
            if (waiting.get() > 0) Thread.sleep(tickMs)
        }
        if (i < text.length) waiting.addAndGet(-(text.length - i))
    }
}
