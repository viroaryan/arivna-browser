# Third-party notices

Arivna Browser includes or uses the components below. Each keeps its own licence. Licences were read from the file headers in this repository, `shield-rs/Cargo.lock` and the crates' own `Cargo.toml`, and the projects' licence pages (checked 24 Oct 2026). Where something is unclear it says so.

## Filter lists (bundled in `app/src/main/assets/filters`, updated daily from the URLs in `Shield.kt`)

These lists are data, not Arivna code. They are shipped unmodified; the app downloads newer copies from the same sources.

| File | List | Authors / source | Licence |
|---|---|---|---|
| `ublock-filters.txt`, `ublock-privacy.txt`, `ublock-badware.txt`, `ublock-quick-fixes.txt`, `ublock-unbreak.txt`, `ublock-cookies.txt` | uBlock filters (filters, Privacy, Badware risks, Quick fixes, Unbreak, Cookie Notices) | uBlock Origin volunteers, https://github.com/uBlockOrigin/uAssets | GPL-3.0 ([LICENSE](https://github.com/uBlockOrigin/uAssets/blob/master/LICENSE)) |
| `easylist.txt`, `easyprivacy.txt` | EasyList, EasyPrivacy | The EasyList authors (https://easylist.to/) | Dual: GPL-3.0-or-later or CC BY-SA 3.0-or-later ([licence](https://easylist.to/pages/licence.html)). Attribution: "The EasyList authors (https://easylist.to/)" |
| `easylist-cookies.txt` | EasyList Cookie List | Fanboy / EasyList, https://secure.fanboy.co.nz/ | The file header says CC BY 3.0 (http://creativecommons.org/licenses/by/3.0/) |
| `adguard-mobile.txt`, `adguard-tracking.txt` | AdGuard Mobile Ads filter, AdGuard URL Tracking filter | AdGuard, https://github.com/AdguardTeam/AdguardFilters | GPL-3.0 ([LICENSE](https://github.com/AdguardTeam/AdguardFilters/blob/master/LICENSE)) |
| `urlhaus.txt` | Online Malicious URL Blocklist | malware-filter, https://gitlab.com/malware-filter/urlhaus-filter ; data from URLhaus (abuse.ch) | Filters: CC0-1.0 and MIT; URLhaus data: CC0. Not endorsed by abuse.ch |
| `peterlowe.txt` | Peter Lowe's Ad and tracking server list | Peter Lowe, https://pgl.yoyo.org/adservers/ | **No standard licence stated.** The site invites combining and republishing the list and links a policy page (https://pgl.yoyo.org/adservers/policy.php) that we could not read automatically. Check that page, or ask pgl@yoyo.org, before commercial distribution |
| `arivna-builtin.txt` | Arivna built-in rules | Arivna | Same as Arivna's own code |

The bundled GPL-3.0 lists are distributed as separate data files under their own licence; their full source is the file itself and the upstream repository linked above.

## Arivna Shield (Rust, `shield-rs/`)

Arivna Browser, including the `shield-rs` engine, is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). The components below keep their own licences.

Crates compiled into `libarivna_shield.so`:

| Crate | Version | Licence |
|---|---|---|
| regex | 1.13.1 | MIT OR Apache-2.0 |
| regex-automata | 0.4.18 | MIT OR Apache-2.0 |
| regex-syntax | 0.8.11 | MIT OR Apache-2.0 |
| aho-corasick | 1.1.5 | Unlicense OR MIT |
| memchr | 2.8.3 | Unlicense OR MIT |
| jni-sys | 0.3.1, 0.4.1 | MIT OR Apache-2.0 |

Build-time only (procedural macros used by `jni-sys`, not shipped in the binary): jni-sys-macros 0.4.1, syn 2.0.119, quote 1.0.47, proc-macro2 1.0.107 (all MIT OR Apache-2.0), unicode-ident 1.0.27 ((MIT OR Apache-2.0) AND Unicode-3.0).

Data compiled into the engine:

| Data | Source | Licence |
|---|---|---|
| `shield-rs/data/public_suffix_list.dat` | Public Suffix List, https://publicsuffix.org/ | MPL-2.0 (file header) |

Scriptlets, redirect resources and procedural cosmetic code in `shield-rs/src` are Arivna's own implementations of the uBlock Origin filter API (per `shield-rs/README.md`), not copies of uBlock Origin code.

## Android app

| Component | Licence |
|---|---|
| Kotlin standard library | Apache-2.0 |
| Jetpack Compose (ui, foundation, material3, BOM 2024.09.00) | Apache-2.0 |
| AndroidX activity-compose, core-ktx, webkit, swiperefreshlayout | Apache-2.0 |
| Lucide icons (path data redrawn in `Icons.kt`), https://lucide.dev/ | ISC (Feather-derived icons: MIT) |
| JUnit 4.13.2 (tests only, not shipped) | EPL-1.0 |

Android System WebView is not bundled: Arivna uses the copy installed on the device (Chromium, under its own BSD-style and other licences, updated by Google Play / the device maker).

Lucide: Copyright (c) Lucide Icons and Contributors, ISC licence. Icons Lucide derived from Feather are Copyright (c) 2013-present Cole Bemis, MIT licence. Both licences require the copyright and permission notice to accompany copies; full text: https://github.com/lucide-icons/lucide/blob/main/LICENSE

## Development tools (not shipped)

| Component | Used in | Licence |
|---|---|---|
| `@gorhill/ubo-core` 0.1.x (uBlock Origin's network engine) | `bench/` comparison only | GPL-3.0-or-later |
| Gradle wrapper | build | Apache-2.0 |
