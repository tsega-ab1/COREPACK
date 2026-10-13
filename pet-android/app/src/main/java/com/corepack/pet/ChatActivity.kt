package com.corepack.pet

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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

/**
 * A bottom-sheet style chat that opens over whatever app is underneath.
 *
 * The message list ([ChatStore]) is updated on the worker thread, so a reply keeps being recorded
 * even if the sheet is dismissed mid-answer. Views are only touched on the UI thread.
 */
class ChatActivity : Activity() {
    companion object {
        @Volatile
        var visible = false
    }

    private lateinit var title: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var action: Button

    private val views = HashMap<ChatStore.Msg, TextView>()

    @Volatile
    private var live: ChatStore.Msg? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        title = TextView(this).apply {
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
            text = if (PetAgent.busy) "Stop" else "Send"
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

        for (m in ChatStore.snapshot()) render(m)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    /** UI thread only: show or update the bubble for [m]. */
    private fun render(m: ChatStore.Msg) {
        val existing = views[m]
        if (existing != null) {
            existing.text = m.text
        } else {
            views[m] = addBubble(m)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** UI thread only. */
    private fun unrender(m: ChatStore.Msg) {
        views.remove(m)?.let { list.removeView(it) }
    }

    private fun addBubble(m: ChatStore.Msg): TextView {
        val isNote = m.role == "tool"
        val tv = TextView(this).apply {
            text = m.text
            if (isNote) {
                textSize = 12f
                setTextColor(0xFF888780.toInt())
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            } else {
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
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(if (isNote) 2 else 6)
        lp.gravity = if (m.role == "user") Gravity.END else Gravity.START
        list.addView(tv, lp)
        return tv
    }

    private fun onAction() {
        if (PetAgent.busy) {
            PetAgent.cancel()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        render(ChatStore.add("user", text))
        live = null
        action.text = "Stop"
        title.text = "Chat - thinking..."
        PetBus.set(Phase.THINKING)
        PetAgent.send(this, text, agentListener)
    }

    private fun finishTurn() {
        ui {
            action.text = "Send"
            title.text = "Chat"
        }
    }

    // Callbacks arrive on the worker thread.
    private val agentListener = object : PetAgent.UiListener {
        override fun onTool(name: String) {
            val current = live
            live = null
            if (current != null && current.text.isEmpty()) {
                ChatStore.remove(current)
                ui { unrender(current) }
            }
            val note = ChatStore.add("tool", "used $name")
            ui {
                title.text = "Chat - using $name..."
                render(note)
            }
        }

        override fun onToken(text: String) {
            val m = live ?: ChatStore.add("assistant", "").also { live = it }
            m.text = m.text + text
            ui {
                title.text = "Chat"
                render(m)
            }
        }

        override fun onDone(text: String) {
            if (live == null && text.isNotEmpty()) {
                val m = ChatStore.add("assistant", text)
                ui { render(m) }
            }
            live = null
            finishTurn()
        }

        override fun onError(message: String, unreachable: Boolean) {
            val current = live
            live = null
            if (current != null && current.text.isEmpty()) {
                ChatStore.remove(current)
                ui { unrender(current) }
            }
            val err = ChatStore.add("error", message)
            ui { render(err) }
            finishTurn()
        }
    }
}
