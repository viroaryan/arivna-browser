//! The public engine: build from filter lists, then answer network and cosmetic queries.

use crate::codec::{self, Reader, Writer};
use crate::cosmetic::{CosmeticEngine, CosmeticResult};
use crate::hash::hash_str;
use crate::json::push_str_array;
use crate::network::{flag, Builder, NetResult, NetworkEngine};
use crate::preprocess::active_lines;
use crate::request::{ty, Request};
use crate::resources::{self, Resource};
use crate::url;
use std::path::Path;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::RwLock;
use std::time::Instant;

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Page-level exception flags of the most recent pages, shared by all threads. The WebView asks from many
/// IO threads at once; a per-thread cache made every thread redo the same page scan for every page.
static PAGE_CACHE: RwLock<[(u64, u64, u32); 8]> = RwLock::new([(0, 0, 0); 8]);
static PAGE_SLOT: AtomicUsize = AtomicUsize::new(0);

pub struct ListInput<'a> {
    pub text: &'a str,
    /// Trusted lists (uBlock Origin's own) may use `trusted-*` scriptlets.
    pub trusted: bool,
}

#[derive(Default, Clone, Debug)]
pub struct Stats {
    pub lines: usize,
    pub network: usize,
    pub cosmetic: usize,
    pub unsupported: usize,
    pub build_ms: u128,
    /// Set when the engine was loaded from a binary cache instead of built.
    pub cached: bool,
    /// Time spent reading and decoding the cache.
    pub load_ms: u128,
}

pub struct Engine {
    id: u64,
    pub net: NetworkEngine,
    pub cos: CosmeticEngine,
    pub stats: Stats,
}

fn is_http(url: &str) -> bool {
    let b = url.as_bytes();
    let starts = |p: &str| b.len() >= p.len() && b[..p.len()].eq_ignore_ascii_case(p.as_bytes());
    starts("http://") || starts("https://") || starts("ws://") || starts("wss://")
}

impl Engine {
    pub fn new(lists: &[ListInput]) -> Engine {
        let start = Instant::now();
        let mut nb = Builder::default();
        let mut cos = CosmeticEngine::default();
        let mut stats = Stats::default();
        for list in lists {
            for line in active_lines(list.text) {
                stats.lines += 1;
                if line.contains("$$") || line.contains("$@$") {
                    stats.unsupported += 1;
                    continue;
                }
                match cos.add(line, list.trusted) {
                    Some(true) => stats.cosmetic += 1,
                    Some(false) => stats.unsupported += 1,
                    None => {
                        if nb.add(line) {
                            stats.network += 1
                        } else {
                            stats.unsupported += 1
                        }
                    }
                }
            }
        }
        let net = nb.build();
        // Parse the public suffix list now, not on the first request of the first page.
        let _ = crate::psl::psl();
        stats.build_ms = start.elapsed().as_millis();
        Engine { id: NEXT_ID.fetch_add(1, Ordering::Relaxed), net, cos, stats }
    }

    /// Page-level exception flags for a document URL, cached across threads.
    pub fn page_flags(&self, page_url: &str) -> u32 {
        if page_url.is_empty() || !is_http(page_url) {
            return 0;
        }
        let h = hash_str(page_url);
        if let Ok(g) = PAGE_CACHE.read() {
            if let Some(e) = g.iter().find(|e| e.0 == self.id && e.1 == h) {
                return e.2;
            }
        }
        let f = match Request::new(page_url, page_url, ty::DOCUMENT, 0) {
            Some(r) => self.net.page_flags(&r),
            None => 0,
        };
        if let Ok(mut g) = PAGE_CACHE.write() {
            g[PAGE_SLOT.fetch_add(1, Ordering::Relaxed) % 8] = (self.id, h, f);
        }
        f
    }

