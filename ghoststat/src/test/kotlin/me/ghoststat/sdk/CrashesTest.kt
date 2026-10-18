package me.ghoststat.sdk

import org.junit.Assert.*
import org.junit.Test

/**
 * Crash details (SDK 1.2). The tombstone and the ANR trace are real Android output, from a test app crashed on an
 * Android 16 emulator; the tombstone was cut down to the fields the SDK reads.
 */
@Suppress("UNCHECKED_CAST")
class CrashesTest {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test fun aRealTombstone() {
        val t = Tombstone.parse(resource("tombstone-sigsegv.pb"))!!
        assertEquals(11, t.signalNumber)
        assertEquals("SIGSEGV", t.signalName)
        assertEquals("SI_USER", t.signalCode)
        assertNull("a signal sent with kill() has no fault address", t.faultAddress)
        assertEquals("tstat.crashtest", t.threadName)
        assertTrue("tid == pid: the main thread", t.isMainThread)
        assertEquals("kill", t.frames[0].symbol)
        assertEquals("libc.so", t.frames[0].image)
        assertEquals(861704L, t.frames[0].offset)
        assertEquals("2445fc73ede03b9536eef49355aff375", t.frames[0].buildId)
        assertTrue("the runtime's interpreter frames are left out", t.frames.none { it.image == "libart.so" })
        val app = t.frames.filter { it.inApp }
        assertEquals(listOf("t.crash.Main.onCreate"), app.map { it.symbol })
        assertEquals("no install path, only the file name", "base.apk", app[0].image)
        assertTrue(t.frames.any { it.symbol == "android.app.Activity.performCreate" && !it.inApp })
        assertFalse(t.truncated)
    }

    @Test fun aBrokenTombstoneIsNull() {
        val b = resource("tombstone-sigsegv.pb")
        assertNull(Tombstone.parse(b.copyOf(b.size / 3)))
        assertNull(Tombstone.parse(byteArrayOf(0x0a, 0x7f)))
    }

    @Test fun aRealAnrTrace() {
        val text = String(resource("anr-main-sleep.txt"), Charsets.UTF_8)
        val (frames, cut) = AnrTrace.mainThreadFrames(text)
        assertFalse(cut)
        assertEquals(listOf("java.lang.Thread.sleep", "java.lang.Thread.sleep", "java.lang.Thread.sleep", "t.crash.Main.onCreate\$lambda\$0",
            "t.crash.Main\$\$ExternalSyntheticLambda0.run", "android.os.Handler.handleCallback", "android.os.Handler.dispatchMessage",
            "android.os.Looper.loopOnce", "android.os.Looper.loop", "android.app.ActivityThread.main", "java.lang.reflect.Method.invoke",
            "com.android.internal.os.RuntimeInit\$MethodAndArgsCaller.run", "com.android.internal.os.ZygoteInit.main"), frames.map { it.symbol })
        assertNull("Native method: no file", frames[0].file)
        assertEquals("Main.kt", frames[3].file); assertEquals(18, frames[3].line); assertTrue(frames[3].inApp)
        assertFalse(frames[0].inApp)
        assertEquals("Input dispatching timed out · waited 5001ms", AnrTrace.detail(
            "bg anr: Input dispatching timed out (b1f9597 me.ghoststat.crashtest/t.crash.Main is not responding. Waited 5001ms for TouchModeEvent(inTouchMode=true))."))
        assertEquals("seen on the emulator when the frozen app was closed", "Input dispatching timed out · waited 5022ms", AnrTrace.detail(
            "user request after error: Input dispatching timed out (5b2c1d0 me.ghoststat.sdk12test/t.sdk12.Main is not responding. Waited 5022ms for MotionEvent)."))
        assertNull(AnrTrace.detail("  "))
        assertTrue(AnrTrace.mainThreadFrames("no main thread here").first.isEmpty())
    }

    private class Boom(cause: Throwable) : IllegalStateException("PRIVATE outer message", cause)

