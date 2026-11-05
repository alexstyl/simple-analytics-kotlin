package com.alexstyl.simpleanalytics

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimpleAnalyticsTest {
    @Test fun `an event is the hostname, its name, the user agent and its details`() {
        assertEquals("""{"type":"event","hostname":"app.example.com","event":"buy_clicked","ua":"MyApp/1.2.0","metadata":{"source":"onboarding"}}""",
            eventJson("app.example.com", "buy_clicked", "MyApp/1.2.0", null, mapOf("source" to "onboarding")))
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua"}""", eventJson("h", "app_opened", "ua", null, emptyMap()))
        assertEquals("""{"type":"event","hostname":"h","event":"e","ua":"ua","metadata":{"path":"C:\\Users\\\"me\"\u000a"}}""",
            eventJson("h", "e", "ua", null, mapOf("path" to "C:\\Users\\\"me\"\n")))
    }

    @Test fun `events and pageviews carry the visitor's timezone, language and screen`() {
        val visitor = Visitor("Europe/Athens", "en-GB", 1512, 982)
        assertEquals("""{"type":"event","hostname":"h","event":"app_opened","ua":"ua","timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            eventJson("h", "app_opened", "ua", visitor, emptyMap()))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/settings/updates","ua":"ua","timezone":"Europe/Athens","language":"en-GB","screen_width":1512,"screen_height":982}""",
            pageviewJson("h", "/settings/updates", "ua", visitor))
        assertEquals("""{"type":"pageview","hostname":"h","event":"pageview","path":"/","ua":"ua","timezone":"UTC","language":"und"}""",
            pageviewJson("h", "/", "ua", Visitor("UTC", "und", null, null)))
    }

    @Test fun `events are sent off the caller's thread, and failures stay there`() {
        val threads = CopyOnWriteArrayList<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        val analytics = SimpleAnalytics("app.example.com", "MyApp/1.2.0", post = { threads += Thread.currentThread(); error("offline") }, executor = executor)
        analytics.track("app_opened")
        analytics.track("app_opened")
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(2, threads.size)
        assertNotEquals(Thread.currentThread(), threads.first())
    }

    @Test fun `names and user agents Simple Analytics would reject are caught early`() {
        val analytics = SimpleAnalytics("h", "MyApp/1.2.0", post = {})
        assertFailsWith<IllegalArgumentException> { analytics.track("Buy clicked") }
        assertFailsWith<IllegalArgumentException> { analytics.pageview("settings") }
        assertFailsWith<IllegalArgumentException> { SimpleAnalytics("h", "MyApp Agent/1.0", post = {}) }
    }
}
