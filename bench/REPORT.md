# Arivna Shield vs uBlock Origin core: benchmark report

Same 12 lists (242k lines), same Public Suffix List file, same `!#if` environment (Arivna's
preprocessed "active" lines are fed to both). uBO side is `@gorhill/ubo-core` 0.1.30
(uBO's real static network filtering engine, JavaScript, Node 24). Desktop, single thread.

Reproduce: `node make-corpus.mjs`, then `cargo run --release --example bench_corpus -- ../app/src/main/assets/filters ../bench/lists.json ../bench/corpus.tsv ../bench/arivna-out.tsv ../bench/active`, then `node run-ubo.mjs && node diff.mjs`.

## Correctness (83,631 requests, 9 request types, 8 page origins)

| | |
|---|---|
| Both block | 76,492 |
| Both allow | 7,135 |
| Only uBO blocks | 0 |
| Only Arivna blocks | 4 |
| Agreement | 99.995 % |

The 4: three `$all,reason=malicious` rules (uBO-core 0.1.30 predates `reason=`, so it drops the
rule) and one urlhaus rule that uBO-core blocks alone but misses once all lists are loaded together
(unexplained, uBO-core quirk).

Redirect resources (76,496 blocked requests): 81 same resource, 0 only-Arivna, 0 only-uBO,
3 differ (XHR to `pagead2.googlesyndication.com/.../adsbygoogle.js`: uBO picks `noop.js`, Arivna
picks the priority-5 `googlesyndication_adsbygoogle.js`; not resolved).

`$removeparam` (3,291 URLs, 3,249 actually cleaned): 3,291 identical outputs.

## Bugs found by this comparison (fixed)

1. `$badfilter` matched on literal option text, so `$3p,badfilter` did not disable
   `$third-party`, option order/alias (`xhr`/`xmlhttprequest`, ...) and pattern case also
   defeated it. 10 of the first 23 mismatches. Now compared on a canonical key. Test added.
2. `clean_url` recompiled `$removeparam=/regex/` on every call: 287 µs/URL. Cached: 26.7 µs.

## Speed (best of 5, JIT-warm for uBO)

| | Arivna | uBO core |
|---|---|---|
| Build from lists | 234 ms | 1,151 ms |
| Check per request | 3.2 µs | 2.9 µs |
| removeparam per URL | 26.7 µs | 27.4 µs |
| Live heap after build | 33 MB | 37 MB (RSS +167 MB while parsing) |
| Load from cache (bytes in RAM) | 15 ms | 142 ms |
| Cache size | 10.7 MB | 14.3 MB |

uBO is about 10 % faster per request check. Arivna is about 5x faster to build and about 9x
faster to restore.

## Not covered

* Cosmetic filters, scriptlets, procedural cosmetics, `$csp`, `$replace`, HTML filtering:
  `ubo-core` has no cosmetic engine and Arivna deliberately drops `$csp/$replace/$header`.
  Needs the real uBO extension in a browser to compare.
* Real-network page load times against uBO.
