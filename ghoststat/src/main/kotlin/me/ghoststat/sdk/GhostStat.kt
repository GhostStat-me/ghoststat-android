package me.ghoststat.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * GhostStat for Android apps: cookieless, no device identifiers, no advertising ID.
 *
 *     GhostStat.configure(this, siteKey = "…")        // once, in Application.onCreate
 *     GhostStat.screen("Settings")                     // screens
 *     GhostStat.event("signup", mapOf("plan" to "pro"))
 *     GhostStat.handle(intent.data)                    // campaigns from deep links
 *     GhostStat.isEnabled = false                      // opt-out
 *
 * Every call returns at once; the work happens on the SDK's own background thread.
 */
object GhostStat {
    /** This SDK's version (also in the download's file name on cdn.ghoststat.me). */
    const val VERSION = "1.2.0"

    @Volatile private var engine: Engine? = null
    @Volatile private var enabled = true

    /**
     * Start tracking. Call once, in Application.onCreate — the launch time for App Insights is measured from the
     * process start. An empty key does nothing.
     *
     * `appHealth` (default on): installs / updates / opens and launch time, crash and ANR counts from Android's exit
     * reasons (Android 11+), and since 1.2 each crash's reason and stack (class names and frames, never the exception
     * message), for the App Insights plugin. It keeps two values in the app's own storage (the last app version seen,
     * the time of the last exit it counted) and, after a Java crash, one small file until the next launch sends it.
     * Off: nothing is stored and none of this is sent.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, siteKey: String, endpoint: String = "https://api.ghoststat.me",
                  enabled: Boolean = true, appHealth: Boolean = true) {
        val key = siteKey.trim()
        if (engine != null || key.isEmpty()) return
        val appContext = context.applicationContext ?: context
        synchronized(this) {
            if (engine != null) return
            val app = AndroidEnvironment.app(appContext)
            val history = if (appHealth) AndroidLaunchHistory(appContext) else null
            val tracker = Tracker(Configuration(key, endpoint), app, { AndroidEnvironment.device(appContext) }, HttpTransport(),
                history = history, launchStartedAtMs = AndroidEnvironment.processStartMs())
            tracker.isEnabled = enabled
            this.enabled = enabled
            val e = Engine(tracker)
            engine = e
            (appContext as? Application)?.registerActivityLifecycleCallbacks(Lifecycle(e))
            if (history != null) {
                AndroidHealth.markVersion(appContext, app.version, app.build)
                AndroidHealth.installCrashHandler(appContext, app.version, app.build) { isEnabled }
                e.post { t ->
                    if (t.isEnabled) {
                        val found = AndroidHealth.collect(appContext, history, System.currentTimeMillis())
                        found.summaries.forEach(t::diagnostic)
                        found.reports.forEach(t::crash)
                    }
                }
            }
            e.start()
        }
    }

    /** Off: nothing is collected or sent, and whatever was waiting is discarded. */
    @JvmStatic
    var isEnabled: Boolean
        get() = engine != null && enabled
        set(value) { enabled = value; engine?.post { it.isEnabled = value } }

    @JvmStatic
    fun screen(name: String) { engine?.post { it.screen(name) } }

    @JvmStatic
    @JvmOverloads
    fun event(name: String, props: Map<String, String> = emptyMap(), value: Double? = null) {
        val copy = HashMap(props)
        engine?.post { it.event(name, copy, value) }
    }

    /** Pass the Uri the app was opened with (intent.data): utm_* and the link's origin go with the next screen. */
    @JvmStatic
    fun handle(uri: Uri?) { uri?.toString()?.let(::handle) }

    @JvmStatic
    fun handle(url: String) { engine?.post { it.handle(url) } }

    /** Send what is waiting now, and wait up to `timeoutMs` for the answer. Not on the main thread. */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 10_000): Boolean = engine?.flushAndWait(timeoutMs) ?: true

    /**
     * The HTTP status of the last send, for checking the setup: 200 = arriving; 422 `not_an_app_site` or
     * 403 `bundle_not_allowed` = the key or the package name is wrong in GhostStat (Sites → the app → App & links).
     */
    @JvmStatic
    val lastStatus: Int? get() = engine?.tracker?.lastStatus

    /** Follows the app in and out of the foreground: heartbeat while visible, a flush when it leaves. */
    private class Lifecycle(private val engine: Engine) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        /** A rotation stops the activity and starts a new one: not a trip to the background. */
        private var rotating = false

        override fun onActivityStarted(activity: Activity) {
            started += 1
            if (started == 1) { if (rotating) rotating = false else engine.post { it.enterForeground() } }
        }

        override fun onActivityStopped(activity: Activity) {
            if (started == 0) return
            started -= 1
            if (started == 0) { if (activity.isChangingConfigurations) rotating = true else engine.post { it.enterBackground() } }
        }

        override fun onActivityResumed(activity: Activity) {
            // configure() called after the first activity started (e.g. from an Activity): catch up here.
            if (started == 0) { started = 1; engine.post { it.enterForeground() } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
