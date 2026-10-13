package com.corepack.pet

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.corepack.pet.agent.Tool
import com.corepack.pet.agent.ToolSpec

/** Reads the phone's battery level and charging state. */
class BatteryTool(private val context: Context) : Tool {
    override val spec = ToolSpec("get_battery", "Get the phone's battery level and whether it is charging.")

    override fun run(arguments: Map<String, Any?>): String {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "error: battery information is unavailable"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "error: battery information is unavailable"
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "${level * 100 / scale}%, ${if (charging) "charging" else "not charging"}"
    }
}
