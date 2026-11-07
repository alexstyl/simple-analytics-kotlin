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