    pub fn check(&self, url: &str, source: &str, request_type: u32, method: u8) -> NetResult {
        if !is_http(url) {
            return NetResult::default();
        }
        let Some(r) = Request::new(url, source, request_type, method) else { return NetResult::default() };
        let page = if request_type & (ty::DOCUMENT | ty::POPUP) != 0 && request_type & ty::POPUP == 0 { url } else { source };
        let flags = self.page_flags(page);
        self.net.check(&r, flags)
    }

    pub fn resource(&self, name: &str) -> Option<Resource> {
        resources::get(name)
    }

    pub fn clean_url(&self, url: &str, source: &str) -> Option<String> {
        if !is_http(url) || !url.contains('?') {
            return None;
        }
        let r = Request::new(url, source, ty::DOCUMENT, 0)?;
        if self.page_flags(url) & flag::DOC_ALLOW != 0 {
            return None;
        }
        self.net.clean_url(&r)
    }

    pub fn cosmetic(&self, page_url: &str) -> Option<CosmeticResult> {
        if !is_http(page_url) {
            return None;
        }
        let flags = self.page_flags(page_url);
        if flags & flag::DOC_ALLOW != 0 {
            return None;
        }
        let lower = page_url.to_ascii_lowercase();
        let parts = url::parse(&lower)?;
        let host = &lower[parts.host_start..parts.host_end];
        let tail = &page_url[parts.host_end..];
        let path = tail.split('#').next().unwrap_or("");
        let path = path.trim_start_matches(|c: char| c == ':' || c.is_ascii_digit());
        Some(self.cos.url_resources(
            host,
            path,
            flags & flag::ELEMHIDE != 0,
            flags & flag::GENERICHIDE != 0,
            flags & flag::SPECIFICHIDE != 0,
        ))
    }

    pub fn cosmetic_json(&self, page_url: &str) -> String {
        match self.cosmetic(page_url) {
            Some(r) => r.to_json(),
            None => "{\"off\":true}".to_string(),
        }
    }

    pub fn hidden(&self, page_url: &str, classes: &str, ids: &str) -> Vec<String> {
        if !is_http(page_url) {
            return Vec::new();
        }
        if self.page_flags(page_url) & (flag::DOC_ALLOW | flag::ELEMHIDE | flag::GENERICHIDE) != 0 {
            return Vec::new();
        }
        let host = url::host(&page_url.to_ascii_lowercase()).to_string();
        self.cos.hidden_selectors(&host, classes, ids)
    }

    pub fn hidden_json(&self, page_url: &str, classes: &str, ids: &str) -> String {
        let v = self.hidden(page_url, classes, ids);
        let mut o = String::new();
        push_str_array(&mut o, v.iter().map(|s| s.as_str()));
        o
    }

    pub fn stats_json(&self) -> String {
        let n = &self.net;
        let (generic, specific, scriptlets, always) = self.cos.counts();
        format!(
            "{{\"lines\":{},\"network\":{},\"cosmetic\":{},\"unsupported\":{},\"buildMs\":{},\"cached\":{},\"loadMs\":{},\"hostnames\":{},\"blocks\":{},\"exceptions\":{},\"important\":{},\"redirects\":{},\"removeparam\":{},\"genericHide\":{},\"genericAlways\":{},\"specificHide\":{},\"scriptlets\":{},\"regex\":{},\"untokenized\":{}}}",
            self.stats.lines,
            self.stats.network,
            self.stats.cosmetic,
            self.stats.unsupported,
            self.stats.build_ms,
            self.stats.cached,
            self.stats.load_ms,
            n.host_blocks.len() + n.host_blocks_3p.len(),
            n.blocks.len(),
            n.exceptions.len(),
            n.important.len(),
            n.redirects.len(),
            n.removeparams.len(),
            generic,
            always,
            specific,
            scriptlets,
            n.blocks.regex_count() + n.exceptions.regex_count(),
            n.blocks.untokenized() + n.exceptions.untokenized(),
        )
    }

