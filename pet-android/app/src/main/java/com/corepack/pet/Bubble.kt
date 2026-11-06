package com.corepack.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** A small speech bubble that appears next to the pet, then fades away. Tap it to open the chat. */
class BubbleWindow(private val context: Context, private val windowManager: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hide = Runnable { dismiss() }

    fun show(text: String, pet: Rect, holdMs: Long, onTap: () -> Unit) {
        dismiss()
        val dm = context.resources.displayMetrics
        val d = dm.density
        val tv = TextView(context).apply {
            this.text = text.take(220)
            textSize = 13f
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF26215C.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 14 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), 0xFFCECBF6.toInt())
            }
            setOnClickListener {
                dismiss()
                onTap()
            }
        }
        val maxW = minOf((230 * d).toInt(), dm.widthPixels - (16 * d).toInt())
        tv.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val w = tv.measuredWidth
        val h = tv.measuredHeight
        val margin = (8 * d).toInt()
        val x = (pet.centerX() - w / 2).coerceIn(margin, (dm.widthPixels - w - margin).coerceAtLeast(margin))
        val above = pet.top - h - (4 * d).toInt()
        val y = if (above >= (24 * d).toInt()) above else pet.bottom + (4 * d).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        windowManager.addView(tv, lp)
        view = tv
        main.postDelayed(hide, holdMs)
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        view?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                // already removed
            }
        }
        view = null
    }
}