    @Test fun anExceptionKeepsClassesAndFramesButNeverItsMessage() {
        val root = try { listOf(1)[3]; null } catch (e: IndexOutOfBoundsException) { e }!!
        val r = ThrowableReport.from(Boom(RuntimeException("PRIVATE inner", root)), 1_790_000_000_000, "2.0", "20", "main")
        assertEquals("crash", r.kind)
        assertEquals(Boom::class.java.name, r.exceptionName)
        assertEquals(listOf("java.lang.RuntimeException", root.javaClass.name), r.causes)
        assertEquals("main", r.thread)
        assertTrue("the root cause's stack", r.frames.isNotEmpty())
        assertTrue(r.frames.any { it.symbol?.startsWith("me.ghoststat.sdk.CrashesTest.") == true && it.inApp })
        val j = Json().obj { r.write(this) }.toString()
        assertFalse(j.contains("PRIVATE")); assertFalse(j.contains("message"))
        assertEquals("background", ThrowableReport.from(RuntimeException("x"), 1, null, null, "OkHttp Dispatcher").thread)
    }

    @Test fun aSelfCausedExceptionDoesNotLoop() {
        val e = object : RuntimeException("x") { override val cause: Throwable get() = this }
        val r = ThrowableReport.from(e, 1, null, null, null)
        assertTrue(r.causes.isEmpty())
    }

    @Test fun theCrashFileRoundTrips() {
        val r = ThrowableReport.from(IllegalArgumentException("secret", NullPointerException()), 1_790_000_000_123, "2.0", "20", "main")
        val back = CrashFile.decode(CrashFile.encode(r))!!
        assertEquals(r, back)
        assertNull(CrashFile.decode("something else"))
        assertNull("no time", CrashFile.decode("gscrash\t1\nexception\tX\n"))
        val tabs = CrashFile.decode(CrashFile.encode(CrashReport("crash", 5, exceptionName = "a\tb\nc")))!!
        assertEquals("a b c", tabs.exceptionName)
    }

    @Test fun versionAndBuildInTheProcessSummary() {
        assertEquals("3.2.0|45", AndroidHealth.summary("3.2.0", "45"))
        assertEquals("3.2.0", AndroidHealth.summary("3.2.0", null))
        assertEquals("3.2.0" to "45", AndroidHealth.versionOf("3.2.0|45"))
        assertEquals("written by SDK 1.1", "3.2.0" to null, AndroidHealth.versionOf("3.2.0"))
        assertEquals(null to null, AndroidHealth.versionOf(" "))
    }

    private fun exit(ts: Long, reason: Int, importance: Int = 100, status: Int = 0, description: String? = null) =
        AndroidHealth.Exit(ts, "2.0", reason, "20", status, importance, 51_200, 140_000, description)

    @Test fun exitsBecomeReports() {
        val file = ThrowableReport.from(IllegalStateException(), 10_500, "2.0", "20", "main")
        val traces = mapOf(30_000L to resource("tombstone-sigsegv.pb"), 40_000L to resource("anr-main-sleep.txt"))
        val out = AndroidHealth.reports(listOf(
            exit(10_000, AndroidHealth.REASON_CRASH),
            exit(30_000, AndroidHealth.REASON_CRASH_NATIVE, status = 11),
            exit(40_000, AndroidHealth.REASON_ANR, importance = 400, description = "anr: Input dispatching timed out (x)."),
            exit(50_000, AndroidHealth.REASON_LOW_MEMORY, importance = 400),        // in the background: normal
            exit(60_000, AndroidHealth.REASON_LOW_MEMORY, importance = 100),        // on screen
            exit(70_000, 10),                                                     // user swiped it away
            exit(80_000, AndroidHealth.REASON_CRASH),                             // no file for this one
        ), traces, file)
        assertEquals(listOf(80_000L, 60_000L, 40_000L, 30_000L, 10_500L), out.map { it.atMs })
        assertEquals(listOf("crash", "killed", "hang", "crash", "crash"), out.map { it.kind })
        val java = out.last()
        assertEquals("java.lang.IllegalStateException", java.exceptionName)
        assertEquals("joined with Android's record", AndroidHealth.REASON_CRASH, java.exitReason)
        assertEquals(51_200L, java.pssKb)
        assertTrue(java.frames.isNotEmpty())
        val native = out[3]
        assertEquals("SIGSEGV", native.signalName); assertEquals("SI_USER", native.signalCode); assertEquals("main", native.thread)
        assertTrue(native.frames.any { it.inApp })
        val anr = out[2]
        assertEquals("main", anr.thread); assertEquals("Input dispatching timed out", anr.detail)
        assertEquals("t.crash.Main.onCreate\$lambda\$0", anr.frames.first { it.inApp }.symbol)
        assertNull("no file, no frames", out[0].exceptionName); assertTrue(out[0].frames.isEmpty())
    }

