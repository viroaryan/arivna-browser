# Google Play listing: Arivna Browser (en-US)

Package: `com.arivna.browser` · Category: Communication (or Tools) · Free · Contains ads: No · Target audience: 13+ / adults (not designed for children)

## App name (max 30)
Arivna Browser: Fast & Secure

(alternatives: `Arivna Browser`, `Arivna: Private Browser`)

## Short description (max 80)
A private Android browser with its own Rust ad and tracker blocker. Open source.

## Full description (max 4000)
Arivna is a fast, private web browser for Android with its own built-in ad and tracker blocker, Arivna Shield. No account, no ads, no analytics.

BLOCK THE NOISE
Arivna Shield is a native engine written in Rust. It reads the same filter lists used by uBlock Origin and Adblock Plus and stops ads, trackers and known malicious addresses before the request is sent. Leftover empty ad boxes are hidden. 13 public filter lists are built in and update themselves once a day, only downloading the lists that changed.

SEE WHAT IS HAPPENING
• Network request log for every tab: what was allowed, blocked or redirected, and which filter decided.
• Tap any element on a page to hide it. Your rules are saved in My filters and you can edit them.
• Turn protection off for one site from the site info sheet if a page looks broken.
• Standard or Strict protection; popups and third-party cookies can be blocked; Do Not Track and Global Privacy Control can be sent.

A COMPLETE BROWSER
• Grid or list tabs, private tabs with their own storage, recently closed tabs with Undo, auto-close old tabs.
• Full-screen search with suggestions, recent searches, clipboard link and voice search.
• Address bar at the top or the bottom.
• Reader view, find in page, night mode for websites, text size and zoom, desktop site, save as PDF.
• Bookmarks with folders, searchable history, a download manager that keeps going in the background with progress, speed and time left.
• Add pages to your home screen and open links in their apps.

YOURS TO CONTROL
• Delete browsing data (tabs, history, cookies, cache, site permissions, downloads) now or every time you quit.
• Site permissions for camera, microphone, location and autoplay, with exceptions per site.

PRIVATE BY DESIGN
Arivna has no sign-in and no servers of its own that receive your browsing. History, bookmarks and settings stay on your phone. The app contains no analytics, crash reporting or advertising code. The full source code is public under GPL-3.0, and every download lists its SHA-256 checksum.

Arivna shows web pages with the Android System WebView (Chromium). It does not support browser extensions, sync accounts or built-in translation.

Made in India by Nirvana Team.
Website: https://arivna.in · Source: https://github.com/viroaryan/arivna-browser · Contact: contactus@arivna.com

## What's new (v1.0.1, max 500)
New Arivna logo. Stops WebView from sending anonymous diagnostics to Google. Small fixes and a faster tab switcher.

## Store settings
- Website: https://arivna.in
- Email: contactus@arivna.com
- Privacy policy URL: https://arivna.in/privacy
- App icon: store-listing/icon-512.png (512x512, 32-bit PNG)
- Feature graphic: store-listing/feature-graphic-1024x500.png
- Phone screenshots: store-listing/screenshots/*.png (1080x2160, 2:1)
- Data safety: data is processed on device; no data collected or shared by the developer; no account; the app contacts filter-list servers (no personal data) and the user's chosen search engine for suggestions (user-controlled). Data is encrypted in transit (HTTPS). Users can delete data in Settings.
- Permissions in the manifest: INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, ACCESS_COARSE/FINE_LOCATION (only when a website asks and you allow), POST_NOTIFICATIONS (download progress), FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC (downloads), WAKE_LOCK (downloads).
- Foreground service declaration: type dataSync, used only while files are downloading, with a visible notification.
