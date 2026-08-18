package dev.ccbuddy.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

/**
 * Known per-OEM "sleeping apps" / autostart-management screens that layer
 * on top of stock Android's battery optimization and can still kill a
 * background foreground service even when [isIgnoringBatteryOptimizations]
 * is true. There's no standard API for these -- component names below are
 * the well-known ones cataloged by the dontkillmyapp.com project. Several
 * candidates per manufacturer are listed since the exact activity has
 * moved across OS versions; the first one present on the device wins.
 */
private val OEM_BATTERY_SETTINGS: Map<String, List<ComponentName>> = mapOf(
    "samsung" to listOf(
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
    ),
    "xiaomi" to listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    ),
    "huawei" to listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
    ),
    "oppo" to listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
    ),
    "vivo" to listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
    ),
    "oneplus" to listOf(
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
    )
)

/** Whether this device's manufacturer is known to layer extra battery/autostart management on top of stock Android. */
fun hasKnownOemBatteryManagement(): Boolean =
    Build.MANUFACTURER.lowercase() in OEM_BATTERY_SETTINGS

/**
 * Deep link to this device's OEM-specific battery/autostart management
 * screen, or null if the manufacturer is unrecognized or none of its known
 * candidate activities are present (component names shift across OEM
 * skin versions). Callers should fall back to [appDetailsSettingsIntent]
 * when this returns null or the returned intent fails to launch.
 */
fun oemBatterySettingsIntent(context: Context): Intent? {
    val candidates = OEM_BATTERY_SETTINGS[Build.MANUFACTURER.lowercase()] ?: return null
    // resolveActivity() can throw SecurityException on some OEM builds when
    // querying a component the caller doesn't have package-visibility into
    // (unlike an emulator, where these packages don't exist at all and it
    // just returns null) -- treat that the same as "not present" rather
    // than letting it crash the caller.
    val resolved = candidates.firstOrNull { candidate ->
        try {
            Intent().setComponent(candidate).resolveActivity(context.packageManager) != null
        } catch (e: SecurityException) {
            false
        }
    } ?: return null
    return Intent().setComponent(resolved)
}