    // ---------- Binary cache ----------

    /// Encodes the built engine (without the container header): stats, then
    /// the network and cosmetic parts, each length-prefixed so they can be
    /// decoded in parallel.
    pub fn encode(&self) -> Vec<u8> {
        let mut net = Vec::new();
        self.net.encode_parts(&mut net);
        let mut cos = Vec::new();
        self.cos.encode_parts(&mut cos);
        let total: usize = net.iter().chain(cos.iter()).map(|p| p.len() + 5).sum();
        let mut w = Writer::with_capacity(total + 64);
        let st = &self.stats;
        for v in [st.lines, st.network, st.cosmetic, st.unsupported] {
            w.len(v);
        }
        w.var(st.build_ms as u64);
        w.len(net.len());
        w.len(cos.len());
        for p in net.iter().chain(cos.iter()) {
            w.bytes(p);
        }
        w.buf
    }

    /// Decodes a payload written by [`Engine::encode`]; None on any malformed input.
    pub fn decode(payload: &[u8]) -> Option<Engine> {
        enum Part {
            Net(crate::network::NetPart),
            Cos(crate::cosmetic::CosPart),
        }
        let mut r = Reader::new(payload);
        let mut stats = Stats {
            lines: r.var()? as usize,
            network: r.var()? as usize,
            cosmetic: r.var()? as usize,
            unsupported: r.var()? as usize,
            build_ms: r.var()? as u128,
            ..Default::default()
        };
        let n_net = r.len(1)?;
        let n_cos = r.len(1)?;
        let mut parts = Vec::with_capacity(n_net + n_cos);
        for _ in 0..n_net + n_cos {
            parts.push(r.bytes()?);
        }
        if !r.at_end() {
            return None;
        }
        let decoded = codec::par_decode(&parts, |i, b| {
            if i < n_net {
                NetworkEngine::decode_part(b).map(Part::Net)
            } else {
                CosmeticEngine::decode_part(b).map(Part::Cos)
            }
        })?;
        let (mut net, mut cos) = (Vec::with_capacity(n_net), Vec::with_capacity(n_cos));
        for p in decoded {
            match p {
                Part::Net(p) => net.push(p),
                Part::Cos(p) => cos.push(p),
            }
        }
        let net = NetworkEngine::assemble(net)?;
        let cos = CosmeticEngine::assemble(cos)?;
        stats.cached = true;
        Some(Engine { id: NEXT_ID.fetch_add(1, Ordering::Relaxed), net, cos, stats })
    }

    /// The engine as a self-validating cache file tagged with `key`
    /// (the caller's fingerprint of the lists it was built from).
    pub fn to_cache(&self, key: &str) -> Vec<u8> {
        codec::wrap(key, &self.encode())
    }

    /// Restores an engine from [`Engine::to_cache`] output; None when the key,
    /// format version or checksum don't match.
    pub fn from_cache(data: &[u8], key: &str) -> Option<Engine> {
        let start = Instant::now();
        let mut e = Engine::decode(codec::unwrap(data, key)?)?;
        e.stats.load_ms = start.elapsed().as_millis();
        Some(e)
    }

    /// Writes the cache atomically (temp file + rename).
    pub fn save_cache(&self, path: &Path, key: &str) -> std::io::Result<()> {
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, self.to_cache(key))?;
        std::fs::rename(&tmp, path).or_else(|_| {
            let _ = std::fs::remove_file(path);
            std::fs::rename(&tmp, path)
        })
    }

    pub fn load_cache(path: &Path, key: &str) -> Option<Engine> {
        let start = Instant::now();
        let data = std::fs::read(path).ok()?;
        let mut e = Engine::from_cache(&data, key)?;
        // Warm the public suffix list so the first request doesn't pay for it.
        let _ = crate::psl::psl();
        e.stats.load_ms = start.elapsed().as_millis();
        Some(e)
    }
}
