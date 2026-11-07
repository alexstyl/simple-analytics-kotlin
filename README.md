![Simple Analytics for Kotlin: the Kotlin logo pointing to the Simple Analytics bars](.github/header.png)

# Simple Analytics for Kotlin

Send events and pageviews from your Kotlin app to [Simple Analytics](https://simpleanalytics.com), with no cookies and no user identifiers.

## Features

- **Events with details:** count what people do, such as `buy_clicked` with `source=toolbar`.
- **Pageviews for app screens:** see your app's screens in the dashboard's pages report, just like a website.
- **Country, language and screen reports out of the box:** the computer's timezone, language and screen size are sent automatically.
- **Accurate visitor counts:** each run of your app counts as one visit, not every screen it shows.
- **Time on page:** see how long people keep each screen on view. Time while the app is minimised doesn't count, with nothing for you to wire up.
- **Works offline:** events recorded without a connection wait and are sent once it's back, landing on the day they happened. They can even survive the app quitting.
- **Never slows down or crashes your app:** events are sent on a background thread. Invalid names, failed requests and network errors are logged and dropped, never thrown.
- **Shows your app like a browser:** the dashboard lists your app by name, next to Chrome and Safari, with the operating system it ran on.
- **Kotlin Multiplatform:** add it to the common code of projects that target the JVM. It uses Ktor and kotlinx libraries.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.alexstyl:simple-analytics-kotlin:0.1.0")
}
```

The library is published on Maven Central, so make sure `mavenCentral()` is in your repositories.

## Quick start

First add your app as a website in the Simple Analytics dashboard, using a hostname such as `app.example.com`. Then send your first event by creating a `SimpleAnalytics` with that hostname and your app's name and version, and calling `track()`:

```kotlin
val analytics = SimpleAnalytics(hostname = "app.example.com", appName = "MyApp", appVersion = "1.0.0")

analytics.track("app_opened")
```

`app_opened` shows up in the dashboard's events report for `app.example.com`, along with the visitor's country, language, operating system and screen size. The browser column shows `MyApp`.

## Examples

### Track a button click

To tell apart which button people clicked, pass details as metadata to `track()`:

```kotlin
analytics.track("buy_clicked", mapOf("source" to "toolbar"))
```

Event names can only contain lowercase letters, digits and underscores. Other names are logged and not sent.

### Track screen views

To count how often a screen is shown, call `pageview()` with a path for that screen whenever your app navigates to it:

```kotlin
analytics.pageview("/settings")
```

Each screen appears in the dashboard's pages report. Call it from your navigation code rather than from UI code that can run many times for the same screen.

### See how long people spend on each screen

Each screen's time on view is measured from its `pageview()` until the next one, and shows up as time on page in the dashboard's pages report. There's nothing to turn on:

```kotlin
analytics.pageview("/")          // the home screen shows; its clock starts
analytics.pageview("/settings")  // the 42 seconds on / are added to its pageview
```

Only time while your app is on view counts, as browsers only count a visible tab. On desktop the clock pauses by itself while all your app's windows are minimised or closed, and picks up again when one is back. A window that's open behind another app still counts as on view, as a browser tab on screen does. `close()` adds the time of the screen on view when the app quits.

### Keep time on page when the app is killed

An app that's force-quit or crashes can't report how long its last screen was on view. To keep a lower bound, pass `pings`, and the time on view is also sent as it passes each of them:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0", pings = listOf(5.seconds, 60.seconds))
```

### Count visitors

The dashboard counts a visitor for the first pageview each `SimpleAnalytics` sends, so create one when your app starts and use it for the whole run. Its visitors are then your app's runs:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0")

analytics.pageview("/")        // a visitor
analytics.pageview("/settings") // not a new visitor
```

### Keep events recorded offline

When the computer is offline, events wait and are retried with increasing delays, up to every 5 minutes. They keep the time they happened, so they land on the right day in the dashboard. By default they wait in memory and are lost if the app quits first. To keep them until the next run, pass a `queueDirectory`, such as your app's data folder:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0", queueDirectory = File(appDataDir, "analytics"))
```

Up to 1000 events wait at a time, and events older than 72 hours are dropped. Use one `SimpleAnalytics` per hostname and directory.

Pageviews sent more than a minute after they happened, such as after being offline, keep the time they happened but get no time on page.

### Send events right before the app quits

Events are sent in the background, so ones recorded right before your app exits can be lost. Wait for them with `flush()` when your app quits, with a timeout so a missing connection can't hold the app up, then `close()`. With a `queueDirectory`, any still waiting are sent on the next run:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking { withTimeoutOrNull(2.seconds) { analytics.flush() } }
    analytics.close()
})
```

### See which version and system people use

Every event and pageview carries a user agent made from your app's name and version and the computer it runs on, written the way browsers write theirs:

```
MyApp/1.0.0 (Macintosh; Mac OS X 15_4; arm64)
MyApp/1.0.0 (Windows NT 10.0; Win64; x64)
MyApp/1.0.0 (X11; Linux x86_64)
```

Simple Analytics reads it like a browser's: the dashboard shows `MyApp` as the browser and macOS, Windows or Linux as the operating system. Pass the version people see, such as `1.4.0`, as `appVersion`.

## Troubleshooting

### Events don't show up in the dashboard

Check that `hostname` exactly matches a website you added in Simple Analytics, such as `app.example.com`. The dashboard only shows data for the websites in your account.

### "Simple Analytics will count … as a robot"

Simple Analytics leaves out traffic from user agents containing `bot`, `spider`, `crawl` or `agent`. Choose an app name without those words, such as `MyApp` instead of `MyApp Agent`.

### "Not sent to Simple Analytics"

An event name or path was invalid. Event names can only contain lowercase letters, digits and underscores, and paths must start with `/`.

### "Simple Analytics refused … with 403"

Simple Analytics doesn't accept data for a hostname that isn't in an account. Check the `hostname` against your dashboard.