    @Test fun aJavaCrashWithoutAndroidsRecordIsStillSent() {
        val file = ThrowableReport.from(IllegalStateException(), 500_000, "2.0", "20", "main")
        val out = AndroidHealth.reports(listOf(exit(10_000, AndroidHealth.REASON_CRASH)), emptyMap(), file)
        assertEquals("too far apart to be the same crash", 2, out.size)
        assertEquals(500_000L, out[0].atMs); assertNull(out[0].exitReason)
        assertEquals(listOf(file), AndroidHealth.reports(emptyList(), emptyMap(), file))
    }

    @Test fun atMostTenReports() {
        val many = (1..25).map { exit(it * 1000L, AndroidHealth.REASON_CRASH) }
        assertEquals(10, AndroidHealth.reports(many, emptyMap(), null).size)
    }

    @Test fun aNativeCrashWithoutTombstoneStillNamesTheSignal() {
        val out = AndroidHealth.reports(listOf(exit(1, AndroidHealth.REASON_CRASH_NATIVE, status = 7)), emptyMap(), null)
        assertEquals("SIGBUS", out[0].signalName)
    }

    @Test fun aCrashIsSentAsTheCollectorReadsIt() {
        val transport = MockTransport()
        val t = Tracker(Configuration("K", "https://api.example.test"), AppInfo("me.example.app", "2.0", "20"),
            { DeviceInfo("16", "Google Pixel 8", DeviceInfo.DeviceClass.PHONE) }, transport, history = MockHistory())
        val r = AndroidHealth.reports(listOf(exit(30_000, AndroidHealth.REASON_CRASH_NATIVE, status = 11)),
            mapOf(30_000L to resource("tombstone-sigsegv.pb")), null)[0]
        t.crash(r); t.flush()
        val item = transport.items()[0]
        assertEquals("app_crash", item["type"])
        val d = item["data"] as Map<String, Any?>
        assertEquals("crash", d["kind"]); assertEquals(30_000L, d["at"]); assertEquals("2.0", d["crash_version"]); assertEquals("20", d["crash_build"])
        assertEquals(5L, d["exit_reason"]); assertEquals(11L, d["exit_status"]); assertEquals("SIGSEGV", d["signal_name"])
        val frames = d["frames"] as List<Map<String, Any?>>
        assertEquals("kill", frames[0]["symbol"]); assertEquals("libc.so", frames[0]["image"]); assertEquals(861704L, frames[0]["offset"])
        assertFalse(frames[0].containsKey("in_app"))
        assertEquals(1L, frames.first { it["symbol"] == "t.crash.Main.onCreate" }["in_app"])
    }

    @Test fun withoutAppHealthNoCrashIsSent() {
        val transport = MockTransport()
        val t = Tracker(Configuration("K", "https://api.example.test"), AppInfo("me.example.app", "2.0", "20"),
            { DeviceInfo("16", "Google Pixel 8", DeviceInfo.DeviceClass.PHONE) }, transport, history = null)
        t.crash(CrashReport("crash", 1)); t.flush()
        assertTrue(transport.items().isEmpty())
    }
}
