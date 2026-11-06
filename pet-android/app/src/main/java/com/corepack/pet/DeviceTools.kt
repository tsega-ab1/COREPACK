package com.corepack.pet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import com.corepack.pet.agent.PhoneArgs
import com.corepack.pet.agent.Tool
import com.corepack.pet.agent.ToolSpec

/** Starts an activity; returns an error message, or null on success. */
private fun Context.launch(intent: Intent): String? {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "error: no app on this phone can do that"
    } catch (e: SecurityException) {
        "error: not allowed (${e.message})"
    }
}

private fun str(args: Map<String, Any?>, key: String) = (args[key] as? String)?.trim().orEmpty()

class SetAlarmTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "set_alarm",
        "Set an alarm in the phone's clock app for a clock time.",
        """{"type":"object","properties":{"hour":{"type":"integer","description":"Hour, 0-23."},"minute":{"type":"integer","description":"Minute, 0-59."},"label":{"type":"string","description":"Optional label."}},"required":["hour"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val (h, m) = PhoneArgs.hourMinute(arguments["hour"], arguments["minute"]) ?: return "error: hour must be 0-23 and minute 0-59"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        str(arguments, "label").takeIf { it.isNotEmpty() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        app.launch(i)?.let { return it }
        return "Asked the clock app to set an alarm for %02d:%02d.".format(h, m)
    }
}

class SetTimerTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "set_timer",
        "Start a countdown timer in the phone's clock app.",
        """{"type":"object","properties":{"seconds":{"type":"integer","description":"Length in seconds, 1 to 86400."},"label":{"type":"string","description":"Optional label."}},"required":["seconds"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val s = PhoneArgs.seconds(arguments["seconds"]) ?: return "error: seconds must be between 1 and 86400"
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        str(arguments, "label").takeIf { it.isNotEmpty() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        app.launch(i)?.let { return it }
        return "Asked the clock app to start a ${s / 60} min ${s % 60} s timer."
    }
}

class OpenAppTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "open_app",
        "Open an app on the phone by its name, like Chrome or Telegram.",
        """{"type":"object","properties":{"name":{"type":"string","description":"The app's name."}},"required":["name"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val name = str(arguments, "name")
        if (name.isEmpty()) return "error: missing 'name'"
        val pm = app.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val apps = launchers.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return when (val pick = PhoneArgs.pickApp(name, apps)) {
            is PhoneArgs.AppPick.One -> {
                val intent = pm.getLaunchIntentForPackage(pick.pkg) ?: return "error: couldn't open ${pick.label}"
                app.launch(intent)?.let { return it }
                "Opened ${pick.label}."
            }
            is PhoneArgs.AppPick.Many -> "More than one app matches. Which one? " + pick.labels.joinToString(", ")
            PhoneArgs.AppPick.None -> "I couldn't find an app called '$name'."
        }
    }
}

class OpenUrlTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "open_url",
        "Open a web link (http or https) in the browser.",
        """{"type":"object","properties":{"url":{"type":"string","description":"The full link."}},"required":["url"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val url = PhoneArgs.httpUrl(arguments["url"] as? String) ?: return "error: only full http or https links can be opened"
        app.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))?.let { return it }
        return "Opened the link."
    }
}

class OpenMapsTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "open_maps",
        "Show a place on the map, or start navigation to it.",
        """{"type":"object","properties":{"query":{"type":"string","description":"A place or address."},"navigate":{"type":"boolean","description":"Start turn-by-turn navigation."}},"required":["query"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val q = str(arguments, "query")
        if (q.isEmpty()) return "error: missing 'query'"
        val navigate = com.corepack.pet.agent.boolArgPublic(arguments["navigate"]) ?: false
        if (navigate) {
            if (app.launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))) == null) return "Started navigation to $q."
        }
        app.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))?.let { return it }
        return "Showing $q on the map."
    }
}

class ComposeSmsTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "compose_sms",
        "Open the messages app with a drafted text. It does NOT send; the user must press send.",
        """{"type":"object","properties":{"to":{"type":"string","description":"Phone number."},"text":{"type":"string","description":"The message."}},"required":["to","text"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val to = PhoneArgs.phoneNumber(arguments["to"] as? String) ?: return "error: that doesn't look like a phone number"
        val text = str(arguments, "text")
        if (text.isEmpty()) return "error: missing 'text'"
        app.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to))).putExtra("sms_body", text))?.let { return it }
        return "Opened the messages app with your draft to $to. Nothing is sent until the user presses send."
    }
}

class DialTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "dial",
        "Open the dialer with a number filled in. It does NOT place the call; the user must press call.",
        """{"type":"object","properties":{"number":{"type":"string","description":"Phone number."}},"required":["number"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val n = PhoneArgs.phoneNumber(arguments["number"] as? String) ?: return "error: that doesn't look like a phone number"
        app.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))?.let { return it }
        return "Opened the dialer with $n. The call is not placed until the user presses call."
    }
}

class FlashlightTool(private val app: Context) : Tool {
    override val spec = ToolSpec(
        "flashlight",
        "Turn the phone's flashlight on or off.",
        """{"type":"object","properties":{"on":{"type":"boolean","description":"true for on, false for off."}},"required":["on"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val on = com.corepack.pet.agent.boolArgPublic(arguments["on"]) ?: return "error: say on or off"
        val cm = app.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "error: this phone has no flashlight"
        return try {
            cm.setTorchMode(id, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "error: couldn't switch the flashlight (${e.message})"
        }
    }
}
