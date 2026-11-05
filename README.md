![Simple Analytics for Kotlin: the Kotlin logo pointing to the Simple Analytics bars](.github/header.png)

# Simple Analytics for Kotlin

Send events and pageviews from your Kotlin app to [Simple Analytics](https://simpleanalytics.com), with no cookies and no user identifiers.

## Features

- **Events with details:** count what people do, such as `buy_clicked` with `source=toolbar`.
- **Pageviews for app screens:** see your app's screens in the dashboard's pages report, just like a website.
- **Country, language and screen reports out of the box:** the computer's timezone, language and screen size are sent automatically.
- **Accurate visitor counts:** each run of your app counts as one visit, not every screen it shows.
- **Never slows down or crashes your app:** events are sent on a background thread. Invalid names, failed requests and network errors are logged and dropped, never thrown.
- **No dependencies:** uses only the JDK.

## Installation

The library isn't on Maven Central yet. Clone it next to your project:

```sh
git clone https://github.com/alexstyl/simple-analytics-kotlin.git
```

Include it as a composite build in your `settings.gradle.kts`:

```kotlin
includeBuild("../simple-analytics-kotlin") {
    dependencySubstitution {
        substitute(module("com.alexstyl:simple-analytics-kotlin")).using(project(":"))
    }
}
```

Then add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.alexstyl:simple-analytics-kotlin")
}
```

## Quick start

First add your app as a website in the Simple Analytics dashboard, using a hostname such as `app.example.com`. Then send your first event by creating a `SimpleAnalytics` with that hostname and your app's name and version, and calling `track()`:

```kotlin
val analytics = SimpleAnalytics(hostname = "app.example.com", appName = "MyApp", appVersion = "1.0.0")

analytics.track("app_opened")
```

`app_opened` shows up in the dashboard's events report for `app.example.com`, along with the visitor's country, language, operating system and screen size.

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

### Count visitors

The dashboard counts a visitor for the first pageview each `SimpleAnalytics` sends, so create one when your app starts and use it for the whole run. Its visitors are then your app's runs:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0")

analytics.pageview("/")        // a visitor
analytics.pageview("/settings") // not a new visitor
```

### Send queued events when the app quits

Events are sent in the background, so ones recorded right before your app exits can be lost. To send them, call `close()` when your app quits. It waits up to 2 seconds for queued events:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread { analytics.close() })
```

### Turn off analytics in development builds

To keep test runs and debug builds out of your numbers, only create a `SimpleAnalytics` in release builds and call it with `?.`:

```kotlin
val analytics = if (isRelease) SimpleAnalytics("app.example.com", "MyApp", "1.0.0") else null

analytics?.track("app_opened")
```

### Leave out the visitor's country, language and screen

To send none of the computer's settings, pass `device = null`:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0", device = null)
```

You can also pass your own `DeviceInfo`, for example with the size of the screen your window is on.

### Use your own user agent

The user agent defaults to your app's name and version plus the operating system and processor, such as `MyApp/1.0.0 (Mac OS X 15.1; aarch64)`. Simple Analytics shows the operating system from it. To send a different one, pass `userAgent`:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp", "1.0.0", userAgent = "MyApp/1.0.0 (+https://example.com)")
```

## Troubleshooting

### Events don't show up in the dashboard

Check that `hostname` exactly matches a website you added in Simple Analytics, such as `app.example.com`. The dashboard only shows data for the websites in your account.

### "Simple Analytics will count … as a robot"

Simple Analytics leaves out traffic from user agents containing `bot`, `spider`, `crawl` or `agent`. Choose an app name or user agent without those words, such as `MyApp` instead of `MyApp Agent`.

### "Not sent to Simple Analytics"

An event name or path was invalid. Event names can only contain lowercase letters, digits and underscores, and paths must start with `/`.
