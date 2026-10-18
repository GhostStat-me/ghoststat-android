package me.ghoststat.sdk

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineTest {
    private val app = AppInfo("me.example.app", "1.0", "1")
    private val device = DeviceInfo("15", "Google Pixel 8", DeviceInfo.DeviceClass.PHONE)

    @Test fun callsRunOnTheWorkerThreadNotTheCaller() {
        val transport = MockTransport()
        val e = Engine(Tracker(Configuration("K"), app, { device }, transport))
        val seen = arrayOfNulls<String>(1); val done = CountDownLatch(1)
        e.post { seen[0] = Thread.currentThread().name; it.screen("Home"); done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals("GhostStat", seen[0])
        assertTrue(e.flushAndWait()); assertEquals(1, transport.bodies.size)
        e.shutdown()
    }

    @Test fun aFailedSendIsRetriedOnItsOwn() {
        val transport = MockTransport(MockTransport.Reply.Offline)
        val e = Engine(Tracker(Configuration("K", retryBaseMs = 50, intervalMs = 60_000), app, { device }, transport))
        e.post { it.event("a"); it.flush() }
        val deadline = System.currentTimeMillis() + 3000
        while (transport.bodies.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("the retry went out without another call", 2, transport.bodies.size)
        assertTrue(e.flushAndWait()); assertTrue(e.tracker.queued.isEmpty())
        e.shutdown()
    }

    @Test fun aFullBatchGoesOutWithoutWaitingForTheLoop() {
        val transport = MockTransport()
        val e = Engine(Tracker(Configuration("K", intervalMs = 60_000), app, { device }, transport))
        e.post { t -> repeat(50) { t.event("e$it") } }
        val deadline = System.currentTimeMillis() + 2000
        while (transport.bodies.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, transport.bodies.size)
        e.shutdown()
    }

    @Test fun aCrashInsideACallNeverReachesTheApp() {
        val e = Engine(Tracker(Configuration("K"), app, { throw IllegalStateException("no screen yet") }, MockTransport()))
        e.post { it.event("a") }
        assertFalse("the failure stays inside the SDK", e.flushAndWait())
        e.shutdown()
    }
}
