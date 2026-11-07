package com.alexstyl.simpleanalytics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.random.Random
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
internal data class DeviceInfo(val timezone: String, val language: String, val screenWidth: Int?, val screenHeight: Int?)

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
 *
 * Like Simple Analytics' browser script, it measures how long each screen is on view: from its [pageview] until the
 * next one, counting only time while the app is on view: on desktop, the clock pauses while all its windows are
 * minimised or closed. The time is added to that pageview in
 * the dashboard's time on page. Pageviews sent more than a minute late, such as after being offline, keep the time they
 * happened instead and get no time on page.
 */
class SimpleAnalytics internal constructor(
    private val hostname: String,
    private val userAgent: String,
    private val device: DeviceInfo?,
    queueStorage: QueueStorage?,
    /** Sends one payload and returns the HTTP status; throws when it can't reach Simple Analytics. */
    post: suspend (Delivery) -> Int,
    private val pings: List<Duration> = emptyList(),
    private val clock: () -> Instant = Clock.System::now,
    retryDelay: (attempt: Int) -> Duration = ::backoff,
    maxQueued: Int = MaxQueued,
) : AutoCloseable {

    /**
     * [userAgent] tells Simple Analytics which app and system sent the events, in the form browsers use, such as
     * `MyApp/1.0.0 (Macintosh; Mac OS X 15_1; arm64)`. Events also carry this device's timezone, language and screen
     * size.
     *
     * Events that can't be sent yet are kept in memory. Pass a [queueStorage] to keep them between runs instead.
     * Use one instance per hostname and storage.
     *
     * [pings] also send a screen's time on view as it passes each of them, such as 5 and 60 seconds, so its time on
     * page is known even if the app is killed before it can report it.
     */
    constructor(
        hostname: String,
        userAgent: String,
        queueStorage: QueueStorage? = null,
        pings: List<Duration> = emptyList(),
    ) : this(hostname, userAgent, currentDevice(), queueStorage, { delivery -> deliver(userAgent, delivery) }, pings) {
        visibility = watchVisibility(::setVisible)
        quitHook = onAppQuit { closeAndSend(QuitTimeout) }
    }

    /** Follows whether the app is on view, where the platform can tell; closed with this instance. */
    private var visibility: AutoCloseable? = null

    /** Sends what's left when the app quits, where the platform can tell; removed when this instance is closed. */
    private var quitHook: AutoCloseable? = null

    /** Pageviews sent as they happen, by id: only these can have time on page added. Touched only while sending. */
    private val livePages = mutableSetOf<String>()
    private val outbox = Outbox(queueStorage, { body, at -> route(body, at, post) }, clock, retryDelay, maxQueued, maxAge = MaxAge)
    private val view = AtomicReference(View(pageId = null, visibleMillis = 0, visibleSince = null, visible = true))
    private val timers = CoroutineScope(Dispatchers.Default + CoroutineName("SimpleAnalytics pings"))
    private var pingJob: Job? = null
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
        val id = randomId()
        val before = view.fetchAndUpdate { it.copy(pageId = id, visibleMillis = 0, visibleSince = if (it.visible) at else null) }
        before.pageId?.let { appendTime(it, before.visibleMillis(at)) }
        outbox.add(at, pageviewJson(hostname, path, userAgent, device, at, unique = firstPageview.exchange(false), id = id))
        schedulePings()
    }

    /**
     * Whether the app is on view: `false` once its windows are minimised or it goes to the background, `true` when
     * it's back. Only time on view counts towards time on page; hiding also sends the time so far.
     */
    internal fun setVisible(visible: Boolean) {
        if (closed.load()) return
        val at = now()
        val before = view.fetchAndUpdate {
            if (it.visible == visible) it
            else it.copy(visible = visible, visibleMillis = it.visibleMillis(at), visibleSince = if (visible) at else null)
        }
        if (before.visible == visible) return
        if (!visible) before.pageId?.let { appendTime(it, before.visibleMillis(at)) }
        schedulePings()
    }

    /**
     * Waits until the events recorded so far are sent, or until Simple Analytics can't be reached and they're waiting
     * for a retry. Use it with a timeout, such as before the app quits.
     */
    suspend fun flush() = outbox.flush()

    /**
     * Stops recording without waiting. Events already recorded are still sent in the background, unless Simple
     * Analytics can't be reached; those stay in the [QueueStorage] for the next run. There's usually no need to call
     * it: when the app quits, what's left is sent for up to 2 seconds by itself.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { visibility?.close() }
        runCatching { quitHook?.close() }
        timers.cancel()
        val current = view.load()
        current.pageId?.let { appendTime(it, current.visibleMillis(now())) }
        outbox.close()
    }

    /** Closes, then waits up to [timeout] for what's left, including the last screen's time on view, to be sent. */
    internal suspend fun closeAndSend(timeout: Duration) {
        close()
        withTimeoutOrNull(timeout) { flush() }
    }

    /** Adds the time [pageId] has been on view to that pageview, when there's at least a second of it. */
    private fun appendTime(pageId: String, visibleMillis: Long, ping: Boolean = false) {
        val seconds = visibleMillis / 1000
        if (seconds >= 1) outbox.add(now(), appendJson(hostname, pageId, seconds, ping))
    }

    /** Waits until the screen on view passes each of [pings] of time on view, sending its time at each. */
    private fun schedulePings() {
        if (pings.isEmpty()) return
        val started = view.load()
        val pageId = started.pageId ?: return
        pingJob?.cancel()
        if (!started.visible) return
        pingJob = timers.launch {
            for (threshold in pings.sorted()) {
                val waited = started.visibleMillis(now())
                val remaining = threshold.inWholeMilliseconds - waited
                if (remaining <= 0) continue
                delay(remaining)
                val current = view.load()
                if (current.pageId != pageId || !current.visible) return@launch
                appendTime(pageId, maxOf(current.visibleMillis(now()), threshold.inWholeMilliseconds), ping = true)
            }
        }
    }

    /**
     * Sends a queued payload the way Simple Analytics' browser script would when it's fresh: a pageview within a minute
     * of happening goes as a live pageview, so time on page can be added to it later. Older pageviews go to the events
     * API with the time they happened. Time on page for a pageview that didn't go live has nowhere to go and is dropped.
     */
    private suspend fun route(body: String, at: Instant, post: suspend (Delivery) -> Int): Int = when {
        body.startsWith(PageviewPrefix) -> {
            val id = jsonFields(body)["id"]
            if (id != null && clock() - at <= LiveWindow) post(Delivery(Delivery.Kind.Pageview, body)).also { if (it in 200..299) livePages += id }
            else post(Delivery(Delivery.Kind.Event, body.replace(IdField, "")))
        }
        body.startsWith(AppendPrefix) ->
            if (jsonFields(body)["original_id"] in livePages) post(Delivery(Delivery.Kind.Append, body)) else 200
        else -> post(Delivery(Delivery.Kind.Event, body))
    }

    private fun now() = Instant.fromEpochMilliseconds(clock().toEpochMilliseconds())

    private fun drop(reason: String) = logWarning("Not sent to Simple Analytics. $reason")

    private companion object {
        val EventName = Regex("[a-z0-9_]{1,200}")
        val RobotWords = listOf("bot", "spider", "crawl", "agent")
        const val MaxQueued = 1000
        val MaxAge = 72.hours
        val LiveWindow = 1.minutes
        val QuitTimeout = 2.seconds
        const val PageviewPrefix = "{\"type\":\"pageview\""
        const val AppendPrefix = "{\"type\":\"append\""
        val IdField = Regex(",\"id\":\"[^\"]*\"")
    }
}

