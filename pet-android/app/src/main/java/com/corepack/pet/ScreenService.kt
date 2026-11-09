package com.corepack.pet

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The pet's eye. Read-only: it can list what is on screen, never tap or type.
 * Android only starts it after the user switches it on in Settings > Accessibility.
 */
class ScreenService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** The screen as text: the app, then one line per visible text, button or field. */
    fun readScreen(): String {
        val root = targetRoot() ?: return "error: nothing readable is on screen right now"
        val m = resources.displayMetrics
        val out = StringBuilder("App: ${root.packageName}\nScreen: ${m.widthPixels}x${m.heightPixels}\n")
        val count = intArrayOf(0)
        walk(root, out, count)
        if (count[0] >= MAX_NODES || out.length >= MAX_CHARS) out.append("(cut off: the screen has more)\n")
        return out.toString()
    }

    /** The app under the pet: the top app window that is not our own chat sheet. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
        for (w in apps) {
            val r = w.root ?: continue
            if (r.packageName?.toString() != packageName) return r
        }
        return rootInActiveWindow
    }

    private fun walk(node: AccessibilityNodeInfo, out: StringBuilder, count: IntArray) {
        if (count[0] >= MAX_NODES || out.length >= MAX_CHARS) return
        if (!node.isVisibleToUser) return
        val text = if (node.isPassword) "(password hidden)" else node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (node.isEditable && text.isEmpty()) node.hintText?.toString()?.trim().orEmpty() else ""
        if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || node.isClickable || node.isEditable || node.isCheckable) {
            count[0]++
            val r = Rect().also { node.getBoundsInScreen(it) }
            val flags = buildList {
                if (node.isClickable) add("tap")
                if (node.isEditable) add("edit")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isScrollable) add("scroll")
                if (node.isSelected) add("selected")
            }
            out.append('[').append(count[0]).append("] ")
                .append(node.className?.toString()?.substringAfterLast('.') ?: "View")
            if (text.isNotEmpty()) out.append(" \"").append(text.take(120)).append('"')
            if (desc.isNotEmpty() && desc != text) out.append(" {").append(desc.take(80)).append('}')
            if (hint.isNotEmpty()) out.append(" hint:").append(hint.take(60))
            if (flags.isNotEmpty()) out.append(" (").append(flags.joinToString(",")).append(')')
            out.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out, count) }
    }

    companion object {
        @Volatile var instance: ScreenService? = null
        private const val MAX_NODES = 120
        private const val MAX_CHARS = 6000
    }
}
