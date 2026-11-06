package com.alexstyl.simpleanalytics

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimpleAnalyticsTest {
    @TempDir lateinit var root: File

    private val start = Instant.parse("2026-11-06T10:00:00Z")
    private val now = AtomicReference(start)
    private val sent = CopyOnWriteArrayList<String>()
    private val threads = CopyOnWriteArrayList<Thread>()

    /** Simple Analytics answering each post with [respond], recording what it accepted. */
    private fun analytics(directory: File? = null, maxQueued: Int = 1000, respond: (String) -> Int = { 200 }) =
        SimpleAnalytics("app.example.com", "MyApp/1.0.0", device = null, queueDirectory = directory?.let { Path(it.path) },
            post = { body -> threads += Thread.currentThread(); respond(body).also { if (it == 200) sent += body } },
            clock = now::get, retryDelay = { 5.milliseconds }, maxQueued = maxQueued)

    private fun names() = sent.map { Regex("\"(?:event|path)\":\"([^\"]+)\"").findAll(it).last().groupValues[1] }

    private fun awaitSent(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (sent.size < count && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(count, sent.size, "sent: $sent")
    }

    @Test fun `an event is the hostname, its name, the user agent, when it happened and its details`() {
        assertEquals("""{"type":"event","hostname":"app.example.com","event":"buy_clicked","ua":"MyApp/1.0.0","timestamp":"2026-11-06T10:00:00.123Z","metadata":{"source":"toolbar"}}""",
            eventJson("app.example.com", "buy_clicked", "MyApp/1.0.0", null, Instant.parse("2026-11-06T10:00:00.123Z"), mapOf("source" to "toolbar")))
        assertEquals("""{"type":"event","hostname":"h","event":"e","ua":"ua","timestamp":"2026-11-06T10:00:00Z","metadata":{"path":"C:\\Users\\\"me\"\u000a"}}""",
            eventJson("h", "e", "ua", null, start, mapOf("path" to "C:\\Users\\\"me\"\n")))
    }

    @Test fun `events and pageviews carry the device's timezone, language and screen`() {
        val device = DeviceInfo("Europe/Athens", "en-GB", 1512, 982)
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua","timestamp":"2026-11-06T10:00:00Z","timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            eventJson("h", "app_opened", "ua", device, start, emptyMap()))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/settings","ua":"ua","timestamp":"2026-11-06T10:00:00Z","unique":true,"timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            pageviewJson("h", "/settings", "ua", device, start, unique = true))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/","ua":"ua","timestamp":"2026-11-06T10:00:00Z","unique":false,"timezone":"UTC","language":"und"}""",
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
        offline.close()

        analytics(directory = root)
        awaitSent(2)
        assertEquals(listOf("first", "/settings"), names())
    }

    @Test fun `events older than 72 hours are dropped`() {
        analytics(directory = root) { throw IOException("offline") }.apply { track("stale"); close() }
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

    @Test fun `nothing is recorded after closing`() {
        val analytics = analytics()
        analytics.track("app_opened")
        analytics.close()
        analytics.track("after")
        analytics.pageview("/")
        assertEquals(listOf("app_opened"), names())
    }

    @Test fun `retries wait longer after each failure, up to 5 minutes`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 256L, 300L, 300L), listOf(1, 2, 3, 4, 9, 10, 50).map { backoff(it).inWholeSeconds })
    }

    @Test fun `the default user agent names the app and the computer`() {
        val ua = defaultUserAgent("MyApp", "1.0.0")
        assertTrue(ua.startsWith("MyApp/1.0.0 (${System.getProperty("os.name")} "), ua)
        assertTrue(ua.endsWith("; ${System.getProperty("os.arch")})"), ua)
    }
}
