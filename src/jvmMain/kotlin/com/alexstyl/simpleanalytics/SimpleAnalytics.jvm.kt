package com.alexstyl.simpleanalytics

import kotlinx.io.files.Path
import java.io.File
import kotlin.time.Duration

/**
 * The same settings as the other `SimpleAnalytics()`, keeping events that can't be sent yet in [queueDirectory], such
 * as your app's data folder, so they're sent on the app's next run.
 */
fun SimpleAnalytics(
    hostname: String,
    appName: String,
    appVersion: String,
    queueDirectory: File,
    pings: List<Duration> = emptyList(),
): SimpleAnalytics = SimpleAnalytics(hostname, appName, appVersion, queueFile(queueDirectory, hostname), pings)

internal fun queueFile(directory: File, hostname: String) = FileQueueStorage(Path(File(directory, "simple-analytics-$hostname.queue").path))

/**
 * `appName/appVersion` followed by this computer's system in the form browsers write it, which Simple Analytics reads
 * the operating system from: `(Macintosh; Mac OS X 15_1; arm64)`, `(Windows NT 10.0; Win64; x64)` or `(X11; Linux x86_64)`.
 */
internal actual fun userAgent(appName: String, appVersion: String): String = userAgent(appName, appVersion, System.getProperty("os.name"))

internal fun userAgent(
    appName: String,
    appVersion: String,
    osName: String,
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
