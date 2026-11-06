package com.alexstyl.simpleanalytics

import kotlinx.coroutines.CoroutineScope

/** This device's timezone, language and screen size. */
internal expect fun currentDevice(): DeviceInfo

/** The operating system and architecture for the user agent, such as `Mac OS X 15.1; aarch64`. */
internal expect fun platformDescription(): String

/** Blocks the calling thread until [block] finishes. */
internal expect fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T

internal expect fun logWarning(message: String, error: Throwable? = null)

internal expect fun logDebug(message: String, error: Throwable? = null)
