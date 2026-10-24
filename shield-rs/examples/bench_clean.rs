use arivna_shield::{Engine, ListInput};
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let dir = &a[1];
    let names: Vec<String> = std::fs::read_dir(dir).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().to_string()).collect();
    let texts: Vec<String> = names.iter().map(|n| std::fs::read_to_string(format!("{}/{}", dir, n)).unwrap()).collect();
    let inputs: Vec<ListInput> = texts.iter().zip(&names).map(|(t, n)| ListInput { text: t, trusted: n.starts_with("ublock-") }).collect();
    let e = Engine::new(&inputs);
    let urls = std::fs::read_to_string(&a[2]).unwrap();
    let mut out = String::new();
    let t = std::time::Instant::now();
    for u in urls.lines() {
        out.push_str(&format!("{}\t{}\n", u, e.clean_url(u, "").unwrap_or_else(|| u.to_string())));
    }
    println!("arivna clean_url {:.2} us/url", t.elapsed().as_secs_f64() * 1e6 / urls.lines().count() as f64);
    std::fs::write(&a[3], out).unwrap();
}
