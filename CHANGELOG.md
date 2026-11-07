# Changelog

## [Unreleased]

- **Breaking:** Device settings: the `device` option is gone. Events always carry the computer's timezone, language and screen size, as the browser script does
- **Breaking:** User agent: the dashboard now shows your app as the browser and the operating system it ran on. The user agent is always built from `appName`, `appVersion` and the computer, and `userAgent` can no longer be passed
- **Breaking:** Closing: `close()` no longer waits for events to be sent. Call `flush()` when you need to wait for them
- Quitting: events recorded right before the app quits, and its last screen's time on view, are sent as it quits, waiting up to 2 seconds
- Time on page: see how long each screen stays on view. Time while all the app's windows are minimised or closed doesn't count. Pass `pings` to keep a lower bound if the app is killed
- Queue storage: keep unsent events anywhere, such as in your app's database, by passing your own `QueueStorage`
- Kotlin Multiplatform: add the library to the common code of Multiplatform projects that target the JVM

## [0.1.0] - 2026-11-06

- First release: send events and pageviews from Kotlin apps to Simple Analytics, with no cookies or user identifiers. Events recorded offline are sent once the app is back online.

