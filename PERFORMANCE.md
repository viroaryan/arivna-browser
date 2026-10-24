# Arivna: performance and scale notes

Measured on a real phone (vivo I2217, Android 16) unless stated. Debug and release builds differ a lot; the
numbers say which one they are from.

## Ad blocker (shield-rs)

| | before | after |
|---|---|---|
| Time in the shield for one page load, first page of a session (BBC, ~75 requests) | 199 ms (worst single request 69 ms) | 33 ms (worst 6.5 ms) |
| Same, later pages (NDTV, CNN) | 28-35 ms | 16-22 ms |
| Compiling all 421 regex filters (desktop) | 120 ms, three of them 15-18 ms each | 27 ms, slowest 0.8 ms |
| Check per request (desktop, 83,631 mixed requests) | 4.3 us | 2.8 us |
| Engine build / load from cache | 0.28 s / 86 ms | same |

* Regex filters now compile as ASCII byte regexes (URLs are ASCII). Filtering results are unchanged: the
  83,631-request comparison against uBlock Origin's own engine still agrees on every request it agreed on before.
* The per-page exception scan was cached per thread; the WebView asks from many threads, so every thread redid it
  for every page. The cache is now shared.
* The public suffix list is parsed when the engine is built, not on the first request.
* `bench/` holds the harness: `node make-corpus.mjs`, `bench_corpus` (Rust example), `run-ubo.mjs`, `diff.mjs`.

## Storage

History and the downloads list moved from JSON files to one SQLite database (WAL, indexed).
Debug-build stress test with 100,000 history pages (`--es bench history`, debug builds only):

| | time |
|---|---|
| open History (first 100) | 0.9 ms |
| scroll to page 500 | 0.7 ms |
| search a common word | 1.2 ms |
| address-bar suggestion (prefix / inside title) | 16 ms / 0.7 ms |
| search that matches nothing (full scan, background thread) | 146 ms |
| record 1,000 visits (queued, background thread) | 378 ms |
| database size at 100,000 pages | 22 MB |

History keeps one row per page (visit count and last time are updated), at most one year and 100,000 pages.
Favicons are decoded off the main thread and capped at 400 files / 8 MB.

## Downloads

Own downloader inside a foreground service (the system DownloadManager stopped when the app went to the
background). Three transfers at a time, the rest wait in line. One timer updates all progress. One summary
notification when there are more than three. MediaStore only numbers 32 copies of a file name; after that a
tagged name is used. 50 downloads at once: 49 saved (the 50th was cancelled by the next test link), scrolling
the list 4.7 % janky frames (idle home screen 3.8 %).

## Tabs and startup

* At most four tabs keep a WebView; the others save their page, history and scroll position and free the memory.
  Under memory pressure every tab except the one in front does.
* Open tabs (not private ones) are saved and come back after the system closes the app.
* Cold start (to first frame): debug 850-970 ms, **release 275-380 ms** (lower once Android has compiled the app after a few runs). Release APK 13.7 MB (debug 24 MB).
* Idle memory (home screen): release 214 MB PSS, debug about 220 MB.

## Not measured / not claimed

* Page load time is dominated by the network and the WebView. Whole-page timings varied by more than +-500 ms
  between identical runs, so only the shield's own overhead is reported.
* No comparison against other browsers on the same phone.
