package com.alexstyl.simpleanalytics

import kotlinx.coroutines.CoroutineScope
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.System.Logger.Level
import java.util.Locale
import java.util.TimeZone

internal actual fun currentDevice(): DeviceInfo {
    val screen = if (GraphicsEnvironment.isHeadless()) null else runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    return DeviceInfo(TimeZone.getDefault().id, Locale.getDefault().toLanguageTag(), screen?.width, screen?.height)
}

internal actual fun platformDescription(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}; ${System.getProperty("os.arch")}"

internal actual fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T = kotlinx.coroutines.runBlocking(block = block)

private val logger: System.Logger = System.getLogger(SimpleAnalytics::class.java.name)

internal actual fun logWarning(message: String, error: Throwable?) = logger.log(Level.WARNING, message, error)

internal actual fun logDebug(message: String, error: Throwable?) = logger.log(Level.DEBUG, message, error)