/** Waits 1 second after the first failure, doubling up to 5 minutes. */
internal fun backoff(attempt: Int): Duration = (1L shl (attempt - 1).coerceIn(0, 9)).seconds.coerceAtMost(5.minutes)

/** The JSON Simple Analytics' events API takes for one event that happened [at]. */
internal fun eventJson(hostname: String, event: String, userAgent: String, device: DeviceInfo?, at: Instant, metadata: Map<String, String>): String = buildString {
    append("{\"type\":\"event\",\"hostname\":").append(json(hostname))
    append(",\"event\":").append(json(event))
    append(",\"ua\":").append(json(userAgent))
    append(",\"https\":true")
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
internal fun pageviewJson(hostname: String, path: String, userAgent: String, device: DeviceInfo?, at: Instant, unique: Boolean,
                          id: String? = null): String = buildString {
    append("{\"type\":\"pageview\",\"hostname\":").append(json(hostname))
    append(",\"event\":\"pageview\",\"path\":").append(json(path))
    append(",\"ua\":").append(json(userAgent))
    append(",\"https\":true")
    append(",\"timestamp\":").append(json(at.toString()))
    append(",\"unique\":").append(unique)
    appendDevice(device)
    id?.let { append(",\"id\":").append(json(it)) }
    append("}")
}

/** Adds [seconds] on view to the pageview [originalId], as the browser script's append request does. */
internal fun appendJson(hostname: String, originalId: String, seconds: Long, ping: Boolean): String = buildString {
    append("{\"type\":\"append\",\"hostname\":").append(json(hostname))
    append(",\"original_id\":").append(json(originalId))
    append(",\"duration\":").append(seconds)
    if (ping) append(",\"ping\":true")
    append(",\"version\":").append(json(ScriptVersion))
    append("}")
}

/** The screen on view: its pageview [pageId] and how long it has been on view, counting only while [visible]. */
internal data class View(val pageId: String?, val visibleMillis: Long, val visibleSince: Instant?, val visible: Boolean) {
    fun visibleMillis(at: Instant): Long = visibleMillis + (visibleSince?.let { (at - it).inWholeMilliseconds.coerceAtLeast(0) } ?: 0)
}

/** One payload and where it goes: the events API, a live pageview, or time on page for one. */
internal class Delivery(val kind: Kind, val body: String) {
    enum class Kind { Event, Pageview, Append }
}

/** The fields of one of our flat JSON payloads, with strings unescaped. Nested objects, such as metadata, are skipped. */
internal fun jsonFields(body: String): Map<String, String> =
    Regex("\"([a-z_]+)\":(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+)").findAll(body.substringBefore("\"metadata\":"))
        .associate { match ->
            val raw = match.groupValues[2]
            match.groupValues[1] to if (raw.startsWith('"')) unescape(raw.substring(1, raw.length - 1)) else raw
        }

private fun unescape(value: String): String = buildString {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '\\' || i + 1 >= value.length) { append(c); i++; continue }
        when (val next = value[i + 1]) {
            'u' -> { append(value.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
            else -> { append(next); i += 2 }
        }
    }
}

/** A random version 4 UUID, which the browser script uses as a pageview's id. */
internal fun randomId(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/** The browser script version Simple Analytics expects on live pageviews and time on page, as its script sends it. */
internal const val ScriptVersion = "cdn_latest_12"

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

private const val Queue = "https://queue.simpleanalyticscdn.com"

/**
 * Sends [delivery] to Simple Analytics: events and late pageviews to the events API, live pageviews as the browser
 * script's pixel request with their id, and time on page to the script's append endpoint.
 */
private suspend fun deliver(userAgent: String, delivery: Delivery): Int = when (delivery.kind) {
    Delivery.Kind.Event -> httpClient.post("$Queue/events") {
        contentType(ContentType.Application.Json)
        userAgent(userAgent)
        setBody(delivery.body)
    }.status.value
    Delivery.Kind.Pageview -> httpClient.get("$Queue/simple.gif") {
        userAgent(userAgent)
        val fields = jsonFields(delivery.body) - setOf("event", "timestamp")
        (fields + mapOf("version" to ScriptVersion, "sri" to "false")).forEach { (key, value) -> parameter(key, value) }
        parameter("time", Clock.System.now().toEpochMilliseconds())
    }.status.value
    Delivery.Kind.Append -> httpClient.post("$Queue/append") {
        contentType(ContentType.Text.Plain)
        userAgent(userAgent)
        setBody(delivery.body)
    }.status.value
}
