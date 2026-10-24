# Arivna Shield (Rust)

A from-scratch content-blocking engine for Arivna's Android WebView, compatible with
uBlock Origin, EasyList and AdGuard filter syntax. No third-party ad-block code — only
`regex` (for `/regex/` filters) and `jni-sys` (JNI type definitions).

## What it does

**Network filtering** (`src/network.rs`)
- Token index: every filter is filed under its rarest safe token; token-less site
  filters are bucketed by domain. Pure `||host^` rules use a hostname hash set.
- Options: types (`script`, `image`, `xhr`, `subdocument`, `media`, `popup`, `document`, …),
  `third-party`/`1p`/`strict1p`/`strict3p`, `domain=` (incl. `~negation` and `entity.*`),
  `to=`, `denyallow=`, `method=`, `match-case`, `important`, `badfilter`,
  `redirect=`/`redirect-rule=` (with priorities), `removeparam=`, `rewrite=abp-resource:*`,
  page-level `$document`, `$elemhide`, `$generichide`, `$specifichide`, `$genericblock`.
- Hosts-file syntax and plain hostnames.
- Filters with options the engine can't honour (`csp`, `header`, `replace`, …) are dropped,
  never applied without their restrictions.

**Cosmetic filtering** (`src/cosmetic.rs`, `src/js/content.js`)
- Generic hiding looked up lazily by the classes/ids present in the page.
- Site-specific hiding, `#@#` exceptions, `:style()`, AdGuard `#$#`, `:matches-path()`.
- Procedural selectors in JS: `:has-text`, `:upward`, `:xpath`, `:matches-css(-before/-after)`,
  `:min-text-length`, `:matches-attr`, `:matches-prop`, `:matches-media`, `:if/:if-not`,
  `:remove()`, `:remove-attr()`, `:remove-class()`, `:style()`.

**Scriptlets** (`src/js/scriptlets.js`) — clean-room implementations of the uBO API:
`set-constant`, `abort-on-property-read/write`, `abort-current-script`, `abort-on-stack-trace`,
`json-prune`, `json-prune-fetch-response`, `json-prune-xhr-response`,
`trusted-replace-fetch-response`, `trusted-replace-xhr-response`, `xml-prune`,
`no-fetch-if`, `no-xhr-if`, `no-window-open-if`, `no-setTimeout-if`, `no-setInterval-if`,
`nano-setTimeout/Interval-booster`, `addEventListener-defuser`, `remove-node-text`,
`replace-node-text`, `remove-attr`, `remove-class`, `set-attr`, `href-sanitizer`,
`trusted-click-element`, `set-cookie(-reload)`, `trusted-set-cookie`, `cookie-remover`,
`set-local/session-storage-item`, `noeval(-if)`, `nowebrtc`, `refresh-defuser`, … plus
aliases. `trusted-*` scriptlets only run from uBlock Origin's own lists.

**Redirect resources** (`src/resources.rs`): `noopjs`, `noopframe`, `1x1.gif`, transparent
PNGs (own encoder), silent MP3, VAST/VMAP stubs, and stubs for Google Analytics/GTM,
GPT, AdSense, IMA SDK, apstag, FingerprintJS2, FuckAdBlock and others.

**Also:** Public Suffix List lookups, `!#if` preprocessor (behaves as uBO on mobile Chromium).

## Numbers (desktop, bundled lists, 242k lines)

| | |
|---|---|
| Build | ~0.3 s |
| Network check | ~4 µs/request (incl. URL parsing) |
| Cosmetic config per page | ~50 µs |

## Build

```bash
cargo test --release
cargo run --release --bin shield-cli -- ../app/src/main/assets/filters
./build-android.sh   # builds libarivna_shield.so for all ABIs into app/src/main/jniLibs
```

## How the app uses it

- `Shield.intercept()` in `shouldInterceptRequest` blocks or redirects subresources.
- `Shield.cleanUrl()` strips tracking parameters on navigation.
- `Shield.documentScript()` is registered with `addDocumentStartJavaScript` in every frame; it
  asks the engine (through the `ShieldBridge` JavaScript interface, removed from `window`
  immediately) for scriptlets and selectors, then hides the bridge from the page.

## Limits

WebView doesn't report request types, so they're inferred from `Sec-Fetch-Dest` and file
extensions. Response headers can't be rewritten, so `$csp`, `$header` and HTML filtering
(`##^`) aren't available. YouTube blocking depends on the uBO lists staying current; the app
refreshes them daily.
