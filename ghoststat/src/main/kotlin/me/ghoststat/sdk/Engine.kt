package me.ghoststat.sdk

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs a [Tracker] on one background thread: every call is queued onto it, the 15-second loop runs on it, and
 * retries are scheduled on it. The app's main thread never waits for the network.
 */
class Engine(
    val tracker: Tracker,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GhostStat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    },
) {
    private var loop: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var retryFor: Long? = null

    /** Runs `block` on the worker, then sends a full batch and schedules a pending retry. */
    fun post(block: (Tracker) -> Unit) {
        try {
            executor.execute {
                try { block(tracker); settle() } catch (_: Throwable) { /* analytics never crashes the app */ }
            }
        } catch (_: Throwable) { /* shut down */ }
    }

    /** Starts the heartbeat / flush loop. */
    fun start() {
        if (loop != null) return
        val every = tracker.configuration.intervalMs
        loop = executor.scheduleWithFixedDelay({
            try { tracker.tick(); settle() } catch (_: Throwable) {}
        }, every, every, TimeUnit.MILLISECONDS)
    }

    /** Sends what is waiting and waits up to `timeoutMs` for the answer (tests, and a last flush on the way out). */
    fun flushAndWait(timeoutMs: Long = 10_000): Boolean = try {
        executor.submit<Boolean> { tracker.flush().also { settle() } }.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) { false }

    fun shutdown() { executor.shutdownNow() }

    private fun settle() {
        if (tracker.wantsFlush) tracker.flush()
        val at = tracker.retryAtMs
        if (at == null) { retry?.cancel(false); retry = null; retryFor = null; return }
        if (retryFor == at) return
        retry?.cancel(false)
        retryFor = at
        val delay = maxOf(0L, at - System.currentTimeMillis())
        retry = executor.schedule({
            try { retryFor = null; tracker.flush(); settle() } catch (_: Throwable) {}
        }, delay, TimeUnit.MILLISECONDS)
    }
}
