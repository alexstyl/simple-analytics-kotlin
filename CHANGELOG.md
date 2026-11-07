# Changelog

## [Unreleased]

- **Breaking:** User agent: the dashboard now shows your app as the browser and the operating system it ran on. The user agent is always built from `appName`, `appVersion` and the computer, and `userAgent` can no longer be passed
- **Breaking:** Closing: `close()` no longer waits for events to be sent. Call `flush()` to wait for them, such as before the app quits
- Time on page: see how long each screen stays on view, counting only while the app is visible. Call `setVisible()` when your app is minimised or goes to the background, and pass `pings` to keep a lower bound if it's killed
- Queue storage: keep unsent events anywhere, such as in your app's database, by passing your own `QueueStorage`
- Kotlin Multiplatform: add the library to the common code of Multiplatform projects that target the JVM

## [0.1.0] - 2026-11-06

- First release: send events and pageviews from Kotlin apps to Simple Analytics, with no cookies or user identifiers. Events recorded offline are sent once the app is back online.

