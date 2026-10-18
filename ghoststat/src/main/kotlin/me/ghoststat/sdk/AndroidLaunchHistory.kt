package me.ghoststat.sdk

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * The default [LaunchHistory]: the last version string in the app's own SharedPreferences file "ghoststat"
 * (key "lastVersion"), and when the app was installed from PackageInfo.firstInstallTime.
 */
internal class AndroidLaunchHistory(private val context: Context) : LaunchHistory {
    internal val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun lastVersion(): String? = prefs.getString(KEY_VERSION, null)

    override fun remember(version: String) { prefs.edit().putString(KEY_VERSION, version).apply() }

    override fun installedAgoMs(nowMs: Long): Long? = try {
        @Suppress("DEPRECATION")
        val first = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        if (first > 0) nowMs - first else null
    } catch (_: PackageManager.NameNotFoundException) { null }

    companion object {
        const val PREFS = "ghoststat"
        const val KEY_VERSION = "lastVersion"
        const val KEY_EXIT_CHECK = "lastExitCheck"
    }
}
