package com.corepack.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        fun text(t: String, size: Float = 14f): TextView = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, dp(10), 0, dp(2))
        }

        fun button(t: String, onClick: () -> Unit): Button = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        val url = EditText(this).apply {
            setSingleLine()
            setText(Prefs.baseUrl(this@MainActivity))
        }
        val token = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.token(this@MainActivity))
            hint = "paste from: corepack gateway token"
        }
        status = text("Start the gateway on the PC first: corepack gateway start")

        col.addView(text("CorePack Pet", 22f))
        col.addView(status)
        col.addView(text("Gateway URL"))
        col.addView(url)
        col.addView(text("Token"))
        col.addView(token)

        col.addView(button("Save and test connection") {
            val u = url.text.toString()
            val t = token.text.toString()
            Prefs.save(this, u, t)
            status.text = "Checking..."
            Thread {
                val result = EngineClient.probe(u, t)
                runOnUiThread { status.text = result }
            }.start()
        })
        col.addView(button("1. Allow drawing over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("2. Start the pet") {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Allow drawing over other apps first."
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
                status.text = "Pet started. Drag it anywhere, tap it to chat."
            }
        })
        col.addView(button("Stop the pet") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            status.text = "Pet stopped."
        })
        col.addView(button("What the pet remembers") {
            val facts = PetAgent.facts(this).all()
            val text = if (facts.isEmpty()) "Nothing saved yet." else facts.joinToString("\n") { "- $it" }
            AlertDialog.Builder(this)
                .setTitle("Memory")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .setNegativeButton("Forget everything") { _, _ -> PetAgent.facts(this).clear() }
                .show()
        })
        col.addView(button("Open chat here") {
            startActivity(Intent(this, ChatActivity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
