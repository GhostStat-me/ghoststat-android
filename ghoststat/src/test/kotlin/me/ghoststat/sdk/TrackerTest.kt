package me.ghoststat.sdk

import org.junit.Assert.*
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class TrackerTest {
    private val launch = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val app = AppInfo("me.example.app", "1.4", "212")
    private val device = DeviceInfo("15", "Google Pixel 8", DeviceInfo.DeviceClass.PHONE, 412, 915, 2.6)

    private fun make(t: MockTransport, clock: TestClock = TestClock(), maxAttempts: Int = 5, history: LaunchHistory? = null) =
        Tracker(Configuration("KEY123", "https://api.example.test/", maxAttempts = maxAttempts), app, { device }, t,
            { clock.now }, launch, history)

    @Test fun theBatchIsWhatTheCollectorReads() {
        val transport = MockTransport(); val clock = TestClock(); val t = make(transport, clock)
        t.screen("Home"); clock.advance(2000)
        t.event("signup", mapOf("plan" to "pro", "note" to "a \"quoted\"\nline"), 2.0)
        t.ping()
        assertTrue(t.flush()); assertTrue(t.queued.isEmpty())
        assertEquals("https://api.example.test/collect/batch", transport.urls[0])
        val body = MiniJson.parse(transport.bodies[0]) as Map<String, Any?>
        assertEquals("KEY123", body["site"])
        val a = body["app"] as Map<String, Any?>
        assertEquals("me.example.app", a["bundle_id"]); assertEquals("1.4", a["version"]); assertEquals("212", a["build"])
        assertEquals("android", a["platform"]); assertEquals("15", a["os_version"]); assertEquals("Google Pixel 8", a["device_model"])
        assertEquals("phone", a["device_class"]); assertEquals(launch, a["launch_id"])
        assertEquals(412L, a["sw"]); assertEquals(915L, a["sh"]); assertEquals(2.6, a["dpr"])
        val batch = body["batch"] as List<Map<String, Any?>>
        assertEquals(listOf("screen", "event", "ping"), batch.map { it["type"] })
        assertEquals(1_790_000_000_000L, batch[0]["ts"]); assertEquals(1_790_000_002_000L, batch[1]["ts"])
        assertEquals("Home", (batch[0]["data"] as Map<*, *>)["name"])
        val ev = batch[1]["data"] as Map<String, Any?>
        assertEquals("signup", ev["name"]); assertEquals(2L, ev["value"]); assertEquals("Home", ev["screen"])
        assertEquals(mapOf("note" to "a \"quoted\"\nline", "plan" to "pro"), ev["props"])
        assertFalse("a ping has no data", batch[2].containsKey("data"))
    }

    @Test fun aDeepLinkCampaignGoesWithTheNextScreenOnly() {
        val transport = MockTransport(); val t = make(transport)
        t.handle("https://sunnyside.example/offer?utm_source=newsletter&utm_campaign=spring%20sale&code=SECRET")
        t.screen("Offer"); t.screen("Checkout")
        t.flush()
        val items = transport.items()
        val first = items[0]["data"] as Map<String, Any?>
        assertEquals("newsletter", first["utm_source"]); assertEquals("spring sale", first["utm_campaign"])
        assertEquals("https://sunnyside.example", first["ref"])
        assertFalse(transport.bodies[0].contains("SECRET"))
        assertNull((items[1]["data"] as Map<*, *>)["utm_source"])
    }

    @Test fun campaignParsing() {
        assertNull(Campaign.fromUrl("myapp://open/item/7"))
        assertEquals(Campaign(source = "x", medium = "cpc"), Campaign.fromUrl("myapp://open?UTM_SOURCE=x&utm_medium=cpc&utm_term="))
        assertEquals("myapp://open", Campaign.source("myapp://open/item/7?a=1"))
        assertEquals("mailto:", Campaign.source("mailto:someone@example.com"))
    }

    @Test fun theSameScreenTwiceWithinASecondCountsOnce() {
        val transport = MockTransport(); val clock = TestClock(); val t = make(transport, clock)
        t.screen("Home"); clock.advance(300); t.screen("Home"); clock.advance(1500); t.screen("Home")
        assertEquals(2, t.queued.size)
    }

    @Test fun batchesHoldAtMostFifty() {
        val transport = MockTransport(); val t = make(transport)
        repeat(120) { t.event("e$it") }
        assertTrue(t.wantsFlush)
        assertTrue(t.flush())
        assertEquals(listOf(50, 50, 20), transport.bodies.map { ((MiniJson.parse(it) as Map<*, *>)["batch"] as List<*>).size })
    }

    @Test fun offlineKeepsTheQueueAndBacksOff() {
        val transport = MockTransport(MockTransport.Reply.Offline, MockTransport.Reply.Offline); val clock = TestClock(); val t = make(transport, clock)
        t.event("a")
        assertFalse(t.flush()); assertEquals(1, t.queued.size); assertEquals(clock.now + 1000, t.retryAtMs)
        assertFalse("not due yet", t.flush()); assertEquals(1, transport.bodies.size)
        clock.advance(1000); assertFalse(t.flush()); assertEquals(clock.now + 2000, t.retryAtMs)
        clock.advance(2000); assertTrue(t.flush()); assertNull(t.retryAtMs); assertTrue(t.queued.isEmpty())
        assertEquals(3, transport.bodies.size)
    }

    @Test fun aBatchIsDroppedAfterItsLastTry() {
        val transport = MockTransport(MockTransport.Reply.Status(503), MockTransport.Reply.Status(503)); val clock = TestClock()
        val t = make(transport, clock, maxAttempts = 2)
        t.event("a"); t.flush(); clock.advance(1000); t.flush()
        assertTrue(t.queued.isEmpty()); assertEquals(1, t.droppedCount); assertNull(t.retryAtMs)
    }

    @Test fun aRefusedBatchIsNotRetried() {
        val transport = MockTransport(MockTransport.Reply.Status(422)); val t = make(transport)
        t.event("a"); t.flush()
        assertTrue(t.queued.isEmpty()); assertEquals(1, t.droppedCount); assertNull(t.retryAtMs); assertEquals(422, t.lastStatus)
    }

    @Test fun switchingOffDiscardsAndStopsCollecting() {
        val transport = MockTransport(); val t = make(transport)
        t.event("a"); t.isEnabled = false
        assertTrue(t.queued.isEmpty())
        t.event("b"); t.screen("X"); t.ping(); t.handle("https://x.test/?utm_source=a")
        assertTrue(t.queued.isEmpty()); assertFalse(t.flush()); assertTrue(transport.bodies.isEmpty())
        t.isEnabled = true; t.screen("Y"); t.flush()
        assertNull("the link from while it was off is gone", (transport.items()[0]["data"] as Map<*, *>)["utm_source"])
    }

    @Test fun theQueueKeepsTheNewestWhenFull() {
        val t = Tracker(Configuration("K", maxBatch = 50, maxQueue = 60), app, { device }, MockTransport(MockTransport.Reply.Offline))
        repeat(70) { t.event("e$it") }
        assertEquals(60, t.queued.size); assertEquals("e69", t.queued.last().data.name); assertEquals(10, t.droppedCount)
    }

    @Test fun foregroundCountsAsPresentAndTheBeatPings() {
        val transport = MockTransport(); val t = make(transport)
        t.enterForeground(); assertTrue(t.isForeground)
        t.tick(); t.enterBackground(); assertFalse(t.isForeground); t.tick()
        assertEquals(listOf("ping", "ping"), transport.items().map { it["type"] })
    }

    @Test fun eventPropsAndValuesAreKeptSane() {
        val transport = MockTransport(); val t = make(transport)
        t.event("buy", mapOf("" to "x", "plan" to "p".repeat(300)), Double.NaN); t.flush()
        val d = transport.items()[0]["data"] as Map<String, Any?>
        assertEquals(setOf("plan"), (d["props"] as Map<*, *>).keys); assertEquals(200, ((d["props"] as Map<*, *>)["plan"] as String).length)
        assertFalse("NaN is not sent", d.containsKey("value"))
    }

    @Test fun jsonEscapesControlCharacters() {
        val transport = MockTransport(); val t = make(transport)
        t.event("x\u0001 \\y"); t.flush()
        assertEquals("x\u0001 \\y", (transport.items()[0]["data"] as Map<*, *>)["name"])
        assertTrue(transport.bodies[0].contains("\\u0001"))
    }
}
