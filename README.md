# GhostStat SDK for Android

Cookieless GhostStat analytics for Android apps (Android 5.0 / API 21+, Kotlin, no dependencies). Version 1.2.0 —
the same features and wire format as GhostStatSDK for iOS 1.2.0.

1.2.0 adds crash details for App Insights: why each crash, ANR or forced stop happened and the stack of the thread it
happened on (see App health below). 1.1.1 changed only the build files (AGP 9 out of the box, an AGP 8 file included).

## Install

1. Download `GhostStatSDK-Android-1.2.0.zip` from [Releases](https://github.com/GhostStat-me/ghoststat-android/releases) and
   unzip it next to your project (or `git clone https://github.com/GhostStat-me/ghoststat-android GhostStatSDK-Android`).
2. Add the `ghoststat` module to your app. In your app's `settings.gradle.kts`:

   ```kotlin
   include(":ghoststat")
   project(":ghoststat").projectDir = file("../GhostStatSDK-Android/ghoststat")
   ```

   and in your app module's `build.gradle.kts`:

   ```kotlin
   dependencies { implementation(project(":ghoststat")) }
   ```

   If your root `build.gradle.kts` does not declare the library plugin yet, add
   `id("com.android.library") version "<your AGP version>" apply false` next to `com.android.application`.
   (Or copy the `ghoststat` folder into your project instead of pointing to it.)

   **Which build file.** Your AGP version is the `com.android.application` version in your root
   `build.gradle.kts` (or `agp` in `gradle/libs.versions.toml`).

   - **AGP 9 and newer** (new Android Studio projects): nothing to do. `ghoststat/build.gradle.kts` uses AGP's
     built-in Kotlin.
   - **AGP 8.x**: copy `agp8/ghoststat-build.gradle.kts` over `ghoststat/build.gradle.kts`. That file uses the
     Kotlin Android plugin (`org.jetbrains.kotlin.android`), which a Kotlin app on AGP 8 already declares.

   On AGP 9 the AGP 8 file stops the sync with "Cannot add extension with name 'kotlin'": switch back to the
   default file.
3. In GhostStat: **Sites → New site → App**, platform Android, with your app's package name (applicationId).
   Copy its site key.

## Use

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GhostStat.configure(this, siteKey = "YOUR_SITE_KEY")
    }
}

// screens — e.g. in an Activity's onResume, or in Compose:
LaunchedEffect(Unit) { GhostStat.screen("Settings") }

GhostStat.event("signup_completed", mapOf("plan" to "pro"))
GhostStat.handle(intent?.data)      // utm_* from deep links / App Links (onCreate and onNewIntent)
GhostStat.isEnabled = false         // opt-out
```

From Java: `GhostStat.configure(this, "YOUR_SITE_KEY");`, `GhostStat.screen("Settings");`.

To check the setup, look at `GhostStat.lastStatus` after a minute: 200 = arriving; 422 = the key is not an app
site; 403 = the package name is not on the site's allow-list (Sites → the app → App & links).

What it sends, and what it does not:

- Screens, events and a foreground heartbeat (every 15 s, for "people in your app right now"), in batches of at
  most 50: every 15 s, at 50 items, and when the app goes to the background.
- App version and version code, package name, Android version, device model (e.g. `Google Pixel 8`), device class,
  screen size in dp and density.
- A random `launch_id` per process, kept in memory only: the server uses it as the session.
- **No** cookies, no advertising ID, no Android ID, no identifiers on disk. Visitors are counted on the server with
  a hash that changes every day, exactly like the GhostStat website tracker.
- The queue lives in memory. Unsent items can be lost when the process is killed — the same trade-off as `gs.js`.
- Everything runs on the SDK's own background thread; the main thread never waits for the network.
- Failed sends are retried with exponential backoff (1, 2, 4, 8 s…, at most 5 tries); a batch the server refuses
  (e.g. a package name that is not on the site's allow-list) is dropped.

## App health (for the App Insights plugin)

`GhostStat.configure(this, siteKey)` turns it on; `configure(this, siteKey, appHealth = false)` turns it off.

- **Installs, updates and opens.** The first time the app is in the foreground in a process, one `app_launch`
  item: an update (with the version it came from) when the remembered version differs, an install when there is
  none and the app was installed less than 7 days ago (`PackageInfo.firstInstallTime`), otherwise an open — so adding
  GhostStat does not show every existing user as new.
- **Launch time.** From the process start (Android 7+) to the first activity being visible; over 60 s is not
  measured (the process was started in the background).
- **Crash and ANR counts** (Android 11+). Android keeps a record of why the app's earlier processes ended
  (ApplicationExitInfo). The SDK counts crashes (Java and native) and ANRs per app version. Each process marks its
  version and build (`setProcessStateSummary`), so a crash is counted against the version that crashed. Nothing from
  before the SDK was added is counted.
- **Why it crashed** (1.2). One `app_crash` report per crash, ANR, native crash, and stop by Android while the app was
  on screen (low memory, excessive CPU, failing to start), sent on the next launch:
  - Java/Kotlin crash (any Android version): the SDK's uncaught-exception handler records the exception's class
    names and the stack (class.method, file, line) in a small file in the app's no-backup folder, then hands the
    exception on to Android as before. The next launch sends it and deletes the file.
  - Native crash (Android 12+): the signal and the crashing thread's frames from Android's tombstone (library, offset,
    function, build ID). ANR (Android 11+): the main thread's stack from Android's ANR trace.
  - **Never sent:** exception messages (they can contain what the user typed), an abort message, other threads,
    registers, memory, open files or the device log — the tombstone holds all of those; the SDK reads only the fields
    above. At most 10 reports per launch, 64 frames each.
  - Release builds minified with R8 have short class names (`a.b.c`): upload `mapping.txt` of each release in
    GhostStat (App Insights → Symbol files, or from CI) to see the real names, files and lines. Native code: upload
    the unstripped `.so`.
- It keeps two small values in the app's own SharedPreferences file `ghoststat`: `lastVersion` (e.g. `3.2.0`) and
  `lastExitCheck` (the time of the newest exit already handled), and after a Java crash the file
  `ghoststat-crash.txt` until the next launch. With App health off, nothing is written and no handler is installed.

## Google Play Data safety

Data collected: **App activity → App interactions**, and with App health also **App info and performance →
Crash logs** and **Diagnostics**. Not shared with third parties, not linked to the user's identity, processed
for analytics. Encrypted in transit (HTTPS). No advertising ID, so no Advertising ID declaration.

The site must be an **app site** in GhostStat with your package name on its allow-list. A site with platform
"iOS and Android" can take both apps: put the iOS bundle ID and the Android package name on the list.

## Tests

`ghoststat/src/test` — 38 JUnit tests for the queue, batching, backoff, opt-out, the wire format, installs /
updates / opens, the crash counts and the crash details (with a real Android 16 tombstone and ANR trace). Run them with `gradle :ghoststat:testDebugUnitTest` when this folder is opened
on its own (Gradle 9 with the default files; on AGP 8, Gradle 8.9 or newer with both `agp8` files copied in place).
Tested with AGP 9.4.1 / Gradle 9.8.0 and AGP 8.7.3 / Gradle 8.11.1, both with an app that includes the module as
described above.

## Licence

MIT — see [LICENSE](LICENSE). Copyright © 2026 Martijn Broeders.
[ghoststat.me](https://ghoststat.me) · [Support](https://ghoststat.me/support) · [Changes](CHANGELOG.md)
