package me.ghoststat.sdk

/**
 * What the SDK needs to tell an install from an update from an ordinary open (App health).
 * The only thing it keeps is the last app version seen — a string like "2.3.0" — in the app's own storage.
 */
interface LaunchHistory {
    /** The version seen on the previous launch, or null on the first launch with the SDK. */
    fun lastVersion(): String?
    fun remember(version: String)
    /** How long ago the app was installed, when known. */
    fun installedAgoMs(nowMs: Long): Long?
}
