package me.ghoststat.sdk

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration as AndroidConfiguration
import android.os.Build
import android.os.Process
import android.os.SystemClock

/** What the SDK reads from the phone: the app's package and version, and the device fields GhostStat shows. */
internal object AndroidEnvironment {
    fun app(context: Context): AppInfo {
        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) { null }
        val build = info?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
        }
        return AppInfo(context.packageName, info?.versionName, build)
    }

    /** Read fresh each time: the screen size changes with rotation and split screen. */
    fun device(context: Context): DeviceInfo {
        val res = context.resources
        val cfg = res.configuration
        val metrics = res.displayMetrics
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val type = (ui?.currentModeType ?: (cfg.uiMode and AndroidConfiguration.UI_MODE_TYPE_MASK))
        val deviceClass = when {
            type == AndroidConfiguration.UI_MODE_TYPE_TELEVISION -> DeviceInfo.DeviceClass.TV
            type == AndroidConfiguration.UI_MODE_TYPE_WATCH -> DeviceInfo.DeviceClass.WATCH
            type == AndroidConfiguration.UI_MODE_TYPE_DESK -> DeviceInfo.DeviceClass.DESKTOP
            cfg.smallestScreenWidthDp >= 600 -> DeviceInfo.DeviceClass.TABLET
            else -> DeviceInfo.DeviceClass.PHONE
        }
        val density = metrics.density.toDouble().takeIf { it > 0 }
        return DeviceInfo(
            osVersion = Build.VERSION.RELEASE ?: "",
            model = model(Build.MANUFACTURER, Build.MODEL),
            deviceClass = deviceClass,
            screenWidthDp = density?.let { Math.round(metrics.widthPixels / it).toInt() },
            screenHeightDp = density?.let { Math.round(metrics.heightPixels / it).toInt() },
            density = density?.let { Math.round(it * 10) / 10.0 },
        )
    }

    /** "Google Pixel 8", "Samsung SM-S918B" — the model, not the device. */
    fun model(manufacturer: String?, model: String?): String {
        val m = (model ?: "").trim()
        val maker = (manufacturer ?: "").trim().replaceFirstChar { it.uppercase() }
        return when {
            maker.isEmpty() -> m
            m.isEmpty() -> maker
            m.startsWith(maker, ignoreCase = true) -> maker + m.substring(maker.length)
            else -> "$maker $m"
        }.take(64)
    }

    /** When this process started, as wall-clock ms (Android 7+); null before that. */
    fun processStartMs(): Long? =
        if (Build.VERSION.SDK_INT >= 24) System.currentTimeMillis() - (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) else null
}
