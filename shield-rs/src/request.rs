//! A network request as seen by the engine.

use crate::hash::{hash_entity, suffix_hashes, token_hash};
use crate::psl::psl;
use crate::url;

pub mod ty {
    pub const DOCUMENT: u32 = 1 << 0;
    pub const SUBDOCUMENT: u32 = 1 << 1;
    pub const STYLESHEET: u32 = 1 << 2;
    pub const SCRIPT: u32 = 1 << 3;
    pub const IMAGE: u32 = 1 << 4;
    pub const FONT: u32 = 1 << 5;
    pub const OBJECT: u32 = 1 << 6;
    pub const XHR: u32 = 1 << 7;
    pub const PING: u32 = 1 << 8;
    pub const MEDIA: u32 = 1 << 9;
    pub const WEBSOCKET: u32 = 1 << 10;
    pub const OTHER: u32 = 1 << 11;
    pub const POPUP: u32 = 1 << 12;
    /// Everything a filter without type options applies to.
    pub const DEFAULT: u32 = SUBDOCUMENT | STYLESHEET | SCRIPT | IMAGE | FONT | OBJECT | XHR | PING | MEDIA | WEBSOCKET | OTHER;
    pub const ALL: u32 = DEFAULT | DOCUMENT | POPUP;

    pub fn from_name(name: &str) -> Option<u32> {
        Some(match name {
            "document" | "doc" | "main_frame" => DOCUMENT,
            "subdocument" | "frame" | "sub_frame" => SUBDOCUMENT,
            "stylesheet" | "css" => STYLESHEET,
            "script" => SCRIPT,
            "image" | "imageset" => IMAGE,
            "font" => FONT,
            "object" | "object-subrequest" => OBJECT,
            "xmlhttprequest" | "xhr" | "fetch" => XHR,
            "ping" | "beacon" | "csp_report" => PING,
            "media" => MEDIA,
            "websocket" => WEBSOCKET,
            "other" => OTHER,
            "popup" => POPUP,
            _ => return None,
        })
    }
}

pub mod method {
    pub const ANY: u8 = 0;
    pub fn from_name(m: &str) -> u8 {
        match m.to_ascii_lowercase().as_str() {
            "get" => 1,
            "post" => 2,
            "head" => 4,
            "put" => 8,
            "delete" => 16,
            "patch" => 32,
            "options" => 64,
            "connect" => 128,
            _ => 0,
        }
    }
}

pub struct Request {
    pub url: String,
    pub url_raw: String,
    pub host_start: usize,
    pub host_end: usize,
    pub domain: String,
    pub source_host: String,
    pub source_domain: String,
    /// Hashes of the page host and its parents, plus `entity.*` forms.
    pub source_hashes: Vec<u64>,
    /// Hashes of the request host and its parents, plus `entity.*` forms.
    pub host_hashes: Vec<u64>,
    pub ty: u32,
    pub method: u8,
    pub third_party: bool,
    pub tokens: Vec<u32>,
}

fn add_entity_hashes(host: &str, out: &mut Vec<u64>) {
    let base = psl().without_suffix(host);
    if base.is_empty() {
        return;
    }
    let mut start = 0;
    loop {
        out.push(hash_entity(&base[start..]));
        match base[start..].find('.') {
            Some(i) => start += i + 1,
            None => break,
        }
    }
}

pub fn tokenize(url: &[u8], out: &mut Vec<u32>) {
    let mut i = 0;
    let n = url.len();
    while i < n && out.len() < 256 {
        while i < n && !url::is_token_char(url[i]) {
            i += 1;
        }
        let s = i;
        while i < n && url::is_token_char(url[i]) {
            i += 1;
        }
        if i > s {
            let t = token_hash(&url[s..i]);
            if !out.contains(&t) {
                out.push(t);
            }
        }
    }
}

type SourceInfo = (String, String, String, Vec<u64>);

/// Everything derived from the page URL. All requests of a page share it, so the last one is cached per thread.
fn source_info(source_url: &str) -> (String, String, Vec<u64>) {
    thread_local! {
        static LAST: std::cell::RefCell<Option<SourceInfo>> = const { std::cell::RefCell::new(None) };
    }
    LAST.with(|c| {
        if let Some((raw, host, domain, hashes)) = &*c.borrow() {
            if raw == source_url {
                return (host.clone(), domain.clone(), hashes.clone());
            }
        }
        let lower = source_url.to_ascii_lowercase();
        let host = url::host(&lower).to_string();
        let domain = psl().domain(&host).to_string();
        let mut hashes = Vec::with_capacity(8);
        suffix_hashes(&host, &mut hashes);
        add_entity_hashes(&host, &mut hashes);
        *c.borrow_mut() = Some((source_url.to_string(), host.clone(), domain.clone(), hashes.clone()));
        (host, domain, hashes)
    })
}

impl Request {
    pub fn new(url_raw: &str, source_url: &str, ty: u32, method: u8) -> Option<Request> {
        let url = url_raw.to_ascii_lowercase();
        let parts = url::parse(&url)?;
        let host = &url[parts.host_start..parts.host_end];
        let domain = psl().domain(host).to_string();
        let (source_host, source_domain, source_hashes) = source_info(source_url);
        let third_party = !source_host.is_empty() && source_domain != domain;

        let mut host_hashes = Vec::with_capacity(8);
        suffix_hashes(host, &mut host_hashes);
        add_entity_hashes(host, &mut host_hashes);

        let mut tokens = Vec::with_capacity(32);
        tokenize(url.as_bytes(), &mut tokens);
        Some(Request {
            host_start: parts.host_start,
            host_end: parts.host_end,
            domain,
            source_host,
            source_domain,
            source_hashes,
            host_hashes,
            ty,
            method,
            third_party,
            tokens,
            url_raw: url_raw.to_string(),
            url,
        })
    }

    #[inline]
    pub fn host(&self) -> &str {
        &self.url[self.host_start..self.host_end]
    }

    /// Strict party check (`strict1p`/`strict3p`) compares full hostnames.
    pub fn strict_third_party(&self) -> bool {
        !self.source_host.is_empty() && self.source_host != self.host()
    }
}
