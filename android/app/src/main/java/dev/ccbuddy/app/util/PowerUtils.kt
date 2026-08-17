package dev.ccbuddy.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the OS is exempting this app from battery optimization -- if
 * not, stricter OEM battery managers can kill the foreground service (and
 * with it the phone<->PC WebSocket) even though it's a proper foreground
 * service with a visible notification.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** Opens the system dialog to request the exemption above. */
fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )

/**
 * There's no programmatic way for an app to un-exempt itself (only the
 * user-facing request-exemption action above has a direct intent). The
 * general battery-optimization list (ACTION_IGNORE_BATTERY_OPTIMIZATION_
 * SETTINGS) is unreliable for this -- stock Android defaults it to
 * showing only apps that *could* still be optimized, hiding ones already
 * exempted unless the user finds and changes a filter dropdown, and
 * OEM skins (Samsung's One UI among them) often restyle or relocate that
 * screen entirely. The app's own details page is the one place every
 * Android version/OEM puts a way to manage this app's battery settings.
 */
fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
