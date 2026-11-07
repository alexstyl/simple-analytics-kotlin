package com.alexstyl.simpleanalytics

import java.awt.AWTEvent
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
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

/** A window of the app: whether it's showing, and whether it's minimised. */
internal data class WindowView(val showing: Boolean, val minimised: Boolean)

/** The app is on view while any of its windows is showing and not minimised, as a browser tab on screen is. */
internal fun appOnView(windows: List<WindowView>): Boolean = windows.any { it.showing && !it.minimised }

/** Follows the app's windows, such as Compose Desktop or Swing windows, through AWT's window events. */
internal actual fun watchVisibility(onChange: (Boolean) -> Unit): AutoCloseable? {
    if (GraphicsEnvironment.isHeadless()) return null
    val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull() ?: return null
    val listener = AWTEventListener { event ->
        if (event !is WindowEvent) return@AWTEventListener
        val windows = Window.getWindows().map { WindowView(it.isShowing, it is Frame && it.extendedState and Frame.ICONIFIED != 0) }
        onChange(appOnView(windows))
    }
    toolkit.addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_STATE_EVENT_MASK)
    return AutoCloseable { toolkit.removeAWTEventListener(listener) }
}
