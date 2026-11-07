package com.corepack.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small menu that opens next to the pet when you long-press it.
 * Rows are 48 dp tall, it fades and scales in, and it closes on a tap outside, a tap on a row, or after a while.
 */
class PetMenu(private val context: Context, private val windowManager: WindowManager) {
    class Item(val label: String, val destructive: Boolean = false, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    val isShowing: Boolean get() = view != null

    fun show(items: List<Item>, pet: Rect, holdMs: Long = 10_000L) {
        dismissNow()
        val dm = context.resources.displayMetrics
        val d = dm.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xF5FFFFFF.toInt())
                cornerRadius = 22 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), 0xFFE4E1F7.toInt())
            }
            // The window gets ACTION_OUTSIDE when you touch anything else: close then.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else false
            }
        }
        for (item in items) {
            card.addView(TextView(context).apply {
                text = item.label
                textSize = 15f
                setTextColor(if (item.destructive) 0xFFC0334F.toInt() else 0xFF26215C.toInt())
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (48 * d).toInt()
                minWidth = (176 * d).toInt()
                setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x1F7F5BDF))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                isClickable = true
                setOnClickListener {
                    dismiss()
                    item.action()
                }
            })
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val w = card.measuredWidth
        val h = card.measuredHeight
        val margin = (8 * d).toInt()
        val x = (pet.centerX() - w / 2).coerceIn(margin, (dm.widthPixels - w - margin).coerceAtLeast(margin))
        val above = pet.top - h - (4 * d).toInt()
        val placeAbove = above >= (24 * d).toInt()
        val y = if (placeAbove) above else (pet.bottom + (4 * d).toInt()).coerceAtMost((dm.heightPixels - h - margin).coerceAtLeast(margin))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.pivotX = w / 2f
        card.pivotY = if (placeAbove) h.toFloat() else 0f
        windowManager.addView(card, lp)
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        view = card
        main.postDelayed(hide, holdMs)
    }

    /** Fades out, then removes. */
    fun dismiss() {
        val v = view ?: return
        main.removeCallbacks(hide)
        view = null
        v.animate().alpha(0f).setDuration(120).withEndAction { removeQuietly(v) }.start()
    }

    /** Removes at once (used when the service goes away). */
    fun dismissNow() {
        main.removeCallbacks(hide)
        val v = view ?: return
        view = null
        v.animate().cancel()
        removeQuietly(v)
    }

    private fun removeQuietly(v: View) {
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            // already removed
        }
    }
}
