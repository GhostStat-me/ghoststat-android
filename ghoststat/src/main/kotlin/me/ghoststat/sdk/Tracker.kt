package me.ghoststat.sdk

import java.io.IOException
import java.util.UUID

/**
 * The in-memory queue: screens, events and heartbeats, sent in batches with retry and backoff.
 * Nothing is written to disk; `launchId` lives as long as the process.
 *
 * Not thread-safe by itself: [GhostStat] runs every call on its own single worker thread.
 * `send` is synchronous, so a flush blocks that worker — never the app's main thread.
 */
class Tracker(
    val configuration: Configuration,
    private val app: AppInfo,
    private val device: () -> DeviceInfo,
    private val transport: Transport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Random per process; the server keys the session on it. */
    val launchId: String = UUID.randomUUID().toString(),
    /** App health: null means no launch items, no diagnostics and nothing remembered. */
    private val history: LaunchHistory? = null,
    /** When the launch started (wall-clock ms): the process start on Android 7+, else when the tracker was made. */
    launchStartedAtMs: Long? = null,
) {
    private val queue = ArrayList<QueuedItem>()
    val queued: List<QueuedItem> get() = queue.toList()
    var currentScreen: String? = null; private set
    var isForeground = false; private set
    /** When the next retry may go out after a failed send; null when not backing off. */
    var retryAtMs: Long? = null; private set
    /** Items dropped so far (refused by the server, out of tries, or over the queue limit). */
    var droppedCount = 0; private set
    var launchRecorded = false; private set
    /** The status of the last send, for [GhostStat.lastError] (a refused key or package shows up here). */
    var lastStatus: Int? = null; private set

    private val startedAt = launchStartedAtMs ?: clock()
    private var sequence = 0L
    private var failures = 0
    private var pendingCampaign: Campaign? = null
    private var pendingRef: String? = null
    private var lastScreenAt: Long? = null

    /** Off: the queue is emptied and nothing is collected until it is switched on again. */
    var isEnabled = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) { queue.clear(); retryAtMs = null; failures = 0; pendingCampaign = null; pendingRef = null }
        }

    /** A full batch is waiting and no retry is pending: the worker should flush now. */
    val wantsFlush: Boolean get() = queue.size >= configuration.maxBatch && retryAtMs == null

    // ---------------------------------------------------------------- recording

    /** A screen came into view. The same screen again within a second is ignored. */
    fun screen(name: String) {
        val n = clean(name, 120)
        if (!isEnabled || n.isEmpty()) return
        val now = clock()
        if (n == currentScreen && lastScreenAt?.let { now - it < 1000 } == true) return
        currentScreen = n
        lastScreenAt = now
        var data = ItemData(name = n)
        if (pendingCampaign != null || pendingRef != null) {           // a deep link lands on the next screen
            data = data.copy(campaign = pendingCampaign, ref = pendingRef)
            pendingCampaign = null
            pendingRef = null
        }
        enqueue(QueuedItem.Kind.SCREEN, data, now)
    }

    /** A named event, with optional string properties and a numeric value (e.g. revenue). */
    fun event(name: String, props: Map<String, String> = emptyMap(), value: Double? = null) {
        val n = clean(name, 120)
        if (!isEnabled || n.isEmpty()) return
        val clean = LinkedHashMap<String, String>()
        for ((k, v) in props.entries.take(25)) {
            val key = clean(k, 40)
            if (key.isNotEmpty()) clean[key] = v.take(200)
        }
        enqueue(QueuedItem.Kind.EVENT, ItemData(name = n, props = clean.ifEmpty { null },
            value = value?.takeIf { it.isFinite() }, screen = currentScreen), clock())
    }

    /** A deep link or App Link opened the app: its utm_* and where it came from go with the next screen. */
    fun handle(url: String) {
        if (!isEnabled) return
        pendingCampaign = Campaign.fromUrl(url)
        pendingRef = Campaign.source(url)
    }

    /** "Still here" — keeps the app counted as active now. Sent by the heartbeat while in the foreground. */
    fun ping() {
        if (!isEnabled) return
        enqueue(QueuedItem.Kind.PING, ItemData(), clock())
    }

    /**
     * App health: the first time the app is in the foreground in this process, one `app_launch` item — install,
     * update or open — with the launch time. Remembers the running version for next time.
     */
    fun recordLaunch() {
        val h = history ?: return
        if (!isEnabled || launchRecorded) return
        launchRecorded = true
        val now = clock()
        val (kind, from) = LaunchInfo.kind(app.version, h.lastVersion(), h.installedAgoMs(now))
        val ms = now - startedAt
        enqueue(QueuedItem.Kind.APP_LAUNCH, ItemData(launch = LaunchInfo(kind, from,
            if (ms in 0..MAX_LAUNCH_MS) ms.toInt() else null)), now)
        app.version?.takeIf { it.isNotEmpty() }?.let { h.remember(it) }
    }

    /** App health: crash / ANR counts found in the app's exit reasons. */
    fun diagnostic(summary: DiagnosticSummary) {
        if (!isEnabled || history == null) return
        enqueue(QueuedItem.Kind.APP_DIAGNOSTIC, ItemData(diagnostic = summary.copy(
            appVersion = summary.appVersion?.let { clean(it, 32) },
            crashes = maxOf(0, summary.crashes), hangs = maxOf(0, summary.hangs),
            cpuExceptions = maxOf(0, summary.cpuExceptions), diskWriteExceptions = maxOf(0, summary.diskWriteExceptions))), clock())
    }

    /** App health: one crash, ANR or kill with its reason and stack (SDK 1.2). */
    fun crash(report: CrashReport) {
        if (!isEnabled || history == null) return
        enqueue(QueuedItem.Kind.APP_CRASH, ItemData(crash = report.copy(
            appVersion = report.appVersion?.let { clean(it, 32) }, appBuild = report.appBuild?.let { clean(it, 32) },
            frames = report.frames.take(CrashReport.MAX_FRAMES), causes = report.causes.take(CrashReport.MAX_CAUSES))), clock())
    }

    private fun enqueue(kind: QueuedItem.Kind, data: ItemData, at: Long) {
        sequence += 1
        queue.add(QueuedItem(sequence, kind, at, data))
        if (queue.size > configuration.maxQueue) {
            val over = queue.size - configuration.maxQueue
            repeat(over) { queue.removeAt(0) }
            droppedCount += over
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends the oldest batch (and keeps going while items are waiting). Returns whether the queue was fully
     * accepted. Does nothing while a retry is not due yet.
     */
    fun flush(): Boolean {
        if (!isEnabled) return false
        retryAtMs?.let { if (clock() < it) return false }
        while (queue.isNotEmpty() && isEnabled) {
            val batch = queue.take(configuration.maxBatch)
            val body = Envelope.encode(configuration.siteKey, app, device(), launchId, batch).toByteArray(Charsets.UTF_8)
            val status = try {
                transport.send(body, configuration.collectUrl)
            } catch (e: IOException) {
                failed(batch)                                           // offline or timed out: keep, try later
                return false
            } catch (e: RuntimeException) {
                failed(batch)
                return false
            }
            lastStatus = status
            when (status) {
                in 200..299 -> { remove(batch, dropped = false); failures = 0; retryAtMs = null }
                408, 425, 429, in 500..599, 0 -> { failed(batch); return false }
                else -> { remove(batch, dropped = true); failures = 0; retryAtMs = null }   // refused: no point retrying
            }
        }
        return queue.isEmpty()
    }

    private fun remove(batch: List<QueuedItem>, dropped: Boolean) {
        val sent = batch.map { it.sequence }.toHashSet()
        val before = queue.size
        queue.removeAll { it.sequence in sent }
        if (dropped) droppedCount += before - queue.size
    }

    private fun failed(batch: List<QueuedItem>) {
        failures += 1
        if (failures >= configuration.maxAttempts) {
            remove(batch, dropped = true)
            failures = 0
            retryAtMs = null
            return
        }
        val delay = minOf(60_000L, configuration.retryBaseMs * (1L shl (failures - 1)))
        retryAtMs = clock() + delay
    }

    // ---------------------------------------------------------------- lifecycle

    /** One beat of the 15-second loop: a heartbeat while in the foreground, then a flush. */
    fun tick() {
        if (isForeground) ping()
        flush()
    }

    /** The app came to the foreground: record the launch (once) and count it as present straight away. */
    fun enterForeground() {
        isForeground = true
        recordLaunch()
        ping()
        flush()
    }

    /** The app left the screen: stop the heartbeat and send what is waiting. */
    fun enterBackground() {
        isForeground = false
        flush()
    }

    companion object {
        /** A launch slower than this is not measured (the process was probably started in the background). */
        const val MAX_LAUNCH_MS = 60_000L

        internal fun clean(s: String, max: Int): String = s.trim().take(max)
    }
}
