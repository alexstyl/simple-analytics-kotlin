![Simple Analytics dashboard showing a Kotlin app's pageviews, events and visitor countries]()

_Demo idea: the Simple Analytics dashboard for an app, with screens listed as pages, funnel events and a country map._

# Simple Analytics for Kotlin

Send events and pageviews from your Kotlin app to [Simple Analytics](https://simpleanalytics.com), with no cookies and no user identifiers.

## Features

- **Events with details:** count what people do, such as `buy_clicked` with `source=toolbar`.
- **Pageviews for app screens:** see your app's screens in the dashboard's pages report, just like a website.
- **Country, language and screen reports:** send the computer's timezone, language and screen size so the dashboard can break visitors down.
- **Never slows down or crashes your app:** events are sent on a background thread, and failures are dropped silently.
- **Mistakes caught early:** invalid event names, page paths without a leading `/`, and user agents that Simple Analytics would count as bots throw an exception as soon as you use them.
- **No dependencies:** uses only the JDK's HTTP client.

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

First add your app as a website in the Simple Analytics dashboard, using a hostname such as `app.example.com`. Then send your first event by creating a `SimpleAnalytics` with that hostname and your app's user agent, and calling `track()`:

```kotlin
val analytics = SimpleAnalytics(hostname = "app.example.com", userAgent = "MyApp/1.0.0")

analytics.track("app_opened")
```

`app_opened` shows up in the dashboard's events report for `app.example.com`.

## Examples

### Track a button click

To tell apart which button people clicked, pass details as metadata to `track()`:

```kotlin
analytics.track("buy_clicked", mapOf("source" to "toolbar"))
```

Event names can only contain lowercase letters, digits and underscores.

### Track screen views

To count how often a screen is shown, call `pageview()` with a path for that screen whenever your app navigates to it:

```kotlin
analytics.pageview("/settings")
```

Each screen appears in the dashboard's pages report. Call it from your navigation code rather than from UI code that can run many times for the same screen.

### See visitors' countries and languages

Simple Analytics works out a visitor's country from their timezone. To get country, language and screen size reports, pass a `Visitor` with the computer's settings:

```kotlin
val screen = Toolkit.getDefaultToolkit().screenSize
val analytics = SimpleAnalytics(
    hostname = "app.example.com",
    userAgent = "MyApp/1.0.0 (macOS 15.1; aarch64)",
    visitor = Visitor(
        timezone = TimeZone.getDefault().id,
        language = Locale.getDefault().toLanguageTag(),
        screenWidth = screen.width,
        screenHeight = screen.height,
    ),
)
```

Without a `Visitor`, events and pageviews are still counted, but with no country, language or screen size.

### Turn off analytics in development builds

To keep test runs and debug builds out of your numbers, use `Analytics.None` there. It accepts the same calls and sends nothing:

```kotlin
val analytics: Analytics = if (isRelease) SimpleAnalytics("app.example.com", "MyApp/1.0.0") else Analytics.None
```

### See what would be sent

To inspect the JSON instead of sending it, for example while debugging, replace how the payload is posted:

```kotlin
val analytics = SimpleAnalytics("app.example.com", "MyApp/1.0.0", post = { json -> println(json) })
```

## Troubleshooting

### Events don't show up in the dashboard

Check that `hostname` exactly matches a website you added in Simple Analytics, such as `app.example.com`. The dashboard only shows data for the websites in your account.

### "The user agent would be counted as a robot"

Simple Analytics leaves out traffic from user agents containing `bot`, `spider`, `crawl` or `agent`. Choose a user agent without those words, such as `MyApp/1.0.0` instead of `MyApp Agent/1.0.0`.
