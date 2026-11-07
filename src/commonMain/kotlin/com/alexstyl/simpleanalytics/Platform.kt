package com.alexstyl.simpleanalytics

/** This device's timezone, language and screen size. */
internal expect fun currentDevice(): DeviceInfo

/**
 * Calls [onChange] with whether the app is on view whenever that may have changed, where the platform can tell, until
 * the returned handle is closed. Null where it can't tell, such as a server with no windows.
 */
internal expect fun watchVisibility(onChange: (Boolean) -> Unit): AutoCloseable?

internal expect fun logWarning(message: String, error: Throwable? = null)

internal expect fun logDebug(message: String, error: Throwable? = null)

/**
 * Runs [send] when the app quits, where the platform can tell, until the returned handle is closed. Null where it
 * can't tell.
 */
internal expect fun onAppQuit(send: suspend () -> Unit): AutoCloseable?

/**
 * `appName/appVersion` followed by the system the app runs on, in the form browsers write it, which Simple Analytics
 * reads the operating system from, such as `MyApp/1.0.0 (Macintosh; Mac OS X 15_1; arm64)`.
 */
internal expect fun userAgent(appName: String, appVersion: String): String
