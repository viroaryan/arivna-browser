//! How long do the regex filters take to compile? (They compile lazily, on first use.)
use arivna_shield::{pattern::Pattern, Engine, ListInput};
use std::time::Instant;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let dir = &a[1];
    let names: Vec<String> = std::fs::read_to_string(&a[2]).unwrap().trim().trim_matches(|c| c == '[' || c == ']').split(',').map(|s| s.trim().trim_matches('"').to_string()).collect();
    let texts: Vec<String> = names.iter().map(|n| std::fs::read_to_string(format!("{}/{}.txt", dir, n)).unwrap()).collect();
    let inputs: Vec<ListInput> = texts.iter().zip(&names).map(|(t, n)| ListInput { text: t, trusted: n.starts_with("ublock-") }).collect();
    let e = Engine::new(&inputs);
    let mut times: Vec<(f64, usize, String)> = Vec::new();
    let sets = [&e.net.blocks, &e.net.exceptions, &e.net.important, &e.net.redirects, &e.net.redirect_exceptions, &e.net.page];
    for set in sets {
        for f in &set.filters {
            if let Pattern::Regex { src, .. } = &f.pattern {
                let t = Instant::now();
                let _ = f.pattern.matches("https://example.com/a/b.js", 8, 19);
                times.push((t.elapsed().as_secs_f64() * 1e3, src.len(), src.chars().take(70).collect()));
            }
        }
    }
    times.sort_by(|x, y| y.0.partial_cmp(&x.0).unwrap());
    let total: f64 = times.iter().map(|t| t.0).sum();
    println!("regex filters: {}  total compile {:.1} ms  avg {:.3} ms", times.len(), total, total / times.len().max(1) as f64);
    for t in times.iter().take(6) { println!("  {:.2} ms  len {}  {}", t.0, t.1, t.2); }
}
