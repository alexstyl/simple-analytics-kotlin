package com.alexstyl.simpleanalytics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Records what people do in the app, as named events with optional details. */
interface Analytics {
    /** Records [event]. Never blocks the caller and never throws; events that can't be sent are dropped. */
    fun track(event: String, metadata: Map<String, String> = emptyMap())

    /** Records that the screen at [path] was shown, like a website's pageview. Never blocks and never throws. */
    fun pageview(path: String)

    /** Records nothing, for tests and builds without analytics. */
    object None : Analytics {
        override fun track(event: String, metadata: Map<String, String>) {}
        override fun pageview(path: String) {}
    }
}

/**
 * The computer's settings that Simple Analytics reports on: it tells the country from [timezone], an IANA id such as
 * `Europe/Athens`. [language] is a language tag such as `en-US`.
 */
data class Visitor(val timezone: String, val language: String, val screenWidth: Int?, val screenHeight: Int?)

/**
 * Sends events and pageviews to Simple Analytics' events API for [hostname], a website set up in its dashboard. They
 * carry no cookies or identifiers: only the event or path, its metadata, the app's [userAgent] and the [visitor]'s
 * settings.
 */
class SimpleAnalytics(
    private val hostname: String,
    private val userAgent: String,
    private val visitor: Visitor? = null,
    /** Posts one JSON event; the default sends it to Simple Analytics. */
    private val post: (String) -> Unit = SimpleAnalyticsHttp(userAgent)::post,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "Analytics").apply { isDaemon = true } },
) : Analytics {
    init {
        // Simple Analytics marks user agents containing these words as robots and leaves them out of the dashboard.
        require(RobotWords.none { it in userAgent.lowercase() }) { "The user agent would be counted as a robot: $userAgent" }
    }

    override fun track(event: String, metadata: Map<String, String>) {
        require(EventName.matches(event)) { "Event names are lowercase letters, digits and underscores: $event" }
        send(eventJson(hostname, event, userAgent, visitor, metadata))
    }

    override fun pageview(path: String) {
        require(path.startsWith("/")) { "Paths start with a slash: $path" }
        send(pageviewJson(hostname, path, userAgent, visitor))
    }

    private fun send(body: String) {
        runCatching { executor.execute { runCatching { post(body) } } }
    }

    private companion object {
        val EventName = Regex("[a-z0-9_]{1,200}")
        val RobotWords = listOf("bot", "spider", "crawl", "agent")
    }
}

/** The JSON Simple Analytics' events API takes for one event. */
internal fun eventJson(hostname: String, event: String, userAgent: String, visitor: Visitor?, metadata: Map<String, String>): String = buildString {
    append("{\"type\":\"event\",\"hostname\":").append(json(hostname))
    append(",\"event\":").append(json(event))
    append(",\"ua\":").append(json(userAgent))
    appendVisitor(visitor)
    if (metadata.isNotEmpty()) {
        append(",\"metadata\":{")
        metadata.entries.joinTo(this, ",") { (key, value) -> "${json(key)}:${json(value)}" }
        append("}")
    }
    append("}")
}

/** The JSON Simple Analytics' events API takes for one pageview. */
internal fun pageviewJson(hostname: String, path: String, userAgent: String, visitor: Visitor?): String = buildString {
    append("{\"type\":\"pageview\",\"hostname\":").append(json(hostname))
    append(",\"event\":\"pageview\",\"path\":").append(json(path))
    append(",\"ua\":").append(json(userAgent))
    appendVisitor(visitor)
    append("}")
}

private fun StringBuilder.appendVisitor(visitor: Visitor?) {
    if (visitor == null) return
    append(",\"timezone\":").append(json(visitor.timezone))
    append(",\"language\":").append(json(visitor.language))
    visitor.screenWidth?.let { append(",\"screen_width\":").append(it) }
    visitor.screenHeight?.let { append(",\"screen_height\":").append(it) }
}

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

private class SimpleAnalyticsHttp(private val userAgent: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun post(body: String) {
        val request = HttpRequest.newBuilder(URI("https://queue.simpleanalyticscdn.com/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
}
