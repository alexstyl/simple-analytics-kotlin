package com.alexstyl.simpleanalytics

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.System.Logger.Level
import java.util.Locale
import java.util.TimeZone

internal actual fun currentDevice(): DeviceInfo {
    val screen = if (GraphicsEnvironment.isHeadless()) null else runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    return DeviceInfo(TimeZone.getDefault().id, Locale.getDefault().toLanguageTag(), screen?.width, screen?.height)
}

private val logger: System.Logger = System.getLogger(SimpleAnalytics::class.java.name)

internal actual fun logWarning(message: String, error: Throwable?) = logger.log(Level.WARNING, message, error)

internal actual fun logDebug(message: String, error: Throwable?) = logger.log(Level.DEBUG, message, error)
