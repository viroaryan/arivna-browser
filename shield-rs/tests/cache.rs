//! Binary engine cache: round trips must answer every query exactly like the
//! freshly built engine, and damaged or mismatched caches must be rejected
//! without panicking.

use arivna_shield::{ty, Engine, ListInput};
use std::time::Instant;

const SAMPLE: &str = "\
||ads.example.com^
||tracker.io^$third-party
@@||ads.example.com/allowed/
||cdn.site.com/ads/*$script,domain=news.com|~sports.news.com
/banner/*$image,third-party
/^https:\\/\\/x[0-9]+\\.evil\\.com\\//
||adzone.net^$domain=google.*
||malware.bad^$important
@@||malware.bad^
@@||trusted.org^$document
@@||lite.org^$generichide
||ga.com/analytics.js$script,redirect=google-analytics_analytics.js
||pix.com^$image,redirect-rule=1x1.gif:5
||pix.com^
$removeparam=/^utm_/
$removeparam=fbclid
||method.com/api$method=post
|https://start.com/ad
.gif|
||Case.com/Path$match-case
||to.com^$to=a.com|~b.a.com
||deny.com^$denyallow=ok.com
##.ad-box
##div[data-ad]
###sidebar-ad
news.com##.promo
news.com#@#.ad-box
news.com##.x:has-text(Sponsored)
news.com##.y:style(opacity: 0 !important)
news.com##:matches-path(/video) .preroll
news.com##+js(set, adsOn, false)
other.com##+js(aopr, adblock)
other.com,~sub.other.com##.banner
example.*##.entity-ad
#@#.globally-allowed
##.globally-allowed
lite.org##.lite-only
";

fn sample() -> Engine {
    Engine::new(&[ListInput { text: SAMPLE, trusted: true }])
}

fn verdict(e: &Engine, url: &str, src: &str, t: u32, m: u8) -> String {
    let r = e.check(url, src, t, m);
    format!("{}{}{}{}|{:?}|{:?}", r.blocked as u8, r.important as u8, r.exception as u8, r.page_allowed as u8, r.filter, r.redirect)
}

/// Every query the app makes, compared between two engines.
fn assert_same(a: &Engine, b: &Engine, urls: &[(String, String, u32)], pages: &[String]) {
    for (u, s, t) in urls {
        for m in [1u8, 2] {
            assert_eq!(verdict(a, u, s, *t, m), verdict(b, u, s, *t, m), "check {} from {} type {}", u, s, t);
        }
        assert_eq!(a.clean_url(u, s), b.clean_url(u, s), "clean_url {}", u);
    }
    for p in pages {
        assert_eq!(a.cosmetic_json(p), b.cosmetic_json(p), "cosmetic {}", p);
        let classes = "ad-box banner promo entity-ad globally-allowed adsbygoogle ad-banner";
        let ids = "sidebar-ad div-gpt-ad carbonads";
        assert_eq!(a.hidden_json(p, classes, ids), b.hidden_json(p, classes, ids), "hidden {}", p);
    }
}

fn sample_queries() -> (Vec<(String, String, u32)>, Vec<String>) {
    let srcs = ["https://news.com/a", "https://sports.news.com/", "https://www.google.co.uk/", "https://trusted.org/x", "https://lite.org/", "https://a.com/", ""];
    let urls = [
        "https://ads.example.com/x.js",
        "https://sub.ads.example.com/allowed/y.js",
        "https://tracker.io/t.js",
        "https://cdn.site.com/ads/a.js",
        "https://img.cdn.net/banner/1.png",
        "https://x123.evil.com/p",
        "https://adzone.net/a",
        "https://malware.bad/",
        "https://ga.com/analytics.js",
        "https://pix.com/p.gif",
        "https://a.com/p?id=1&utm_source=x&fbclid=y#top",
        "https://method.com/api",
        "https://start.com/ad1",
        "https://a.com/x.gif",
        "https://case.com/Path",
        "https://Case.com/Path",
        "https://to.com/x",
        "https://deny.com/x",
        "https://example.org/",
    ];
    let mut q = Vec::new();
    for u in urls {
        for s in srcs {
            for t in [ty::SCRIPT, ty::IMAGE, ty::XHR, ty::DOCUMENT, ty::SUBDOCUMENT] {
                q.push((u.to_string(), s.to_string(), t));
            }
        }
    }
    let pages = [
        "https://www.news.com/a",
        "https://news.com/video/1",
        "https://other.com/",
        "https://sub.other.com/",
        "https://example.co.uk/",
        "https://trusted.org/",
        "https://lite.org/",
        "https://foo.com/",
    ];
    (q, pages.iter().map(|s| s.to_string()).collect())
}

#[test]
fn round_trip_answers_identically() {
    let e = sample();
    let bytes = e.to_cache("key-1");
    let d = Engine::from_cache(&bytes, "key-1").expect("cache loads");
    assert!(d.stats.cached && !e.stats.cached);
    assert_eq!(d.stats.network, e.stats.network);
    assert_eq!(d.stats.cosmetic, e.stats.cosmetic);
    let (q, pages) = sample_queries();
    assert_same(&e, &d, &q, &pages);
    // Re-encoding the decoded engine yields a cache that still round-trips.
    let again = Engine::from_cache(&d.to_cache("key-1"), "key-1").expect("second round trip");
    assert_same(&e, &again, &q, &pages);
}

#[test]
fn empty_engine_round_trips() {
    let e = Engine::new(&[]);
    let d = Engine::from_cache(&e.to_cache(""), "").expect("empty cache loads");
    assert!(!d.check("https://a.com/x.js", "https://b.com/", ty::SCRIPT, 1).blocked);
    assert_eq!(e.cosmetic_json("https://a.com/"), d.cosmetic_json("https://a.com/"));
}

#[test]
fn rejects_wrong_key_and_damage() {
    let bytes = sample().to_cache("lists-v1");
    assert!(Engine::from_cache(&bytes, "lists-v2").is_none(), "key mismatch");
    assert!(Engine::from_cache(&[], "lists-v1").is_none());
    assert!(Engine::from_cache(b"not a cache at all", "lists-v1").is_none());
    for cut in [1, 8, 12, 20, bytes.len() / 2, bytes.len() - 1] {
        assert!(Engine::from_cache(&bytes[..cut], "lists-v1").is_none(), "truncated at {}", cut);
    }
    let mut flipped = bytes.clone();
    let mid = flipped.len() / 2;
    flipped[mid] ^= 0x40;
    assert!(Engine::from_cache(&flipped, "lists-v1").is_none(), "checksum mismatch");
    let mut version = bytes.clone();
    version[8] = version[8].wrapping_add(1);
    assert!(Engine::from_cache(&version, "lists-v1").is_none(), "format version mismatch");
}

/// Payload corruption that slips past the checksum must still never panic.
#[test]
fn decoder_survives_garbage() {
    let payload = sample().encode();
    let mut seed = 0x2545_F491_4F6C_DD1Du64;
    let mut rnd = || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for _ in 0..3000 {
        let mut p = payload.clone();
        for _ in 0..1 + rnd() % 4 {
            let i = (rnd() as usize) % p.len();
            p[i] = rnd() as u8;
        }
        if rnd() % 5 == 0 {
            let cut = (rnd() as usize) % p.len();
            p.truncate(cut);
        }
        if let Some(e) = Engine::decode(&p) {
            // A structurally valid engine must also be safe to query.
            let _ = e.check("https://ads.example.com/x.js", "https://news.com/", ty::SCRIPT, 1);
            let _ = e.cosmetic_json("https://www.news.com/a");
            let _ = e.hidden_json("https://news.com/", "ad-box promo", "sidebar-ad");
            let _ = e.clean_url("https://a.com/p?utm_source=1", "");
        }
    }
    for len in 0..64.min(payload.len()) {
        let _ = Engine::decode(&payload[..len]);
    }
}

#[test]
fn file_round_trip() {
    let path = std::env::temp_dir().join(format!("arivna-cache-test-{}.bin", std::process::id()));
    let e = sample();
    e.save_cache(&path, "file-key").unwrap();
    assert!(Engine::load_cache(&path, "other").is_none());
    let d = Engine::load_cache(&path, "file-key").expect("load from file");
    let _ = std::fs::remove_file(&path);
    let (q, pages) = sample_queries();
    assert_same(&e, &d, &q, &pages);
    assert!(Engine::load_cache(&path, "file-key").is_none(), "missing file");
}

#[test]
fn user_filter_list_is_honoured_after_round_trip() {
    let user = "news.com##div.sponsored > a.card\nexample.org###top-banner\n||evil-cdn.net^\n";
    let e = Engine::new(&[ListInput { text: SAMPLE, trusted: true }, ListInput { text: user, trusted: true }]);
    let d = Engine::from_cache(&e.to_cache("u"), "u").unwrap();
    let c = d.cosmetic("https://news.com/").unwrap();
    assert!(c.hide.contains(&"div.sponsored > a.card".to_string()));
    assert!(d.cosmetic("https://www.example.org/").unwrap().hide.contains(&"#top-banner".to_string()));
    assert!(d.check("https://evil-cdn.net/a.js", "https://a.com/", ty::SCRIPT, 1).blocked);
}

/// Full bundled lists: thousands of derived requests and pages must agree.
#[test]
fn bundled_lists_round_trip() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/src/main/assets/filters");
    let Ok(entries) = std::fs::read_dir(&dir) else {
        eprintln!("bundled lists not found, skipping");
        return;
    };
    let mut texts: Vec<(String, String)> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map_or(false, |x| x == "txt"))
        .map(|p| (p.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read_to_string(&p).unwrap()))
        .collect();
    texts.sort();
    let inputs: Vec<ListInput> = texts.iter().map(|(n, t)| ListInput { text: t, trusted: n.starts_with("ublock-") }).collect();
    let e = Engine::new(&inputs);
    let t = Instant::now();
    let bytes = e.to_cache("bundled");
    let enc = t.elapsed();
    let t = Instant::now();
    let d = Engine::from_cache(&bytes, "bundled").expect("bundled cache loads");
    eprintln!("bundled: build {} ms, encode {:?}, decode {:?}, {:.1} MB", e.stats.build_ms, enc, t.elapsed(), bytes.len() as f64 / 1048576.0);

    // Derive requests and pages from the filters themselves.
    let mut urls = Vec::new();
    let mut pages = Vec::new();
    for (_, text) in &texts {
        for line in text.lines().step_by(7) {
            if let Some(rest) = line.strip_prefix("||").or_else(|| line.strip_prefix("@@||")) {
                let host: String = rest.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '-').collect();
                let tail: String = rest[host.len()..].chars().take_while(|c| *c != '^' && *c != '$' && *c != '*').collect();
                if host.contains('.') {
                    urls.push((format!("https://{}{}", host, tail), "https://www.example.com/".to_string(), ty::SCRIPT));
                    urls.push((format!("https://sub.{}/ad/banner.gif?utm_source=x", host), format!("https://{}/", host), ty::IMAGE));
                }
            } else if let Some(i) = line.find("##") {
                let host = line[..i].split(',').next().unwrap_or("").trim_start_matches('~');
                if host.contains('.') && !host.contains('*') && !host.contains('/') {
                    pages.push(format!("https://{}/", host));
                }
            }
        }
    }
    urls.truncate(40_000);
    pages.truncate(4_000);
    assert!(urls.len() > 1000 && pages.len() > 500, "derived {} urls, {} pages", urls.len(), pages.len());
    assert_same(&e, &d, &urls, &pages);
    let (q, p) = sample_queries();
    assert_same(&e, &d, &q, &p);
}
