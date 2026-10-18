package me.ghoststat.sdk

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.InputStream

/**
 * Crashes and ANRs: why the app's earlier processes ended.
 *
 * - Counts (since 1.1, Android 11+): Android's own record of why each process ended (ApplicationExitInfo), per app
 *   version. Each process marks its version and build (setProcessStateSummary), so a crash is counted against the
 *   version that crashed even when the record is read after an update.
 * - Details (1.2): one `app_crash` report per crash, ANR or kill-while-on-screen, with the reason and the crashing
 *   thread's stack (see [CrashReport] for exactly what is in it):
 *     Java/Kotlin crash  the uncaught-exception handler writes the exception's class names and stack to a small file
 *                        in the app's no-backup folder; the next launch sends it and deletes it (any Android version)
 *     native crash       Android's tombstone (Android 12+): signal and the crashing thread's frames
 *     ANR                Android's ANR trace (Android 11+): the main thread's Java stack
 * - The time of the newest exit already handled is kept next to the last version ("lastExitCheck"), so nothing is
 *   reported twice. On the first run nothing older is reported: only what happens with GhostStat in the app.
 */
internal object AndroidHealth {
    const val CRASH_FILE = "ghoststat-crash.txt"
    /** At most this many reports per launch (a crash loop does not flood the queue). */
    const val MAX_REPORTS = 10
    /** A Java crash file and Android's record of the same crash are at most this far apart. */
    const val MATCH_WINDOW_MS = 60_000L

