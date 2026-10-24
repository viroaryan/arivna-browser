//! Desktop harness: loads the bundled filter lists, checks known requests and benchmarks.
//! Usage: shield-cli <filters-dir> [--dump-script <path>]

use arivna_shield::{ty, Engine, ListInput};
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = args.get(1).cloned().unwrap_or_else(|| "../app/src/main/assets/filters".into());
    if let Some(i) = args.iter().position(|a| a == "--dump-script") {
        std::fs::write(&args[i + 1], arivna_shield::content_script()).unwrap();
    }
    let mut names: Vec<_> = std::fs::read_dir(&dir).unwrap().filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.extension().map_or(false, |x| x == "txt")).collect();
    names.sort();
    let texts: Vec<(String, String)> = names.iter().map(|p| (p.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read_to_string(p).unwrap())).collect();
    let inputs: Vec<ListInput> = texts.iter().map(|(n, t)| ListInput { text: t, trusted: n.starts_with("ublock-") }).collect();

    let t = Instant::now();
    let engine = Engine::new(&inputs);
    println!("built in {:?}: {}", t.elapsed(), engine.stats_json());

    let cases: &[(&str, &str, u32, &str)] = &[
        ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://www.googletagmanager.com/gtag/js?id=G-XXXX", "https://news.example.org/a", ty::SCRIPT, "block"),
        ("https://www.google-analytics.com/analytics.js", "https://shop.example.com/", ty::SCRIPT, "block"),
        ("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://www.cnn.com/", ty::SCRIPT, "block"),
        ("https://connect.facebook.net/en_US/fbevents.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://www.youtube.com/api/stats/ads?ver=2", "https://www.youtube.com/watch?v=x", ty::XHR, "block"),
        ("https://www.youtube.com/pagead/viewthroughconversion/1", "https://www.youtube.com/", ty::IMAGE, "block"),
        ("https://imasdk.googleapis.com/js/sdkloader/ima3.js", "https://www.example.com/video", ty::SCRIPT, "any"),
        ("https://static.doubleclick.net/instream/ad_status.js", "https://www.youtube.com/", ty::SCRIPT, "block"),
        ("https://www.youtube.com/s/player/abc/base.js", "https://www.youtube.com/watch?v=x", ty::SCRIPT, "allow"),
        ("https://i.ytimg.com/vi/x/hqdefault.jpg", "https://www.youtube.com/", ty::IMAGE, "allow"),
        ("https://www.google.com/search?q=rust", "https://www.google.com/", ty::DOCUMENT, "allow"),
        ("https://code.jquery.com/jquery-3.7.1.min.js", "https://www.example.com/", ty::SCRIPT, "allow"),
        ("https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js", "https://www.example.com/", ty::SCRIPT, "allow"),
        ("https://www.wikipedia.org/portal/wikipedia.org/assets/img/Wikipedia-logo-v2.png", "https://www.wikipedia.org/", ty::IMAGE, "allow"),
        ("https://ads.pubmatic.com/AdServer/js/pwt/1/pwt.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://c.amazon-adsystem.com/aax2/apstag.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://sb.scorecardresearch.com/beacon.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://cdn.taboola.com/libtrc/example/loader.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://widgets.outbrain.com/outbrain.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://www.redditstatic.com/ads/pixel.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://static.hotjar.com/c/hotjar-1.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://bat.bing.com/bat.js", "https://www.example.com/", ty::SCRIPT, "block"),
        ("https://www.reddit.com/r/rust/", "https://www.reddit.com/", ty::DOCUMENT, "allow"),
        ("https://github.githubassets.com/assets/app.js", "https://github.com/", ty::SCRIPT, "allow"),
    ];
    let mut fails = 0;
    for (url, src, t, want) in cases {
        let r = engine.check(url, src, *t, 1);
        let got = if r.blocked { "block" } else { "allow" };
        let ok = *want == "any" || *want == got;
        if !ok {
            fails += 1;
        }
        println!(
            "{} {:5} {}  [{}]{}",
            if ok { "ok  " } else { "FAIL" },
            got,
            url,
            r.filter.clone().unwrap_or_default(),
            r.redirect.as_ref().map(|x| format!(" -> {}", x)).unwrap_or_default()
        );
    }
    println!("{} failures", fails);

    let clean = engine.clean_url("https://example.com/article?id=5&utm_source=x&fbclid=abc&gclid=1", "");
    println!("removeparam: {:?}", clean);

    for page in ["https://www.youtube.com/watch?v=abc", "https://m.youtube.com/", "https://www.google.com/search?q=x", "https://www.reddit.com/", "https://www.speedtest.net/"] {
        let c = engine.cosmetic(page).unwrap_or_default();
        println!(
            "cosmetic {}: hide={} style={} proc={} scriptlets={} {:?}",
            page,
            c.hide.len(),
            c.style.len(),
            c.procedural.len(),
            c.scriptlets.len(),
            c.scriptlets.iter().take(6).map(|s| s.join(",")).collect::<Vec<_>>()
        );
    }
    let h = engine.hidden("https://www.example.com/", "adsbygoogle ad-banner header content", "div-gpt-ad-123 carbonads main");
    println!("hidden for classes/ids: {:?}", h);

    // Benchmark
    let urls: Vec<(&str, &str, u32)> = cases.iter().map(|(u, s, t, _)| (*u, *s, *t)).collect();
    let n = 200_000;
    let t = Instant::now();
    let mut blocked = 0;
    for i in 0..n {
        let (u, s, ty) = urls[i % urls.len()];
        if engine.check(u, s, ty, 1).blocked {
            blocked += 1;
        }
    }
    let el = t.elapsed();
    println!("bench: {} checks in {:?} = {:.2} µs/check ({} blocked)", n, el, el.as_secs_f64() * 1e6 / n as f64, blocked);
    let t = Instant::now();
    for i in 0..n {
        let (u, s, ty) = urls[i % urls.len()];
        std::hint::black_box(arivna_shield::request::Request::new(u, s, ty, 1));
    }
    println!("request parse only: {:.2} µs", t.elapsed().as_secs_f64() * 1e6 / n as f64);
    let t = Instant::now();
    for _ in 0..200 {
        let _ = engine.cosmetic_json("https://www.youtube.com/watch?v=abc");
    }
    println!("cosmetic: {:.1} µs/page", t.elapsed().as_secs_f64() * 1e6 / 200.0);

    // Binary cache: size, save/load time, and identical answers after a round trip.
    let path = std::env::temp_dir().join("arivna-shield-cli.bin");
    let t = Instant::now();
    engine.save_cache(&path, "cli").unwrap();
    let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
    println!("cache: saved {:.1} MB in {:?}", size as f64 / 1048576.0, t.elapsed());
    let t = Instant::now();
    let loaded = Engine::load_cache(&path, "cli").expect("cache must load");
    println!("cache: loaded in {:?} ({}ms reported)", t.elapsed(), loaded.stats.load_ms);
    let _ = std::fs::remove_file(&path);
    for (url, src, t, _) in cases {
        let (a, b) = (engine.check(url, src, *t, 1), loaded.check(url, src, *t, 1));
        if (a.blocked, a.filter, a.redirect) != (b.blocked, b.filter, b.redirect) {
            fails += 1;
            println!("FAIL cache mismatch for {}", url);
        }
    }
    for page in ["https://www.youtube.com/watch?v=abc", "https://www.reddit.com/", "https://www.speedtest.net/"] {
        if engine.cosmetic_json(page) != loaded.cosmetic_json(page) {
            fails += 1;
            println!("FAIL cache cosmetic mismatch for {}", page);
        }
    }
    if fails > 0 {
        std::process::exit(1);
    }
}
