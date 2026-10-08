package com.corepack.pet

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A bottom-sheet style chat that opens over whatever app is underneath. */
class ChatActivity : Activity() {
    companion object {
        @Volatile
        var visible = false
    }

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var action: Button

    private var handle: EngineClient.Handle? = null

    @Volatile
    private var busy = false
    private var liveMsg: ChatStore.Msg? = null
    private var liveView: TextView? = null
    private var gotFirst = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "Chat"
            textSize = 16f
            setTextColor(0xFF26215C.toInt())
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        input = EditText(this).apply {
            hint = "Say something"
            maxLines = 4
            setTextColor(0xFF2C2C2A.toInt())
            setHintTextColor(0xFF888780.toInt())
        }
        action = Button(this).apply {
            text = "Send"
            setOnClickListener { onAction() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action)
        }
        root.addView(title)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)

        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.65f).toInt()
        )
        window.setGravity(Gravity.BOTTOM)

        for (m in ChatStore.snapshot()) addBubble(m)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    private fun addBubble(m: ChatStore.Msg): TextView {
        val tv = TextView(this).apply {
            text = m.text
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            when (m.role) {
                "user" -> {
                    bg.setColor(0xFFCECBF6.toInt())
                    setTextColor(0xFF26215C.toInt())
                }
                "error" -> {
                    bg.setColor(0xFFFCEBEB.toInt())
                    setTextColor(0xFF791F1F.toInt())
                }
                else -> {
                    bg.setColor(0xFFF1EFE8.toInt())
                    setTextColor(0xFF2C2C2A.toInt())
                }
            }
            background = bg
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6)
        lp.gravity = if (m.role == "user") Gravity.END else Gravity.START
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun onAction() {
        if (busy) {
            handle?.cancel()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addBubble(ChatStore.add("user", text))
        val reply = ChatStore.add("assistant", "")
        liveMsg = reply
        liveView = addBubble(reply)
        gotFirst = false
        busy = true
        action.text = "Stop"
        PetBus.set(Phase.THINKING)
        handle = EngineClient.stream(this, ChatStore.forRequest(), listener)
    }

    private fun finishTurn() {
        busy = false
        runOnUiThread { if (!isDestroyed) action.text = "Send" }
    }

    private val listener = object : EngineClient.Listener {
        override fun onEvent(name: String) {
            when (name) {
                "queued" -> PetBus.set(Phase.QUEUED)
                "upstream_start" -> PetBus.set(Phase.THINKING)
                "throttle" -> PetBus.set(Phase.THROTTLED)
            }
        }

        override fun onToken(text: String) {
            val m = liveMsg ?: return
            m.text = m.text + text
            if (!gotFirst) {
                gotFirst = true
                PetBus.set(Phase.TALKING)
            }
            val snapshot = m.text
            runOnUiThread {
                if (!isDestroyed) {
                    liveView?.text = snapshot
                    scroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        override fun onDone() {
            val m = liveMsg
            if (m != null && m.text.isEmpty()) {
                m.text = "(no reply)"
                runOnUiThread { if (!isDestroyed) liveView?.text = m.text }
            }
            PetBus.setThenIdle(Phase.SETTLE, 1800)
            finishTurn()
        }

        override fun onError(message: String, unreachable: Boolean) {
            if (unreachable) PetBus.set(Phase.OFFLINE) else PetBus.setThenIdle(Phase.ERROR, 3000)
            val live = liveMsg
            val emptyLive = live != null && live.text.isEmpty()
            if (live != null && emptyLive) ChatStore.remove(live)
            val shown = if (unreachable) {
                "Can't reach the gateway. Is it running on the PC with the phone plugged in?"
            } else {
                message
            }
            val err = ChatStore.add("error", shown)
            runOnUiThread {
                if (!isDestroyed) {
                    val v = liveView
                    if (emptyLive && v != null) list.removeView(v)
                    addBubble(err)
                }
            }
            finishTurn()
        }
    }
}
