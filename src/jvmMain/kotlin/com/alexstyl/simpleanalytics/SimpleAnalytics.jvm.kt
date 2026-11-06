package com.alexstyl.simpleanalytics

import kotlinx.io.files.Path
import java.io.File

/**
 * Sends events and pageviews to Simple Analytics for [hostname] from a desktop or server app. [appName] and
 * [appVersion] make up the default [userAgent], such as `MyApp/1.0.0 (Mac OS X 15.1; aarch64)`. [device] defaults to
 * this computer's settings; null sends none.
 *
 * Events that can't be sent yet are kept in memory. Pass a [queueDirectory], such as your app's data folder, to keep
 * them on disk instead, so they're sent on the app's next run. Use one instance per hostname and directory.
 */
fun SimpleAnalytics(
    hostname: String,
    appName: String,
    appVersion: String,
    userAgent: String = defaultUserAgent(appName, appVersion),
    device: DeviceInfo? = DeviceInfo.current(),
    queueDirectory: File? = null,
): SimpleAnalytics = SimpleAnalytics(hostname, userAgent, device, queueDirectory?.let { queueFile(it, hostname) })

internal fun queueFile(directory: File, hostname: String) = FileQueueStorage(Path(File(directory, "simple-analytics-$hostname.queue").path))

internal fun defaultUserAgent(appName: String, appVersion: String): String =
    "$appName/$appVersion (${System.getProperty("os.name")} ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"
