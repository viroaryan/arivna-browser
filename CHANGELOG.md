# Changelog

All notable changes to Arivna Browser are listed here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-24

First public release (versionCode 1). Android 8.0+ (minSdk 26), targets Android 16 (API 36). "Changed" and "Removed" compare with earlier internal test builds.

### Added
- **Arivna Shield**, a native ad and tracker blocking engine written in Rust and called through JNI. Supports uBlock Origin / Adblock Plus filter syntax: network rules, cosmetic (element hiding) rules, scriptlets, `$redirect`, `$removeparam`, `$badfilter`, `$important` and procedural cosmetic filters. Requests are blocked before they load; leftover ad boxes are hidden.
- 13 bundled filter lists: uBlock Origin filters, privacy, badware, quick fixes, unbreak; EasyList; EasyPrivacy; Peter Lowe's list; URLhaus malicious URL list; AdGuard Mobile; AdGuard Tracking Protection; and, in strict mode, uBlock cookie notices and EasyList Cookie. Plus Arivna built-in rules and your own rules.
- Daily filter list updates (on app open, or on return after 24 hours): lists download in parallel with HTTP conditional requests, the engine is rebuilt only if a list changed, and Settings shows "Last updated", "Update now" and how many lists changed.
- Binary cache of the compiled engine for fast start.
- Per-tab network request log (allowed / blocked / redirected, with the matching filter).
- Element picker ("Block element") that saves your own rules, and a My filters list.
- Per-site protection switch in the site info sheet; popup blocking; third-party cookie blocking; YouTube ad cleanup; "Tell websites not to sell or share my data" (Do Not Track + Global Privacy Control).
- New UI inspired by Firefox for Android: pill-shaped address bar at the top or bottom, full-screen search with suggestions, recent searches and clipboard link, site info sheet, main and page menus, home screen.
- Tab tray (grid or list), private tabs (separate cookies and storage, nothing saved), recently closed tabs with Undo, auto-close tabs after a day, week or month.
- Settings tree; bookmarks with folders; history with search and time-range delete.
- Downloads manager: own downloader in a foreground service, three parallel transfers, live notification with progress, speed and ETA, keeps working in the background.
- Reader view, find in page, desktop site, save page as PDF, add page to home screen, open in app, voice search, night mode for websites, text size and zoom on all websites, pull to refresh.
- Privacy controls: delete browsing data (tabs, history, cookies, cache, site permissions, downloads), delete on quit, site permissions (camera, microphone, location, notifications, autoplay) with per-site exceptions, open links in apps (always / ask / never), remote debugging switch (off by default).

### Changed
- MainActivity split into one file per screen.
- Targets API 36 (Android 16).
- Release builds are minified with R8 using narrower keep rules.
- Play bundles are signed with a dedicated upload key; local release APKs keep the debug key so they install over test builds.

### Removed
- 32-bit x86 native library (emulator-only); native engine ships for arm64-v8a, armeabi-v7a and x86_64.
- Unused bundled assets, including an unused Readability script.
- Icon and wallpaper choosers and dead debug code.

### Security
- `allowBackup` is off: history, cookies and downloads are not copied to cloud backup.
- Filters from third-party lists cannot run `trusted-*` scriptlets; only uBlock Origin's own lists, Arivna's built-in rules and your own rules are trusted.
- Remote WebView debugging is off by default.
- No analytics, telemetry or account.

### Performance
- Native libraries linked with 16 KB page-size alignment (`max-page-size=16384`), as Google Play requires.
- Engine load from the binary cache measured at 58 ms on a real phone (release build).
- Scrolling a news page on a real phone (release build): 0-2 % janky frames, median frame 8 ms.
- Shield time per first page load cut from 199 ms to 33 ms (BBC, about 75 requests); regex filters compile as ASCII byte regexes; per-page exception cache shared across WebView threads. Details and method: `PERFORMANCE.md`.
- Engine agrees with uBlock Origin's own network engine on 99.995 % of 83,631 test requests; about 5x faster to build and 9x faster to restore from cache (desktop). Method: `bench/REPORT.md`.
- Cheaper tab switching; at most four tabs keep a live WebView.

[1.0.0]: https://github.com/viroaryan/arivna-browser-releases/releases/tag/v1.0.0
