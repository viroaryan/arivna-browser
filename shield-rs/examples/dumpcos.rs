use arivna_shield::{Engine, ListInput};
fn main() {
    let dir = "../app/src/main/assets/filters";
    let mut texts = vec![];
    for e in std::fs::read_dir(dir).unwrap() { let p = e.unwrap().path(); texts.push((p.file_name().unwrap().to_string_lossy().to_string(), std::fs::read_to_string(&p).unwrap())); }
    let inputs: Vec<ListInput> = texts.iter().filter(|(n,_)| !n.contains("cookies")).map(|(n,t)| ListInput{ text: t, trusted: n.starts_with("ublock-") }).collect();
    let e = Engine::new(&inputs);
    println!("{}", e.cosmetic_json(&std::env::args().nth(1).unwrap()));
}
