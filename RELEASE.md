# Releasing Arivna on Google Play

## Identity that can never change
- **Package name (`applicationId`)**: `com.arivna.browser`. Play ties the app, its reviews, installs and updates to it. Decide now; after the first upload it is permanent.
- **Upload key**: a `.jks` file kept outside the repo (alias `arivna-upload`, RSA 4096, valid to 2056). Passwords are in `keystore.properties` in the project root (git-ignored).
  **Back both files up somewhere safe (password manager + a second drive).** With Play App Signing a lost upload key can be reset through Play support, but it takes days.
- Choose **Play App Signing** when you create the app in Play Console (default for new apps). Google keeps the real app-signing key; you only sign uploads with the upload key. This is what makes future updates safe even if your upload key leaks or is lost.

## Every release
1. Raise `versionCode` in `app/build.gradle.kts` by at least 1 (Play rejects equal or lower) and set `versionName` (1.0.0 -> 1.0.1).
2. If you changed Rust code: `bash shield-rs/build-android.sh` (it already links with 16 KB page alignment, which Play requires).
3. `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab` (signed with the upload key; fails loudly if `keystore.properties` is missing).
4. Upload the `.aab` to a Play Console track (Internal testing first), then promote.
- Local testing: `./gradlew assembleRelease` gives an APK signed with the debug key, so it installs over the app already on your phone. Run it as its own command: if `bundleRelease` is in the same gradle call, the APK is signed with the upload key too and `adb install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.

## Never break existing users
- Never change `applicationId` or the upload key's alias/password without resetting it in Play Console.
- SQLite (`AppDb` in `Storage.kt`): raise the DB version and add a migration in `onUpgrade`; never drop tables on upgrade.
- SharedPreferences keys in `Prefs` are stored data: rename a key only with a migration.
- `allowBackup` is off (history, cookies and downloads are not copied to Google Drive).
- The filter lists update themselves from the network, so list changes do not need a store update.

## Play Console checklist (not code)
- **Target API 36** is set (required for new apps and updates since 31 Aug 2026).
- **16 KB page size**: native libs are linked with `max-page-size=16384`.
- **Privacy policy URL** (mandatory for a browser: it handles URLs, history, camera/mic/location for web pages).
- **Data safety form**: Arivna sends no analytics. Say data is processed on device; filter-list downloads send no personal data; camera/mic/location are used only when a website asks and you allow.
- **Foreground service declaration**: `dataSync` (downloads). Play asks you to describe it and show a short video of a download notification.
- **Permissions**: CAMERA, RECORD_AUDIO, ACCESS_*_LOCATION, POST_NOTIFICATIONS are requested only when a page needs them.
- **Content rating questionnaire**, **target audience** (not for children), **app category** (Tools / Communication).
- **Store listing**: 512x512 icon (the avatar), 1024x500 feature graphic, 2+ phone screenshots, short and full description.
- Personal developer accounts created after Nov 2023 must run a **closed test with 12+ testers for 14 days** before production.
- **Your own phone**: the build installed now is signed with the debug key. Installing the Play version over it needs an uninstall first, so export your bookmarks first.
