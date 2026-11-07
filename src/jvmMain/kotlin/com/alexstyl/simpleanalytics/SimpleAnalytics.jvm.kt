package com.alexstyl.simpleanalytics

import kotlinx.io.files.Path
import java.io.File
import kotlin.time.Duration

/**
 * Sends events and pageviews to Simple Analytics for [hostname] from a desktop or server app. They carry a user agent
 * built from [appName], [appVersion] and this computer, in the form browsers use, such as
 * `MyApp/1.0.0 (Macintosh; Mac OS X 15_1; arm64)`, so the dashboard shows your app as the browser and the system it ran
 * on, along with this computer's timezone, language and screen size.
 *
 * Time on page counts only while one of the app's windows is on view. [pings] send a screen's time on view as it
 * passes each of them, such as 5 and 60 seconds.
 *
 * Events that can't be sent yet are kept in memory. Pass a [queueDirectory], such as your app's data folder, to keep
 * them on disk instead, so they're sent on the app's next run. Use one instance per hostname and directory.
 */
fun SimpleAnalytics(
    hostname: String,
    appName: String,
    appVersion: String,
    queueDirectory: File? = null,
    pings: List<Duration> = emptyList(),
): SimpleAnalytics =
    SimpleAnalytics(hostname, userAgent(appName, appVersion), queueDirectory?.let { queueFile(it, hostname) }, pings)

internal fun queueFile(directory: File, hostname: String) = FileQueueStorage(Path(File(directory, "simple-analytics-$hostname.queue").path))

/**
 * `appName/appVersion` followed by this computer's system in the form browsers write it, which Simple Analytics reads
 * the operating system from: `(Macintosh; Mac OS X 15_1; arm64)`, `(Windows NT 10.0; Win64; x64)` or `(X11; Linux x86_64)`.
 */
internal fun userAgent(
    appName: String,
    appVersion: String,
    osName: String = System.getProperty("os.name"),
    osVersion: String = System.getProperty("os.version"),
    osArch: String = System.getProperty("os.arch"),
): String {
    val arch = when (osArch.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> osArch
    }
    val system = when {
        osName.startsWith("Mac", ignoreCase = true) -> "Macintosh; Mac OS X ${osVersion.replace('.', '_')}; $arch"
        osName.startsWith("Windows", ignoreCase = true) -> "Windows NT ${windowsNtVersion(osName, osVersion)}; " +
            if (arch == "arm64") "ARM64" else "Win64; x64"
        osName.startsWith("Linux", ignoreCase = true) -> "X11; Linux $arch"
        else -> "$osName $osVersion; $arch"
    }
    return "$appName/$appVersion ($system)"
}

/** Windows 11 still reports itself as NT 10.0, as browsers do. */
private fun windowsNtVersion(osName: String, osVersion: String) = if (osName == "Windows 11") "10.0" else osVersion