    fun markVersion(context: Context, version: String?, build: String?) {
        if (Build.VERSION.SDK_INT < 30 || version.isNullOrEmpty()) return
        try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.setProcessStateSummary(summary(version, build).toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {}
    }

    /** "3.2.0|45": version and build. SDK 1.1 wrote the version alone; [versionOf] reads both. */
    fun summary(version: String, build: String?): String =
        if (build.isNullOrEmpty()) version.take(32) else "${version.take(32)}|${build.take(32)}"

    fun versionOf(summary: String?): Pair<String?, String?> {
        val s = summary?.takeIf { it.isNotBlank() } ?: return null to null
        val v = s.substringBefore('|').ifEmpty { null }
        val b = if ('|' in s) s.substringAfter('|').ifEmpty { null } else null
        return v to b
    }

    // ---------------------------------------------------------------- the uncaught-exception handler

    fun installCrashHandler(context: Context, version: String?, build: String?, enabled: () -> Boolean) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) return
        val dir = try { context.noBackupFilesDir } catch (_: Throwable) { return }
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(File(dir, CRASH_FILE), version, build, current, enabled))
    }

    /** Writes the report, then hands the exception on: Android's own handler still shows the crash and ends the process. */
    internal class CrashHandler(private val file: File, private val version: String?, private val build: String?,
                                private val previous: Thread.UncaughtExceptionHandler?,
                                private val enabled: () -> Boolean = { true }) : Thread.UncaughtExceptionHandler {
        @Volatile private var handling = false

        override fun uncaughtException(t: Thread, e: Throwable) {
            if (!handling && enabled()) {
                handling = true
                try { write(ThrowableReport.from(e, System.currentTimeMillis(), version, build, t.name)) } catch (_: Throwable) {}
            }
            if (previous != null) previous.uncaughtException(t, e)
            else { Process.killProcess(Process.myPid()); System.exit(10) }
        }

        private fun write(r: CrashReport) {
            val tmp = File(file.parentFile, "$CRASH_FILE.tmp")
            tmp.writeText(CrashFile.encode(r), Charsets.UTF_8)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    // ---------------------------------------------------------------- the next launch

    class Found(val summaries: List<DiagnosticSummary>, val reports: List<CrashReport>)

    /** What happened since the last check: counts per version and the crash reports (empty counts below Android 11). */
    fun collect(context: Context, history: AndroidLaunchHistory, nowMs: Long): Found {
        val fileReport = readCrashFile(context)
        if (Build.VERSION.SDK_INT < 30) return Found(emptyList(), listOfNotNull(fileReport))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Found(emptyList(), listOfNotNull(fileReport))
        val since = history.prefs.getLong(AndroidLaunchHistory.KEY_EXIT_CHECK, -1L)
        val infos = try { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) } catch (_: Throwable) {
            return Found(emptyList(), listOfNotNull(fileReport))
        }
        val newest = infos.maxOfOrNull { it.timestamp } ?: nowMs
        history.prefs.edit().putLong(AndroidLaunchHistory.KEY_EXIT_CHECK, maxOf(newest, since)).apply()
        if (since < 0) return Found(emptyList(), listOfNotNull(fileReport))       // first run: start counting from here
        val fresh = infos.filter { it.timestamp > since }
        val exits = fresh.map { e ->
            val (v, b) = versionOf(e.processStateSummary?.let { String(it, Charsets.UTF_8) })
            Exit(e.timestamp, v, e.reason, b, e.status, e.importance, e.pss, e.rss, e.description)
        }
        val traces = HashMap<Long, ByteArray>()
        for (e in fresh) {
            val wanted = (e.reason == REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) || e.reason == REASON_ANR
            if (wanted) readTrace(e)?.let { traces[e.timestamp] = it }
        }
        return Found(summarize(exits), reports(exits, traces, fileReport))
    }

    private fun readTrace(e: ApplicationExitInfo): ByteArray? = try {
        e.traceInputStream?.use { readCapped(it, if (e.reason == REASON_ANR) 512 * 1024 else 4 * 1024 * 1024) }
    } catch (_: Throwable) { null }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readCrashFile(context: Context): CrashReport? = try {
        val f = File(context.noBackupFilesDir, CRASH_FILE)
        if (!f.isFile) null else {
            val r = if (f.length() < 256 * 1024) CrashFile.decode(f.readText(Charsets.UTF_8)) else null
            f.delete()
            r
        }
    } catch (_: Throwable) { null }

    internal class Exit(
        val timestampMs: Long, val version: String?, val reason: Int, val build: String? = null, val status: Int = 0,
        val importance: Int = 0, val pssKb: Long = 0, val rssKb: Long = 0, val description: String? = null,
    )

    /** Crashes (Java and native) and ANRs per version; other exits (user swipe, low memory…) are not counted. */
    internal fun summarize(exits: List<Exit>): List<DiagnosticSummary> =
        exits.filter { it.reason == REASON_CRASH || it.reason == REASON_CRASH_NATIVE || it.reason == REASON_ANR }
            .groupBy { it.version ?: "" }
            .toSortedMap()
            .map { (v, list) ->
                DiagnosticSummary(
                    periodStartMs = list.minOf { it.timestampMs }, periodEndMs = list.maxOf { it.timestampMs },
                    appVersion = v.ifEmpty { null },
                    crashes = list.count { it.reason == REASON_CRASH || it.reason == REASON_CRASH_NATIVE },
                    hangs = list.count { it.reason == REASON_ANR },
                )
            }

    /**
     * One report per crash, native crash and ANR, and per stop by Android while the app was on screen (low memory,
     * excessive resource use, failing to start). A Java crash file is joined to Android's record of the same crash.
     * Newest first, at most [MAX_REPORTS].
     */
    internal fun reports(exits: List<Exit>, traces: Map<Long, ByteArray>, javaFile: CrashReport?): List<CrashReport> {
        var file = javaFile
        val out = ArrayList<CrashReport>()
        for (e in exits.sortedByDescending { it.timestampMs }) {
            val base = CrashReport(kind = "crash", atMs = e.timestampMs, appVersion = e.version, appBuild = e.build,
                exitReason = e.reason, exitStatus = e.status, importance = e.importance,
                pssKb = e.pssKb.takeIf { it > 0 }, rssKb = e.rssKb.takeIf { it > 0 })
            val r: CrashReport? = when (e.reason) {
                REASON_CRASH -> {
                    val f = file
                    if (f != null && Math.abs(f.atMs - e.timestampMs) <= MATCH_WINDOW_MS) {
                        file = null
                        f.copy(exitReason = base.exitReason, exitStatus = base.exitStatus, importance = base.importance,
                            pssKb = base.pssKb, rssKb = base.rssKb, appVersion = f.appVersion ?: e.version, appBuild = f.appBuild ?: e.build)
                    } else base
                }
                REASON_CRASH_NATIVE -> {
                    val t = traces[e.timestampMs]?.let { Tombstone.parse(it) }
                    if (t == null) base.copy(signalName = signalName(e.status))
                    else base.copy(signalName = t.signalName ?: signalName(e.status), signalCode = t.signalCode,
                        faultAddress = t.faultAddress?.let { "0x" + java.lang.Long.toHexString(it) },
                        thread = if (t.isMainThread) "main" else "background", frames = t.frames, framesTruncated = t.truncated)
                }
                REASON_ANR -> {
                    val text = traces[e.timestampMs]?.let { String(it, Charsets.UTF_8) }
                    val (frames, cut) = text?.let { AnrTrace.mainThreadFrames(it) } ?: (emptyList<CrashFrame>() to false)
                    base.copy(kind = "hang", detail = AnrTrace.detail(e.description), thread = "main", frames = frames, framesTruncated = cut)
                }
                REASON_LOW_MEMORY, REASON_MEMORY_LIMITER ->
                    if (e.importance in 1..IMPORTANCE_VISIBLE) base.copy(kind = "killed") else null
                REASON_EXCESSIVE_RESOURCE_USAGE, REASON_INITIALIZATION_FAILURE -> base.copy(kind = "killed")
                else -> null
            }
            if (r != null) out.add(r)
        }
        file?.let { out.add(it) }                    // a Java crash Android has no record of (Android 10 and older)
        return out.sortedByDescending { it.atMs }.take(MAX_REPORTS)
    }

    /** Linux signal numbers (Android): SIGBUS is 7 here, not 10 as on Apple platforms. */
    fun signalName(n: Int): String? = when (n) {
        4 -> "SIGILL"; 5 -> "SIGTRAP"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 31 -> "SIGSYS"
        else -> null
    }

    // ApplicationExitInfo.REASON_* (checked against android.jar, API 37); kept here so the logic is testable on the plain JVM.
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_MEMORY_LIMITER = 17
    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE: foreground (100) and visible (200) count as on screen. */
    const val IMPORTANCE_VISIBLE = 200
}
