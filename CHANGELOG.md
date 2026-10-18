# Changelog

## 1.2.0 — 2026-10-02
- Crash details for App Insights: one `app_crash` report per crash, ANR, native crash and stop by Android while on
  screen, sent on the next launch (exception class names and stack, tombstone frames, the ANR main-thread stack).
  Never exception messages, other threads, registers, memory or the device log.

## 1.1.1
- Build files only: AGP 9 out of the box, an AGP 8 build file in `agp8/`.

## 1.1.0
- First release: screens, events, deep-link UTMs, foreground heartbeat, batching with retry, and App health
  (installs, updates and opens, launch time, crash and ANR counts). Same wire format as the iOS SDK.
