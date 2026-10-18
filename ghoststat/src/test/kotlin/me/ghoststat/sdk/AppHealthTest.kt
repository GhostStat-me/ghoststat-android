package me.ghoststat.sdk

import org.junit.Assert.*
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class AppHealthTest {
    private val device = DeviceInfo("15", "Google Pixel 8", DeviceInfo.DeviceClass.PHONE)

    private fun make(t: MockTransport, history: LaunchHistory?, clock: TestClock = TestClock(), version: String? = "2.0", startedAt: Long? = null) =
        Tracker(Configuration("K", "https://api.example.test"), AppInfo("me.example.app", version, "20"), { device }, t,
            { clock.now }, history = history, launchStartedAtMs = startedAt)

    private fun launchData(t: MockTransport) = t.items().first { it["type"] == "app_launch" }["data"] as Map<String, Any?>

    @Test fun kindRules() {
        val day = 86_400_000L
        assertEquals(LaunchInfo.Kind.INSTALL to null, LaunchInfo.kind("2.0", null, 60_000))
        assertEquals("unknown install time: an install", LaunchInfo.Kind.INSTALL to null, LaunchInfo.kind("2.0", null, null))
        assertEquals("installed long before the SDK: not a new install", LaunchInfo.Kind.OPEN to null, LaunchInfo.kind("2.0", null, 30 * day))
        assertEquals(LaunchInfo.Kind.UPDATE to "1.9", LaunchInfo.kind("2.0", "1.9", 60_000))
        assertEquals(LaunchInfo.Kind.OPEN to null, LaunchInfo.kind("2.0", "2.0", 60_000))
        assertEquals(LaunchInfo.Kind.OPEN to null, LaunchInfo.kind(null, "2.0", 60_000))
    }

    @Test fun firstForegroundIsOneLaunchItemMeasuredFromTheProcessStart() {
        val transport = MockTransport(); val clock = TestClock(); val history = MockHistory(stored = "1.9")
        val t = make(transport, history, clock, startedAt = clock.now - 400)
        clock.advance(440)
        t.enterForeground(); t.enterBackground(); t.enterForeground()
        val launches = transport.items().filter { it["type"] == "app_launch" }
        assertEquals(1, launches.size)
        val d = launches[0]["data"] as Map<String, Any?>
        assertEquals("update", d["kind"]); assertEquals("1.9", d["from_version"]); assertEquals(840L, d["launch_ms"])
        assertEquals("2.0", history.stored)
        assertEquals("sent before the first heartbeat", "app_launch", transport.items().first()["type"])
    }

    @Test fun aSlowLaunchIsNotMeasured() {
        val transport = MockTransport(); val clock = TestClock(); val t = make(transport, MockHistory(), clock)
        clock.advance(3_600_000); t.enterForeground()
        val d = launchData(transport)
        assertEquals("install", d["kind"]); assertFalse(d.containsKey("launch_ms"))
    }

    @Test fun withoutAppHealthThereIsNoLaunchItem() {
        val transport = MockTransport(); val t = make(transport, null)
        t.enterForeground(); t.diagnostic(DiagnosticSummary(crashes = 1)); t.flush()
        assertEquals(listOf("ping"), transport.items().map { it["type"] })
    }

    @Test fun switchedOffNothingIsReadOrStored() {
        val transport = MockTransport(); val history = MockHistory(stored = "1.0"); val t = make(transport, history)
        t.isEnabled = false
        t.enterForeground(); t.diagnostic(DiagnosticSummary(crashes = 1))
        assertEquals(0, history.reads); assertEquals("1.0", history.stored); assertTrue(transport.bodies.isEmpty())
    }

    @Test fun aDiagnosticIsSentAsTheCollectorReadsIt() {
        val transport = MockTransport(); val t = make(transport, MockHistory())
        t.diagnostic(DiagnosticSummary(1_790_000_000_000, 1_790_086_399_000, "1.9", crashes = 2, hangs = 1, diskWriteExceptions = -3))
        t.flush()
        val item = transport.items()[0]
        assertEquals("app_diagnostic", item["type"])
        val d = item["data"] as Map<String, Any?>
        assertEquals(1_790_000_000_000L, d["period_start"]); assertEquals(1_790_086_399_000L, d["period_end"])
        assertEquals("1.9", d["app_version"]); assertEquals(2L, d["crashes"]); assertEquals(1L, d["hangs"])
        assertEquals(0L, d["cpu_exceptions"]); assertEquals("never negative", 0L, d["disk_write_exceptions"])
        assertFalse(d.containsKey("launch_p50_ms")); assertFalse(d.containsKey("name"))
    }

    @Test fun exitReasonsBecomeCountsPerVersion() {
        val e = { ts: Long, v: String?, r: Int -> AndroidHealth.Exit(ts, v, r) }
        val out = AndroidHealth.summarize(listOf(
            e(100, "1.9", AndroidHealth.REASON_CRASH), e(300, "1.9", AndroidHealth.REASON_ANR), e(200, "1.9", AndroidHealth.REASON_CRASH_NATIVE),
            e(400, "2.0", AndroidHealth.REASON_CRASH), e(500, "2.0", 10 /* user requested */), e(600, "2.0", 3 /* low memory */)))
        assertEquals(2, out.size)
        assertEquals(DiagnosticSummary(100, 300, "1.9", crashes = 2, hangs = 1), out[0])
        assertEquals(DiagnosticSummary(400, 400, "2.0", crashes = 1), out[1])
        assertTrue("ordinary exits are not problems", AndroidHealth.summarize(listOf(e(1, "1", 10), e(2, "1", 1))).isEmpty())
    }

    @Test fun deviceModelNames() {
        assertEquals("Google Pixel 8", AndroidEnvironment.model("Google", "Pixel 8"))
        assertEquals("Samsung SM-S918B", AndroidEnvironment.model("samsung", "SM-S918B"))
        assertEquals("the maker is not repeated", "OnePlus CPH2451", AndroidEnvironment.model("OnePlus", "OnePlus CPH2451"))
        assertEquals("Pixel 8", AndroidEnvironment.model(null, "Pixel 8"))
    }
}
