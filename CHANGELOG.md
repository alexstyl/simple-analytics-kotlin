# Changelog

## [Unreleased]

- **Breaking:** Closing: `close()` no longer waits for events to be sent. Call `flush()` to wait for them, such as before the app quits
- Queue storage: keep unsent events anywhere, such as in your app's database, by passing your own `QueueStorage`
- Kotlin Multiplatform: add the library to the common code of Multiplatform projects that target the JVM

## [0.1.0] - 2026-11-06

- First release: send events and pageviews from Kotlin apps to Simple Analytics, with no cookies or user identifiers. Events recorded offline are sent once the app is back online.

