//! Perf numbers: build, memory (live heap), best-of-5 check loop, cache size, cache load.
use arivna_shield::{ty, Engine, ListInput};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};
use std::time::Instant;
struct Counting;
static LIVE: AtomicUsize = AtomicUsize::new(0);
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 { LIVE.fetch_add(l.size(), Relaxed); System.alloc(l) }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) { LIVE.fetch_sub(l.size(), Relaxed); System.dealloc(p, l) }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 { LIVE.fetch_add(n, Relaxed); LIVE.fetch_sub(l.size(), Relaxed); System.realloc(p, l, n) }
}
#[global_allocator]
static A: Counting = Counting;
fn tb(t: &str) -> u32 { match t { "script" => ty::SCRIPT, "image" => ty::IMAGE, "stylesheet" => ty::STYLESHEET, "xmlhttprequest" => ty::XHR, "sub_frame" => ty::SUBDOCUMENT, "font" => ty::FONT, "media" => ty::MEDIA, "ping" => ty::PING, _ => ty::OTHER } }
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let dir = &a[1];
    let names: Vec<String> = std::fs::read_to_string("../bench/lists.json").unwrap().trim().trim_matches(|c| c == '[' || c == ']').split(',').map(|s| s.trim().trim_matches('"').to_string()).collect();
    let texts: Vec<String> = names.iter().map(|n| std::fs::read_to_string(format!("{}/{}.txt", dir, n)).unwrap()).collect();
    let inputs: Vec<ListInput> = texts.iter().zip(&names).map(|(t, n)| ListInput { text: t, trusted: n.starts_with("ublock-") || n == "arivna-builtin" }).collect();
    let before = LIVE.load(Relaxed);
    let mut best_build = u128::MAX; 
    for _ in 0..3 { let t = Instant::now(); let e = Engine::new(&inputs); best_build = best_build.min(t.elapsed().as_millis()); drop(e); }
    let before = before.max(LIVE.load(Relaxed));
    let e = Engine::new(&inputs);
    let live = LIVE.load(Relaxed) - before;
    println!("arivna build_ms(best of 3) {}  live_heap_mb {:.0}", best_build, live as f64 / 1048576.0);
    let corpus = std::fs::read_to_string("../bench/corpus.tsv").unwrap();
    let rows: Vec<(&str, &str, u32)> = corpus.lines().filter_map(|l| { let mut p = l.split('\t'); Some((p.next()?, p.next()?, tb(p.next()?))) }).collect();
    let mut best = f64::MAX;
    for _ in 0..5 { let t = Instant::now(); let mut n = 0; for (u, o, ty_) in &rows { if e.check(u, o, *ty_, 1).blocked { n += 1 } } std::hint::black_box(n); best = best.min(t.elapsed().as_secs_f64()); }
    println!("arivna check best-of-5: {:.2} us/check ({} reqs)", best * 1e6 / rows.len() as f64, rows.len());
    let bytes = e.to_cache("k");
    println!("arivna cache size_mb {:.1}", bytes.len() as f64 / 1048576.0);
    let mut bl = u128::MAX; for _ in 0..7 { let t = Instant::now(); let d = Engine::from_cache(&bytes, "k").unwrap(); bl = bl.min(t.elapsed().as_micros()); drop(d); }
    println!("arivna cache load (RAM bytes, best of 7) {:.1} ms", bl as f64 / 1000.0);
}
