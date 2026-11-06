# Changelog

## [Unreleased]

- **Breaking:** Queue directory: pass `queueDirectory` as a `kotlinx.io.files.Path`, such as `Path(appDataDir, "analytics")`, instead of a `File`
- Kotlin Multiplatform: add the library to the common code of Multiplatform projects that target the JVM

## [0.1.0] - 2026-11-06

- First release: send events and pageviews from Kotlin apps to Simple Analytics, with no cookies or user identifiers. Events recorded offline are sent once the app is back online.

