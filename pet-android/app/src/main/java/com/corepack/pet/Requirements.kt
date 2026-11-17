package com.corepack.pet

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.corepack.pet.agent.Requirement

/** Checks what Android has allowed, and opens the Settings page where the user can allow the rest. */
object Requirements {
    fun isGranted(app: Context, r: Requirement): Boolean = when (r) {
        // The screen service only exists while the user has switched it on in Settings > Accessibility.
        Requirement.ACCESSIBILITY -> ScreenService.instance != null
        Requirement.EXACT_ALARMS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun grantIntent(app: Context, r: Requirement): Intent = when (r) {
        Requirement.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Requirement.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
