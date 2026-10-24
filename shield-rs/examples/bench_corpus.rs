//! Benchmark harness: builds the engine from the lists named in lists.json, then answers a
//! TSV corpus (url \t origin \t type) and writes verdicts so other engines can be diffed against it.
//! Usage: bench_corpus <filters-dir> <lists.json> <corpus.tsv> <out.tsv>
use arivna_shield::{ty, Engine, ListInput};
use std::time::Instant;

fn type_bits(t: &str) -> u32 {
    match t {
        "script" => ty::SCRIPT,
        "image" => ty::IMAGE,
        "stylesheet" => ty::STYLESHEET,
        "xmlhttprequest" => ty::XHR,
        "sub_frame" => ty::SUBDOCUMENT,
        "main_frame" => ty::DOCUMENT,
        "font" => ty::FONT,
        "media" => ty::MEDIA,
        "ping" => ty::PING,
        "websocket" => ty::WEBSOCKET,
        _ => ty::OTHER,
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let names: Vec<String> = std::fs::read_to_string(&a[2]).unwrap().trim().trim_matches(|c| c == '[' || c == ']').split(',').map(|s| s.trim().trim_matches('"').to_string()).collect();
    let texts: Vec<(String, String)> = names.iter().map(|n| (n.clone(), std::fs::read_to_string(format!("{}/{}.txt", a[1], n)).unwrap())).collect();
    if let Some(dir) = a.get(5) {
        // Preprocessed (active) lines, so other engines see the same `!#if` environment.
        std::fs::create_dir_all(dir).unwrap();
        for (n, t) in &texts {
            std::fs::write(format!("{}/{}.txt", dir, n), arivna_shield::preprocess::active_lines(t).join("\n")).unwrap();
        }
    }
    let inputs: Vec<ListInput> = texts.iter().map(|(n, t)| ListInput { text: t, trusted: n.starts_with("ublock-") || n == "arivna-builtin" }).collect();
    let t = Instant::now();
    let e = Engine::new(&inputs);
    println!("arivna build_ms {}", t.elapsed().as_millis());
    let corpus = std::fs::read_to_string(&a[3]).unwrap();
    let rows: Vec<(&str, &str, &str)> = corpus.lines().filter_map(|l| { let mut p = l.split('\t'); Some((p.next()?, p.next()?, p.next()?)) }).collect();
    let mut out = String::with_capacity(rows.len() * 80);
    let t = Instant::now();
    let mut results = Vec::with_capacity(rows.len());
    for (u, o, ty_) in &rows {
        results.push(e.check(u, o, type_bits(ty_), 1));
    }
    let el = t.elapsed();
    println!("arivna check_total_ms {:.1} per_check_us {:.2} n {}", el.as_secs_f64() * 1e3, el.as_secs_f64() * 1e6 / rows.len() as f64, rows.len());
    for ((u, o, ty_), r) in rows.iter().zip(&results) {
        out.push_str(&format!("{}	{}	{}	{}	{}	{}
", u, o, ty_, if r.blocked { 1 } else { 0 }, r.filter.clone().unwrap_or_default(), r.redirect.clone().unwrap_or_default()));
    }
    std::fs::write(&a[4], out).unwrap();
}
