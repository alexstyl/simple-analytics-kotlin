package com.alexstyl.simpleanalytics

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.System.Logger.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * or identifiers: only the event or path, its details, the user agent and the [device]'s settings.
 *
 * Calls never block and never throw. Sending happens on a background thread; invalid input and failed requests are
 * logged and dropped. Call [close] when the app quits so queued events get sent.
 */
class SimpleAnalytics internal constructor(
    private val hostname: String,
    private val userAgent: String,
    private val device: DeviceInfo?,
    /** Posts one JSON payload. */
    private val post: (String) -> Unit,
    private val executor: ExecutorService,
) : AutoCloseable {

    /**
     * [appName] and [appVersion] make up the default [userAgent], such as `MyApp/1.0.0 (Mac OS X 15.1; aarch64)`.
     * [device] defaults to this computer's settings; null sends none.
     */
    constructor(
        hostname: String,
        appName: String,
        appVersion: String,
        userAgent: String = defaultUserAgent(appName, appVersion),
        device: DeviceInfo? = DeviceInfo.current(),
    ) : this(hostname, userAgent, device, HttpPost(userAgent)::post,
        Executors.newSingleThreadExecutor { Thread(it, "SimpleAnalytics").apply { isDaemon = true } })

    private val firstPageview = AtomicBoolean(true)

    init {
        if (RobotWords.any { it in userAgent.lowercase() }) {
            logger.log(Level.WARNING, "Simple Analytics will count \"$userAgent\" as a robot and leave it out of the dashboard. " +
                "Avoid the words ${RobotWords.joinToString()} in the user agent.")
        }
    }

    /** Records [event], such as `buy_clicked`, with optional [metadata]. Names are lowercase letters, digits and underscores. */
    fun track(event: String, metadata: Map<String, String> = emptyMap()) {
        if (!EventName.matches(event)) return drop("Event names are lowercase letters, digits and underscores: \"$event\"")
        send(eventJson(hostname, event, userAgent, device, metadata))
    }

    /**
     * Records that the screen at [path], such as `/settings`, was shown. The first pageview of each [SimpleAnalytics]
     * counts as a visit, so the dashboard's visitors are the app's runs.
     */
    fun pageview(path: String) {
        if (!path.startsWith("/")) return drop("Paths start with a slash: \"$path\"")
        send(pageviewJson(hostname, path, userAgent, device, unique = firstPageview.getAndSet(false)))
    }

    /** Stops recording and waits up to [CloseTimeout] for queued events to be sent. */
    override fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(CloseTimeout.toMillis(), TimeUnit.MILLISECONDS) }
    }

    private fun send(body: String) {
        runCatching {
            executor.execute {
                runCatching { post(body) }.onFailure { logger.log(Level.DEBUG, "Couldn't send to Simple Analytics", it) }
            }
        }
    }

    private fun drop(reason: String) = logger.log(Level.WARNING, "Not sent to Simple Analytics. $reason")

    private companion object {
        val logger: System.Logger = System.getLogger(SimpleAnalytics::class.java.name)
        val EventName = Regex("[a-z0-9_]{1,200}")
        val RobotWords = listOf("bot", "spider", "crawl", "agent")
        val CloseTimeout: Duration = Duration.ofSeconds(2)
    }
}

internal fun defaultUserAgent(appName: String, appVersion: String): String =
    "$appName/$appVersion (${System.getProperty("os.name")} ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"

/** The JSON Simple Analytics' events API takes for one event. */
internal fun eventJson(hostname: String, event: String, userAgent: String, device: DeviceInfo?, metadata: Map<String, String>): String = buildString {
    append("{\"type\":\"event\",\"hostname\":").append(json(hostname))
    append(",\"event\":").append(json(event))
    append(",\"ua\":").append(json(userAgent))
    appendDevice(device)
    if (metadata.isNotEmpty()) {
        append(",\"metadata\":{")
        metadata.entries.joinTo(this, ",") { (key, value) -> "${json(key)}:${json(value)}" }
        append("}")
    }
    append("}")
}

/** The JSON Simple Analytics' events API takes for one pageview. Only [unique] pageviews count as visitors. */
internal fun pageviewJson(hostname: String, path: String, userAgent: String, device: DeviceInfo?, unique: Boolean): String = buildString {
    append("{\"type\":\"pageview\",\"hostname\":").append(json(hostname))
    append(",\"event\":\"pageview\",\"path\":").append(json(path))
    append(",\"ua\":").append(json(userAgent))
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

    fun post(body: String) {
        val request = HttpRequest.newBuilder(URI("https://queue.simpleanalyticscdn.com/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299) { "Simple Analytics responded ${response.statusCode()}" }
    }
}
