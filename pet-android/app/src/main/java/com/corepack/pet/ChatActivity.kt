package com.corepack.pet

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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

        private const val MAX_CONTEXT = 6000
        private val QUICK_ACTIONS = listOf(
            "Summarize" to "Summarize this in two or three sentences:",
            "Translate" to "Translate this to English:",
            "Explain" to "Explain this simply:",
            "Save as note" to "Save this as a note with a short title:",
            "Draft reply" to "Draft a short, polite reply to this message:",
        )
    }

    private lateinit var title: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var action: Button

    private val views = HashMap<ChatStore.Msg, TextView>()

    /** Text shared into the pet (Share, or "Ask pet" on selected text), waiting to be used. */
    private var contextText: String? = null
    private lateinit var contextBar: LinearLayout
    private lateinit var contextSnippet: TextView

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
        contextBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(4))
        }
        contextSnippet = TextView(this).apply {
            textSize = 12f
            maxLines = 3
            setTextColor(0xFF5F5E5A.toInt())
            setTypeface(typeface, Typeface.ITALIC)
        }
        contextBar.addView(contextSnippet)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, prompt) in QUICK_ACTIONS) {
            chips.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { useContext(label, prompt) }
            })
        }
        chips.addView(Button(this).apply {
            text = "x"
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { clearContext() }
        })
        contextBar.addView(HorizontalScrollView(this).apply { addView(chips) })

        root.addView(title)
        root.addView(contextBar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)

        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.65f).toInt()
        )
        window.setGravity(Gravity.BOTTOM)

        for (m in ChatStore.snapshot()) render(m)
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** Text arriving from another app: Share to the pet, or "Ask pet" in the selection menu. */
    private fun handleIncoming(i: Intent?) {
        val shared = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.trim()?.take(MAX_CONTEXT)
        if (shared.isNullOrEmpty()) return
        contextText = shared
        contextSnippet.text = "Selected: " + shared.replace('\n', ' ').take(160) + if (shared.length > 160) "..." else ""
        contextBar.visibility = View.VISIBLE
    }

    private fun clearContext() {
        contextText = null
        contextBar.visibility = View.GONE
    }

    private fun useContext(label: String, prompt: String) {
        val ctx = contextText ?: return
        val shown = label + ": " + ctx.replace('\n', ' ').take(80) + if (ctx.length > 80) "..." else ""
        submit(shown, prompt + "\n\n" + ctx)
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
        val ctx = contextText
        if (ctx != null) submit("$text  (with the selected text)", text + "\n\n---\n" + ctx) else submit(text, text)
    }

    /** [shown] is what the chat displays; [sent] is what the model receives. */
    private fun submit(shown: String, sent: String) {
        clearContext()
        render(ChatStore.add("user", shown))
        live = null
        action.text = "Stop"
        title.text = "Chat - thinking..."
        PetBus.set(Phase.THINKING)
        PetAgent.send(this, sent, agentListener)
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
