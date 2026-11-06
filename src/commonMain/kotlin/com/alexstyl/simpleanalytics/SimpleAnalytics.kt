package com.alexstyl.simpleanalytics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The device's settings that Simple Analytics reports on. It works out the country from [timezone], an IANA id
 * such as `Europe/Athens`. [language] is a language tag such as `en-US`.
 */
data class DeviceInfo(val timezone: String, val language: String, val screenWidth: Int?, val screenHeight: Int?) {
    companion object {
        /** This device's timezone, language and, when it has a display, the size of its main screen. */
        fun current(): DeviceInfo = currentDevice()
    }
}

/**
 * Keeps the events that haven't been sent yet, so they're sent on the app's next run. The queue is opaque text: [write]
 * it as given and [read] it back unchanged, or null when nothing was written.
 */
interface QueueStorage {
    fun read(): String?
    fun write(queue: String)
}

/**
 * Sends events and pageviews to Simple Analytics for [hostname], a website added in its dashboard. They carry no cookies
 * or identifiers: only the event or path, its details, when it happened, the user agent and the [device]'s settings.
 *
 * Calls never block and never throw. Events are sent in order in the background. While the device is offline
 * they wait and are retried with increasing delays, keeping the time they happened. With a [QueueStorage] they also
 * survive the app quitting. Invalid input and events Simple Analytics refuses are logged and dropped.
 */
class SimpleAnalytics internal constructor(
    private val hostname: String,
    private val userAgent: String,
    private val device: DeviceInfo?,
    queueStorage: QueueStorage?,
    /** Posts one JSON payload and returns the HTTP status; throws when it can't reach Simple Analytics. */
    post: suspend (String) -> Int,
    private val clock: () -> Instant = Clock.System::now,
    retryDelay: (attempt: Int) -> Duration = ::backoff,
    maxQueued: Int = MaxQueued,
) : AutoCloseable {

    /**
     * [userAgent] tells Simple Analytics which app and system sent the events. [device] defaults to this device's
     * settings; null sends none.
     *
     * Events that can't be sent yet are kept in memory. Pass a [queueStorage] to keep them between runs instead.
     * Use one instance per hostname and storage.
     */
    constructor(
        hostname: String,
        userAgent: String,
        device: DeviceInfo? = DeviceInfo.current(),
        queueStorage: QueueStorage? = null,
    ) : this(hostname, userAgent, device, queueStorage, { body -> postEvent(userAgent, body) })

    private val outbox = Outbox(queueStorage, post, clock, retryDelay, maxQueued, maxAge = MaxAge)
    private val firstPageview = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)

    init {
        if (RobotWords.any { it in userAgent.lowercase() }) {
            logWarning("Simple Analytics will count \"$userAgent\" as a robot and leave it out of the dashboard. " +
                "Avoid the words ${RobotWords.joinToString()} in the user agent.")
        }
    }

    /** Records [event], such as `buy_clicked`, with optional [metadata]. Names are lowercase letters, digits and underscores. */
    fun track(event: String, metadata: Map<String, String> = emptyMap()) {
        if (closed.load()) return
        if (!EventName.matches(event)) return drop("Event names are lowercase letters, digits and underscores: \"$event\"")
        val at = now()
        outbox.add(at, eventJson(hostname, event, userAgent, device, at, metadata))
    }

    /**
     * Records that the screen at [path], such as `/settings`, was shown. The first pageview of each [SimpleAnalytics]
     * counts as a visit, so the dashboard's visitors are the app's runs.
     */
    fun pageview(path: String) {
        if (closed.load()) return
        if (!path.startsWith("/")) return drop("Paths start with a slash: \"$path\"")
        val at = now()
        outbox.add(at, pageviewJson(hostname, path, userAgent, device, at, unique = firstPageview.exchange(false)))
    }

    /**
     * Waits until the events recorded so far are sent, or until Simple Analytics can't be reached and they're waiting
     * for a retry. Use it with a timeout, such as before the app quits.
     */
    suspend fun flush() = outbox.flush()

    /**
     * Stops recording without waiting. Events already recorded are still sent in the background, unless Simple
     * Analytics can't be reached; those stay in the [QueueStorage] for the next run.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) outbox.close()
    }

    private fun now() = Instant.fromEpochMilliseconds(clock().toEpochMilliseconds())

    private fun drop(reason: String) = logWarning("Not sent to Simple Analytics. $reason")

    private companion object {
        val EventName = Regex("[a-z0-9_]{1,200}")
        val RobotWords = listOf("bot", "spider", "crawl", "agent")
        const val MaxQueued = 1000
        val MaxAge = 72.hours
    }
}

/** Waits 1 second after the first failure, doubling up to 5 minutes. */
internal fun backoff(attempt: Int): Duration = (1L shl (attempt - 1).coerceIn(0, 9)).seconds.coerceAtMost(5.minutes)

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
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}

private val httpClient by lazy {
    HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }
}

private suspend fun postEvent(userAgent: String, body: String): Int =
    httpClient.post("https://queue.simpleanalyticscdn.com/events") {
        contentType(ContentType.Application.Json)
        userAgent(userAgent)
        setBody(body)
    }.status.value
