package com.alexstyl.simpleanalytics

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimpleAnalyticsTest {
    private val sent = CopyOnWriteArrayList<String>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun analytics(post: (String) -> Unit = { sent += it }) =
        SimpleAnalytics("app.example.com", "MyApp/1.0.0", device = null, post = { threads += Thread.currentThread(); post(it) }, executor = executor)

    private fun SimpleAnalytics.sent(): List<String> {
        close(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        return sent
    }

    @Test fun `an event is the hostname, its name, the user agent and its details`() {
        assertEquals("""{"type":"event","hostname":"app.example.com","event":"buy_clicked","ua":"MyApp/1.0.0","metadata":{"source":"toolbar"}}""",
            eventJson("app.example.com", "buy_clicked", "MyApp/1.0.0", null, mapOf("source" to "toolbar")))
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua"}""", eventJson("h", "app_opened", "ua", null, emptyMap()))
        assertEquals("""{"type":"event","hostname":"h","event":"e","ua":"ua","metadata":{"path":"C:\\Users\\\"me\"\u000a"}}""",
            eventJson("h", "e", "ua", null, mapOf("path" to "C:\\Users\\\"me\"\n")))
    }

    @Test fun `events and pageviews carry the device's timezone, language and screen`() {
        val device = DeviceInfo("Europe/Athens", "en-GB", 1512, 982)
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua","timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            eventJson("h", "app_opened", "ua", device, emptyMap()))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/settings","ua":"ua","unique":true,"timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            pageviewJson("h", "/settings", "ua", device, unique = true))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/","ua":"ua","unique":false,"timezone":"UTC","language":"und"}""",
            pageviewJson("h", "/", "ua", DeviceInfo("UTC", "und", null, null), unique = false))
    }

    @Test fun `only the first pageview of a run counts as a visit`() {
        val analytics = analytics()
        analytics.pageview("/")
        analytics.pageview("/settings")
        analytics.pageview("/")
        assertEquals(listOf(true, false, false), analytics.sent().map { "\"unique\":true" in it })
    }

    @Test fun `events are sent off the caller's thread, and failures stay there`() {
        val analytics = analytics(post = { error("offline") })
        analytics.track("app_opened")
        analytics.track("app_opened")
        analytics.sent()
        assertEquals(2, threads.size)
        assertNotEquals(Thread.currentThread(), threads.first())
    }

    @Test fun `input Simple Analytics would reject is dropped instead of thrown`() {
        val analytics = analytics()
        analytics.track("Buy clicked")
        analytics.pageview("settings")
        analytics.track("app_opened")
        assertEquals(1, analytics.sent().size)
    }

    @Test fun `nothing is sent after closing`() {
        val analytics = analytics()
        analytics.track("app_opened")
        analytics.close()
        analytics.track("app_opened")
        analytics.pageview("/")
        assertEquals(1, analytics.sent().size)
    }

    @Test fun `the default user agent names the app and the computer`() {
        val ua = defaultUserAgent("MyApp", "1.0.0")
        assertTrue(ua.startsWith("MyApp/1.0.0 (${System.getProperty("os.name")} "), ua)
        assertTrue(ua.endsWith("; ${System.getProperty("os.arch")})"), ua)
    }
}
