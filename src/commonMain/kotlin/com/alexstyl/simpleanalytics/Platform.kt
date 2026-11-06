package com.alexstyl.simpleanalytics

/** This device's timezone, language and screen size. */
internal expect fun currentDevice(): DeviceInfo

internal expect fun logWarning(message: String, error: Throwable? = null)

internal expect fun logDebug(message: String, error: Throwable? = null)
