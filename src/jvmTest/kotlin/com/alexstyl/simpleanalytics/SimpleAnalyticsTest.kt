package com.alexstyl.simpleanalytics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimpleAnalyticsTest {
    @TempDir lateinit var root: File

    private val start = Instant.parse("2026-11-06T10:00:00Z")
    private val now = AtomicReference(start)
    private val sent = CopyOnWriteArrayList<String>()
    private val deliveries = CopyOnWriteArrayList<Delivery>()
    private val threads = CopyOnWriteArrayList<Thread>()

    /** Simple Analytics answering each post with [respond], recording what it accepted. */
    private fun analytics(directory: File? = null, maxQueued: Int = 1000, pings: List<Duration> = emptyList(),
                          respond: (String) -> Int = { 200 }) =
        SimpleAnalytics.Session("app.example.com", "MyApp/1.0.0", device = null, queueStorage = directory?.let { queueFile(it, "app.example.com") },
            post = { delivery ->
                threads += Thread.currentThread()
                respond(delivery.body).also { if (it == 200) { sent += delivery.body; deliveries += delivery } }
            },
            pings = pings, clock = now::get, retryDelay = { 5.milliseconds }, maxQueued = maxQueued)

    private fun appends() = deliveries.filter { it.kind == Delivery.Kind.Append }.map { jsonFields(it.body) }
    private fun pageIds() = deliveries.filter { it.kind == Delivery.Kind.Pageview }.map { jsonFields(it.body).getValue("id") }

    private fun names() = sent.filterNot { it.startsWith("{\"type\":\"append\"") }
        .map { Regex("\"(?:event|path)\":\"([^\"]+)\"").findAll(it).last().groupValues[1] }

    private fun SimpleAnalytics.Session.flushNow() = runBlocking { withTimeout(5.seconds) { flush() } }

    private fun awaitSent(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (sent.size < count && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(count, sent.size, "sent: $sent")
    }

    @Test fun `an event is the hostname, its name, the user agent, when it happened and its details`() {
        assertEquals("""{"type":"event","hostname":"app.example.com","event":"buy_clicked","ua":"MyApp/1.0.0","https":true,"timestamp":"2026-11-06T10:00:00.123Z","metadata":{"source":"toolbar"}}""",
            eventJson("app.example.com", "buy_clicked", "MyApp/1.0.0", null, Instant.parse("2026-11-06T10:00:00.123Z"), mapOf("source" to "toolbar")))
        assertEquals("""{"type":"event","hostname":"h","event":"e","ua":"ua","https":true,"timestamp":"2026-11-06T10:00:00Z","metadata":{"path":"C:\\Users\\\"me\"\u000a"}}""",
            eventJson("h", "e", "ua", null, start, mapOf("path" to "C:\\Users\\\"me\"\n")))
    }

    @Test fun `events and pageviews carry the device's timezone, language and screen`() {
        val device = DeviceInfo("Europe/Athens", "en-GB", 1512, 982)
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua","https":true,"timestamp":"2026-11-06T10:00:00Z","timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            eventJson("h", "app_opened", "ua", device, start, emptyMap()))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/settings","ua":"ua","https":true,"timestamp":"2026-11-06T10:00:00Z","unique":true,"timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            pageviewJson("h", "/settings", "ua", device, start, unique = true))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/","ua":"ua","https":true,"timestamp":"2026-11-06T10:00:00Z","unique":false,"timezone":"UTC","language":"und"}""",
            pageviewJson("h", "/", "ua", DeviceInfo("UTC", "und", null, null), start, unique = false))
    }

    @Test fun `only the first pageview of a run counts as a visit`() {
        val analytics = analytics()
        analytics.pageview("/")
        analytics.pageview("/settings")
        analytics.pageview("/")
        awaitSent(3)
        assertEquals(listOf(true, false, false), sent.map { "\"unique\":true" in it })
    }

    @Test fun `events are sent off the caller's thread`() {
        analytics().track("app_opened")
        awaitSent(1)
        assertNotEquals(Thread.currentThread(), threads.single())
    }

    @Test fun `events recorded offline are sent in order once back online, with the time they happened`() {
        val online = AtomicBoolean(false)
        val analytics = analytics { if (online.get()) 200 else throw IOException("offline") }
        analytics.track("first")
        now.set(start + 60.seconds)
        analytics.track("second")
        Thread.sleep(50)
        assertTrue(sent.isEmpty())

        online.set(true)
        awaitSent(2)
        assertEquals(listOf("first", "second"), names())
        assertTrue("2026-11-06T10:00:00Z" in sent[0] && "2026-11-06T10:01:00Z" in sent[1])
    }

    @Test fun `server errors and rate limits are retried`() {
        val responses = ArrayDeque(listOf(503, 429, 200))
        analytics { responses.removeFirstOrNull() ?: 200 }.track("app_opened")
        awaitSent(1)
        assertTrue(responses.isEmpty())
    }

    @Test fun `events Simple Analytics refuses are dropped instead of blocking the queue`() {
        val analytics = analytics { if ("bad" in it) 400 else 200 }
        analytics.track("bad")
        analytics.track("good")
        awaitSent(1)
        assertEquals(listOf("good"), names())
    }

    @Test fun `events waiting when the app quits are sent on its next run`() {
        val offline = analytics(directory = root) { throw IOException("offline") }
        offline.track("first")
        offline.pageview("/settings")
        offline.flushNow()
        offline.close()

        analytics(directory = root)
        awaitSent(2)
        assertEquals(listOf("first", "/settings"), names())
    }

    @Test fun `events older than 72 hours are dropped`() {
        analytics(directory = root) { throw IOException("offline") }.apply { track("stale"); flushNow(); close() }
        now.set(start + 73.hours)
        val analytics = analytics(directory = root)
        analytics.track("fresh")
        awaitSent(1)
        assertEquals(listOf("fresh"), names())
    }

    @Test fun `a full queue drops its oldest events`() {
        val online = AtomicBoolean(false)
        val analytics = analytics(maxQueued = 2) { if (online.get()) 200 else throw IOException("offline") }
        listOf("first", "second", "third").forEach { analytics.track(it) }
        Thread.sleep(50)
        online.set(true)
        awaitSent(2)
        assertEquals(listOf("second", "third"), names())
    }

    @Test fun `input Simple Analytics would reject is dropped instead of thrown`() {
        val analytics = analytics()
        analytics.track("Buy clicked")
        analytics.pageview("settings")
        analytics.track("app_opened")
        awaitSent(1)
        Thread.sleep(20)
        assertEquals(listOf("app_opened"), names())
    }

    @Test fun `closing still sends what was recorded, and records nothing after`() {
        val analytics = analytics { Thread.sleep(20); 200 }
        analytics.track("app_opened")
        analytics.close()
        analytics.track("after")
        analytics.pageview("/")
        assertTrue(sent.isEmpty(), "close() doesn't wait for sending")
        analytics.flushNow()
        assertEquals(listOf("app_opened"), names())
    }

    @Test fun `flush waits until recorded events are sent`() {
        val analytics = analytics { Thread.sleep(20); 200 }
        analytics.track("first")
        analytics.pageview("/")
        analytics.flushNow()
        assertEquals(listOf("first", "/"), names())
    }

    @Test fun `flush doesn't wait for retries while offline`() {
        val analytics = analytics { throw IOException("offline") }
        analytics.track("app_opened")
        analytics.flushNow()
        assertTrue(sent.isEmpty())
    }

    @Test fun `retries wait longer after each failure, up to 5 minutes`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 256L, 300L, 300L), listOf(1, 2, 3, 4, 9, 10, 50).map { backoff(it).inWholeSeconds })
    }

    @Test fun `the user agent names the app and the computer the way browsers do`() {
        assertEquals("MyApp/1.0.0 (Macintosh; Mac OS X 15_4; arm64)", userAgent("MyApp", "1.0.0", "Mac OS X", "15.4", "aarch64"))
        assertEquals("MyApp/1.0.0 (Windows NT 10.0; Win64; x64)", userAgent("MyApp", "1.0.0", "Windows 11", "10.0", "amd64"))
        assertEquals("MyApp/1.0.0 (Windows NT 10.0; ARM64)", userAgent("MyApp", "1.0.0", "Windows 10", "10.0", "aarch64"))
        assertEquals("MyApp/1.0.0 (X11; Linux x86_64)", userAgent("MyApp", "1.0.0", "Linux", "6.8.0", "amd64"))
        assertEquals("MyApp/1.0.0 (FreeBSD 14.1; x86_64)", userAgent("MyApp", "1.0.0", "FreeBSD", "14.1", "amd64"))
    }

    @Test fun `a pageview carries its id, and time on page is added to that id`() {
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/","ua":"ua","https":true,"timestamp":"2026-11-06T10:00:00Z","unique":true,"id":"a1"}""",
            pageviewJson("h", "/", "ua", null, start, unique = true, id = "a1"))
        assertEquals("""{"type":"append","hostname":"h","original_id":"a1","duration":42,"ping":true,"version":"cdn_latest_12"}""",
            appendJson("h", "a1", 42, ping = true))
    }

    @Test fun `a screen's time on view is added to its pageview when the next screen shows`() {
        val analytics = analytics()
        analytics.pageview("/")
        now.set(start + 42.seconds)
        analytics.pageview("/settings")
        analytics.flushNow()

        val first = pageIds().first()
        assertEquals(listOf(mapOf("type" to "append", "hostname" to "app.example.com", "original_id" to first, "duration" to "42",
            "version" to ScriptVersion)), appends())
    }

    @Test fun `only time while the app is visible counts, and hiding it sends the time so far`() {
        val analytics = analytics()
        analytics.pageview("/")
        analytics.flushNow()
        now.set(start + 10.seconds)
        analytics.setVisible(false)
        now.set(start + 110.seconds)
        analytics.setVisible(true)
        now.set(start + 115.seconds)
        analytics.close()
        analytics.flushNow()

        assertEquals(listOf("10", "15"), appends().map { it.getValue("duration") })
    }

    @Test fun `fresh pageviews go live with their id, late ones keep their time and get no time on page`() {
        val online = AtomicBoolean(false)
        val analytics = analytics { if (online.get()) 200 else throw IOException("offline") }
        analytics.pageview("/")
        now.set(start + 2.minutes)
        analytics.pageview("/settings")
        online.set(true)
        analytics.flushNow()
        awaitSent(2)

        assertEquals(listOf(Delivery.Kind.Event, Delivery.Kind.Pageview), deliveries.map { it.kind })
        assertTrue("\"id\"" !in deliveries[0].body && "2026-11-06T10:00:00Z" in deliveries[0].body)
        assertTrue(appends().isEmpty(), "the 2 minutes on / belong to a pageview that didn't go live")
    }

    @Test fun `pings send the time on view as it passes each threshold`() {
        val analytics = analytics(pings = listOf(1.seconds))
        analytics.pageview("/")
        awaitSent(2)
        assertEquals(listOf("1"), appends().map { it.getValue("duration") })
        assertEquals("true", appends().single()["ping"])
    }

    @Test fun `payload fields are read back with escapes undone`() {
        assertEquals(mapOf("type" to "event", "path" to "C:\\Users\\\"me\"\n", "unique" to "true", "screen_width" to "1512"),
            jsonFields("""{"type":"event","path":"C:\\Users\\\"me\"\u000a","unique":true,"screen_width":1512,"metadata":{"a":"b"}}"""))
    }

    @Test fun `the app is on view while any of its windows shows and isn't minimised`() {
        assertTrue(appOnView(listOf(WindowView(showing = true, minimised = false))))
        assertTrue(appOnView(listOf(WindowView(showing = true, minimised = true), WindowView(showing = true, minimised = false))))
        assertFalse(appOnView(listOf(WindowView(showing = true, minimised = true))), "minimised")
        assertFalse(appOnView(listOf(WindowView(showing = false, minimised = false))), "closed")
        assertFalse(appOnView(emptyList()), "no windows")
    }

    @Test fun `quitting sends what's left, including the last screen's time on view`() {
        val analytics = analytics()
        analytics.pageview("/")
        analytics.flushNow()
        now.set(start + 30.seconds)
        analytics.track("export_clicked")
        runBlocking { analytics.stopAndSend(5.seconds) }

        assertEquals(listOf("/", "export_clicked"), names())
        assertEquals(listOf("30"), appends().map { it.getValue("duration") })
    }

    @Test fun `settings do nothing until started, and a stopped session records nothing`() {
        val settings = SimpleAnalytics("app.example.com", "MyApp/1.0.0")
        val session = settings.start()
        session.stop()
        session.stop()
        session.track("after")
        settings.start().use { }
    }
}
