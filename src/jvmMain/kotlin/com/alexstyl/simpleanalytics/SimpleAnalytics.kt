package com.alexstyl.simpleanalytics

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.lang.System.Logger.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The computer's settings that Simple Analytics reports on. It works out the country from [timezone], an IANA id
 * such as `Europe/Athens`. [language] is a language tag such as `en-US`.
 */
data class DeviceInfo(val timezone: String, val language: String, val screenWidth: Int?, val screenHeight: Int?) {
    companion object {
        /** This computer's timezone, language and, when it has a display, the size of its main screen. */
        fun current(): DeviceInfo {
            val screen = if (GraphicsEnvironment.isHeadless()) null else runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
            return DeviceInfo(TimeZone.getDefault().id, Locale.getDefault().toLanguageTag(), screen?.width, screen?.height)
        }
    }
}

/**
 * Sends events and pageviews to Simple Analytics for [hostname], a website added in its dashboard. They carry no cookies
 * or identifiers: only the event or path, its details, when it happened, the user agent and the [device]'s settings.
 *
 * Calls never block and never throw. Events are sent in order on a background thread. While the computer is offline
 * they wait and are retried with increasing delays, keeping the time they happened. With a queue directory they also
 * survive the app quitting. Invalid input and events Simple Analytics refuses are logged and dropped.
 */
class SimpleAnalytics internal constructor(
    private val hostname: String,
    private val userAgent: String,
    private val device: DeviceInfo?,
    queueDirectory: File?,
    /** Posts one JSON payload and returns the HTTP status; throws when it can't reach Simple Analytics. */
    post: (String) -> Int,
    private val clock: () -> Instant = Instant::now,
    retryDelay: (attempt: Int) -> Duration = ::backoff,
    maxQueued: Int = MaxQueued,
) : AutoCloseable {

    /**
     * [appName] and [appVersion] make up the default [userAgent], such as `MyApp/1.0.0 (Mac OS X 15.1; aarch64)`.
     * [device] defaults to this computer's settings; null sends none.
     *
     * Events that can't be sent yet are kept in memory. Pass a [queueDirectory], such as your app's data folder, to
     * keep them on disk instead, so they're sent on the app's next run. Use one instance per hostname and directory.
     */
    constructor(
        hostname: String,
        appName: String,
        appVersion: String,
        userAgent: String = defaultUserAgent(appName, appVersion),
        device: DeviceInfo? = DeviceInfo.current(),
        queueDirectory: File? = null,
    ) : this(hostname, userAgent, device, queueDirectory, HttpPost(userAgent)::post)

    private val outbox = Outbox(queueDirectory?.let { File(it, "simple-analytics-$hostname.queue") },
        post, clock, retryDelay, maxQueued, maxAge = MaxAge)
    private val firstPageview = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)

    init {
        if (RobotWords.any { it in userAgent.lowercase() }) {
            logger.log(Level.WARNING, "Simple Analytics will count \"$userAgent\" as a robot and leave it out of the dashboard. " +
                "Avoid the words ${RobotWords.joinToString()} in the user agent.")
        }
    }

    /** Records [event], such as `buy_clicked`, with optional [metadata]. Names are lowercase letters, digits and underscores. */
    fun track(event: String, metadata: Map<String, String> = emptyMap()) {
        if (closed.get()) return
        if (!EventName.matches(event)) return drop("Event names are lowercase letters, digits and underscores: \"$event\"")
        val at = now()
        outbox.add(at, eventJson(hostname, event, userAgent, device, at, metadata))
    }

    /**
     * Records that the screen at [path], such as `/settings`, was shown. The first pageview of each [SimpleAnalytics]
     * counts as a visit, so the dashboard's visitors are the app's runs.
     */
    fun pageview(path: String) {
        if (closed.get()) return
        if (!path.startsWith("/")) return drop("Paths start with a slash: \"$path\"")
        val at = now()
        outbox.add(at, pageviewJson(hostname, path, userAgent, device, at, unique = firstPageview.getAndSet(false)))
    }

    /**
     * Stops recording and gives events already recorded up to [CloseTimeout] to be sent. Events still waiting stay in
     * the queue directory for the next run.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) outbox.close(CloseTimeout)
    }

    private fun now() = clock().truncatedTo(ChronoUnit.MILLIS)

    private fun drop(reason: String) = logger.log(Level.WARNING, "Not sent to Simple Analytics. $reason")

    private companion object {
        val EventName = Regex("[a-z0-9_]{1,200}")
        val RobotWords = listOf("bot", "spider", "crawl", "agent")
        val CloseTimeout: Duration = Duration.ofSeconds(2)
        const val MaxQueued = 1000
        val MaxAge: Duration = Duration.ofHours(72)
    }
}

internal val logger: System.Logger = System.getLogger(SimpleAnalytics::class.java.name)

/** Waits 1 second after the first failure, doubling up to 5 minutes. */
internal fun backoff(attempt: Int): Duration = Duration.ofSeconds(1L shl (attempt - 1).coerceIn(0, 9)).coerceAtMost(Duration.ofMinutes(5))

internal fun defaultUserAgent(appName: String, appVersion: String): String =
    "$appName/$appVersion (${System.getProperty("os.name")} ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"

/** The JSON Simple Analytics' events API takes for one event that happened [at]. */
internal fun eventJson(hostname: String, event: String, userAgent: String, device: DeviceInfo?, at: Instant, metadata: Map<String, String>): String = buildString {
    append("{\"type\":\"event\",\"hostname\":").append(json(hostname))
    append(",\"event\":").append(json(event))
    append(",\"ua\":").append(json(userAgent))
    append(",\"timestamp\":").append(json(at.toString()))
    appendDevice(device)
    if (metadata.isNotEmpty()) {
        append(",\"metadata\":{")
        metadata.entries.joinTo(this, ",") { (key, value) -> "${json(key)}:${json(value)}" }
        append("}")
    }
    append("}")
}

/** The JSON Simple Analytics' events API takes for one pageview that happened [at]. Only [unique] pageviews count as visitors. */
internal fun pageviewJson(hostname: String, path: String, userAgent: String, device: DeviceInfo?, at: Instant, unique: Boolean): String = buildString {
    append("{\"type\":\"pageview\",\"hostname\":").append(json(hostname))
    append(",\"event\":\"pageview\",\"path\":").append(json(path))
    append(",\"ua\":").append(json(userAgent))
    append(",\"timestamp\":").append(json(at.toString()))
    append(",\"unique\":").append(unique)
    appendDevice(device)
    append("}")
}

private fun StringBuilder.appendDevice(device: DeviceInfo?) {
    if (device == null) return
    append(",\"timezone\":").append(json(device.timezone))
    append(",\"language\":").append(json(device.language))
    device.screenWidth?.let { append(",\"screen_width\":").append(it) }
    device.screenHeight?.let { append(",\"screen_height\":").append(it) }
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

private class HttpPost(private val userAgent: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun post(body: String): Int {
        val request = HttpRequest.newBuilder(URI("https://queue.simpleanalyticscdn.com/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
