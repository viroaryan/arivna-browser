//! Network filters: parsing ABP/uBO syntax and matching requests through a
//! token index (each filter is filed under its rarest safe token).

use crate::hash::{hash_entity, hash_str, token_hash, FastMap, FastSet};
use crate::pattern::Pattern;
use crate::request::{method, ty, Request};
use crate::url::is_token_char;

pub mod flag {
    pub const EXCEPTION: u32 = 1;
    pub const IMPORTANT: u32 = 1 << 1;
    pub const THIRD: u32 = 1 << 2;
    pub const FIRST: u32 = 1 << 3;
    pub const MATCH_CASE: u32 = 1 << 4;
    pub const REDIRECT: u32 = 1 << 5;
    pub const REDIRECT_RULE: u32 = 1 << 6;
    pub const REMOVEPARAM: u32 = 1 << 7;
    pub const DOC_ALLOW: u32 = 1 << 8;
    pub const ELEMHIDE: u32 = 1 << 9;
    pub const GENERICHIDE: u32 = 1 << 10;
    pub const SPECIFICHIDE: u32 = 1 << 11;
    pub const GENERICBLOCK: u32 = 1 << 12;
    pub const STRICT1P: u32 = 1 << 13;
    pub const STRICT3P: u32 = 1 << 14;
    pub const PAGE: u32 = DOC_ALLOW | ELEMHIDE | GENERICHIDE | SPECIFICHIDE | GENERICBLOCK;
}

#[derive(Clone, Default)]
pub struct DomainSet {
    include: Box<[u64]>,
    exclude: Box<[u64]>,
}

impl DomainSet {
    /// Parses `a.com|~b.com|c.*`. Returns None when nothing usable remains
    /// (e.g. only regex entries) so the filter can be dropped safely.
    fn parse(list: &str, sep: char) -> Option<DomainSet> {
        let mut inc = Vec::new();
        let mut exc = Vec::new();
        let mut skipped_include = false;
        for raw in list.split(sep) {
            let d = raw.trim();
            if d.is_empty() {
                continue;
            }
            let (neg, name) = match d.strip_prefix('~') {
                Some(n) => (true, n),
                None => (false, d),
            };
            if name.starts_with('/') || name.contains('*') && !name.ends_with(".*") {
                if !neg {
                    skipped_include = true;
                }
                continue;
            }
            let name = name.to_ascii_lowercase();
            let h = match name.strip_suffix(".*") {
                Some(e) => hash_entity(e),
                None => hash_str(&name),
            };
            if neg { exc.push(h) } else { inc.push(h) }
        }
        if inc.is_empty() && (skipped_include || exc.is_empty()) {
            return None;
        }
        inc.sort_unstable();
        inc.dedup();
        exc.sort_unstable();
        exc.dedup();
        Some(DomainSet { include: inc.into(), exclude: exc.into() })
    }

    #[inline]
    pub fn matches(&self, hashes: &[u64]) -> bool {
        if !self.exclude.is_empty() && hashes.iter().any(|h| self.exclude.binary_search(h).is_ok()) {
            return false;
        }
        self.include.is_empty() || hashes.iter().any(|h| self.include.binary_search(h).is_ok())
    }

    #[inline]
    fn contains_any(&self, hashes: &[u64]) -> bool {
        hashes.iter().any(|h| self.include.binary_search(h).is_ok())
    }

    pub fn has_includes(&self) -> bool {
        !self.include.is_empty()
    }

    pub fn includes(&self) -> &[u64] {
        &self.include
    }
}

#[derive(Clone)]
pub struct NetFilter {
    pub mask: u32,
    pub flags: u32,
    pub method: u8,
    pub pattern: Pattern,
    pub domains: Option<Box<DomainSet>>,
    pub to: Option<Box<DomainSet>>,
    pub denyallow: Option<Box<DomainSet>>,
    pub redirect: Option<Box<str>>,
    pub priority: i32,
    pub param: Option<Box<str>>,
    pub raw: Box<str>,
}

impl NetFilter {
    #[inline]
    pub fn matches(&self, r: &Request) -> bool {
        if self.mask & r.ty == 0 {
            return false;
        }
        let f = self.flags;
        if f & (flag::THIRD | flag::FIRST | flag::STRICT1P | flag::STRICT3P) != 0 {
            if f & flag::THIRD != 0 && !r.third_party {
                return false;
            }
            if f & flag::FIRST != 0 && r.third_party {
                return false;
            }
            if f & flag::STRICT3P != 0 && !r.strict_third_party() {
                return false;
            }
            if f & flag::STRICT1P != 0 && r.strict_third_party() {
                return false;
            }
        }
        if self.method != method::ANY && self.method & r.method == 0 {
            return false;
        }
        if let Some(d) = &self.domains {
            if !d.matches(&r.source_hashes) {
                return false;
            }
        }
        if let Some(d) = &self.to {
            if !d.matches(&r.host_hashes) {
                return false;
            }
        }
        if let Some(d) = &self.denyallow {
            if d.contains_any(&r.host_hashes) {
                return false;
            }
        }
        let url = if f & flag::MATCH_CASE != 0 { &r.url_raw } else { &r.url };
        self.pattern.matches(url, r.host_start, r.host_end)
    }

    pub fn is_generic(&self) -> bool {
        self.domains.as_ref().is_none_or(|d| !d.has_includes())
    }
}

pub enum Parsed {
    Filter(NetFilter, Vec<u32>),
    /// `||host^`, optionally `$third-party` only.
    Host(String, bool),
    Badfilter(String),
    Skip,
}

const KNOWN_OPTIONS: &[&str] = &[
    "script", "image", "stylesheet", "css", "object", "object-subrequest", "xmlhttprequest", "xhr", "subdocument",
    "frame", "ping", "beacon", "media", "font", "websocket", "other", "document", "doc", "popup", "popunder", "all",
    "third-party", "3p", "first-party", "1p", "strict1p", "strict3p", "domain", "from", "to", "denyallow",
    "important", "badfilter", "match-case", "redirect", "redirect-rule", "rewrite", "removeparam", "queryprune",
    "csp", "header", "permissions", "method", "generichide", "ghide", "elemhide", "ehide", "specifichide", "shide",
    "genericblock", "inline-script", "inline-font", "empty", "mp4", "webrtc", "replace", "urlskip", "uritransform",
    "urltransform", "cname", "ipaddress", "reason", "app", "network", "extension", "content", "jsinject", "stealth",
    "cookie", "hls", "jsonprune", "xmlprune", "referrerpolicy", "imageset", "fetch",
];

fn option_name(opt: &str) -> &str {
    opt.trim_start_matches('~').split('=').next().unwrap_or("")
}

fn looks_like_options(s: &str) -> bool {
    let first = s.split(',').next().unwrap_or("");
    KNOWN_OPTIONS.contains(&option_name(first))
}

/// Splits `pattern$options`, choosing the first `$` that starts a valid option list.
fn split_options(l: &str) -> (&str, Option<&str>) {
    if l.starts_with('/') {
        if let Some(i) = l.rfind("/$") {
            if i > 0 && looks_like_options(&l[i + 2..]) {
                return (&l[..=i], Some(&l[i + 2..]));
            }
        }
        if l.len() > 2 && l.ends_with('/') {
            return (l, None);
        }
    }
    let b = l.as_bytes();
    for (i, &c) in b.iter().enumerate() {
        if c == b'$' && i + 1 < b.len() && looks_like_options(&l[i + 1..]) {
            return (&l[..i], Some(&l[i + 1..]));
        }
    }
    (l, None)
}

/// Splits on commas that are not inside `/regex/` option values.
fn split_option_list(s: &str) -> Vec<&str> {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let (mut start, mut i, mut in_re) = (0usize, 0usize, false);
    while i < b.len() {
        let c = b[i];
        if in_re {
            if c == b'\\' {
                i += 2;
                continue;
            }
            if c == b'/' && (i + 1 == b.len() || b[i + 1] == b',' || b[i + 1] == b'|') {
                in_re = false;
            }
            i += 1;
            continue;
        }
        if c == b'/' && i > 0 && matches!(b[i - 1], b'=' | b'|' | b'~') {
            in_re = true;
        } else if c == b',' {
            out.push(&s[start..i]);
            start = i + 1;
        }
        i += 1;
    }
    out.push(&s[start..]);
    out
}

fn valid_hostname(h: &str) -> bool {
    h.len() >= 3
        && h.contains('.')
        && !h.starts_with('.')
        && !h.ends_with('.')
        && !h.starts_with('-')
        && h.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'-' || c == b'_')
}

fn hosts_line(l: &str) -> Option<Parsed> {
    let mut it = l.split_whitespace();
    let first = it.next()?;
    if !matches!(first, "0.0.0.0" | "127.0.0.1" | "::1" | "::" | "0" | "::0" | "255.255.255.255") {
        return None;
    }
    let host = it.next().unwrap_or("").to_ascii_lowercase();
    if matches!(host.as_str(), "localhost" | "localhost.localdomain" | "local" | "broadcasthost" | "0.0.0.0" | "ip6-localhost" | "ip6-loopback")
        || !valid_hostname(&host)
    {
        return Some(Parsed::Skip);
    }
    Some(Parsed::Host(host, false))
}

/// Maps ABP `$rewrite=abp-resource:*` names to uBO resource names.
fn abp_resource(v: &str) -> Option<&'static str> {
    Some(match v.trim_start_matches("abp-resource:") {
        "blank-js" => "noopjs",
        "blank-css" => "noopcss",
        "blank-text" => "nooptext",
        "blank-html" => "noopframe",
        "blank-mp3" => "noopmp3-0.1s",
        "blank-mp4" => "noopmp4-1s",
        "1x1-transparent-gif" => "1x1.gif",
        "2x2-transparent-png" => "2x2.png",
        "3x2-transparent-png" => "3x2.png",
        "32x32-transparent-png" => "32x32.png",
        _ => return None,
    })
}

fn candidate_tokens(text: &str, left_bounded: bool, right_bounded: bool, out: &mut Vec<u32>) {
    let b = text.as_bytes();
    let n = b.len();
    let mut i = 0;
    while i < n {
        if !is_token_char(b[i]) {
            i += 1;
            continue;
        }
        let s = i;
        while i < n && is_token_char(b[i]) {
            i += 1;
        }
        let left_ok = if s == 0 { left_bounded } else { b[s - 1] != b'*' };
        let right_ok = if i == n { right_bounded } else { b[i] != b'*' };
        if left_ok && right_ok && i - s >= 2 {
            let t = token_hash(&b[s..i]);
            if !out.contains(&t) {
                out.push(t);
            }
        }
    }
}

/// Literal tokens a regex filter cannot match without. Conservative: only
/// top-level literal runs bounded by non-token characters, no alternation.
fn regex_tokens(src: &str, out: &mut Vec<u32>) {
    let b = src.as_bytes();
    let mut depth = 0i32;
    for &c in b {
        match c {
            b'|' if depth == 0 => return,
            b'(' | b'[' => depth += 1,
            b')' | b']' => depth -= 1,
            _ => {}
        }
    }
    let lit_sep = |c: u8| matches!(c, b'/' | b'-' | b'_' | b'=' | b'&' | b':' | b',');
    let esc_sep = |c: u8| matches!(c, b'.' | b'/' | b'?' | b'-' | b'=' | b'&');
    let mut i = 0;
    depth = 0;
    while i < b.len() {
        let c = b[i];
        match c {
            b'\\' => {
                i += 2;
                continue;
            }
            b'(' | b'[' | b'{' => {
                depth += 1;
                i += 1;
                continue;
            }
            b')' | b']' | b'}' => {
                depth -= 1;
                i += 1;
                continue;
            }
            _ => {}
        }
        if depth != 0 || !c.is_ascii_alphanumeric() {
            i += 1;
            continue;
        }
        let s0 = i;
        while i < b.len() && b[i].is_ascii_alphanumeric() {
            i += 1;
        }
        let before_ok = (s0 >= 2 && b[s0 - 2] == b'\\' && esc_sep(b[s0 - 1])) || (s0 >= 1 && lit_sep(b[s0 - 1]) && !(s0 >= 2 && b[s0 - 2] == b'\\'));
        let after_ok = (i + 1 < b.len() && b[i] == b'\\' && esc_sep(b[i + 1])) || (i < b.len() && lit_sep(b[i]));
        let quantified = matches!(b.get(i), Some(b'?') | Some(b'*') | Some(b'{') | Some(b'+'))
            || (b.get(i) == Some(&b'\\') && matches!(b.get(i + 2), Some(b'?') | Some(b'*') | Some(b'{') | Some(b'+')));
        let prev_quantified = s0 >= 1 && matches!(b[s0 - 1], b'?' | b'*' | b'+');
        if before_ok && after_ok && !quantified && !prev_quantified && i - s0 >= 3 {
            let t = token_hash(src[s0..i].to_ascii_lowercase().as_bytes());
            if !out.contains(&t) {
                out.push(t);
            }
        }
    }
}


/// Canonical spelling of an option name, so `$3p`, `$third-party` and `$~first-party`
/// style aliases compare equal when `$badfilter` looks for the filter it disables.
fn canonical_option(opt: &str) -> String {
    let opt = opt.trim();
    let (neg, rest) = match opt.strip_prefix('~') {
        Some(r) => ("~", r),
        None => ("", opt),
    };
    let (name, value) = match rest.find('=') {
        Some(i) => (&rest[..i], Some(&rest[i + 1..])),
        None => (rest, None),
    };
    let lower = name.to_ascii_lowercase();
    let name = match lower.as_str() {
        "third-party" => "3p",
        "first-party" => "1p",
        "css" => "stylesheet",
        "xhr" | "fetch" => "xmlhttprequest",
        "frame" | "sub_frame" => "subdocument",
        "doc" | "main_frame" => "document",
        "ehide" => "elemhide",
        "ghide" => "generichide",
        "shide" => "specifichide",
        "from" => "domain",
        "queryprune" => "removeparam",
        "object-subrequest" => "object",
        "beacon" | "csp_report" => "ping",
        other => other,
    };
    match value {
        Some(v) => format!("{}{}={}", neg, name, v),
        None => format!("{}{}", neg, name),
    }
}

/// Key under which `$badfilter` finds the filter it disables: the pattern (case-folded unless
/// `match-case`) plus the sorted, alias-normalised options, without `badfilter` itself.
fn canonical_key(exception: bool, pat: &str, opts: &[&str]) -> String {
    let mut o: Vec<String> = opts.iter().map(|x| canonical_option(x)).filter(|x| !x.is_empty() && x != "badfilter").collect();
    o.sort();
    o.dedup();
    let mut key = String::with_capacity(pat.len() + 16);
    if exception {
        key.push_str("@@");
    }
    if o.iter().any(|x| x == "match-case") {
        key.push_str(pat);
    } else {
        key.push_str(&pat.to_ascii_lowercase());
    }
    if !o.is_empty() {
        key.push('$');
        key.push_str(&o.join(","));
    }
    key
}

/// `canonical_key` for a filter given as raw text.
fn canonical_key_of_raw(raw: &str) -> String {
    let exception = raw.starts_with("@@");
    let body = if exception { &raw[2..] } else { raw };
    let (pat, opts) = split_options(body);
    let list = opts.map(split_option_list).unwrap_or_default();
    canonical_key(exception, pat, &list)
}


/// Compiled `$removeparam=/regex/` specs, shared across calls: compiling per navigation was ~10x slower than matching.
fn removeparam_regex(spec: &str, case_insensitive: bool) -> Option<std::sync::Arc<regex::Regex>> {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex, OnceLock};
    static CACHE: OnceLock<Mutex<HashMap<(String, bool), Option<Arc<regex::Regex>>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    let mut guard = cache.lock().unwrap_or_else(|e| e.into_inner());
    guard
        .entry((spec.to_string(), case_insensitive))
        .or_insert_with(|| regex::RegexBuilder::new(spec).case_insensitive(case_insensitive).build().ok().map(Arc::new))
        .clone()
}

pub fn parse(line: &str) -> Parsed {
    let l = line.trim();
    if l.is_empty() || l.starts_with('!') || l.starts_with('[') || l.starts_with('#') {
        return Parsed::Skip;
    }
    if l.contains(char::is_whitespace) {
        return hosts_line(l).unwrap_or(Parsed::Skip);
    }
    let raw = l;
    let exception = l.starts_with("@@");
    let body = if exception { &l[2..] } else { l };
    let (pat, opts) = split_options(body);

    let mut flags = if exception { flag::EXCEPTION } else { 0 };
    let (mut pos, mut neg) = (0u32, 0u32);
    let mut meth = 0u8;
    let mut meth_neg = 0u8;
    let mut domains = None;
    let mut to = None;
    let mut denyallow = None;
    let mut redirect: Option<String> = None;
    let mut priority = 0i32;
    let mut param = None;
    let mut badfilter = false;
    let mut opt_list: Vec<&str> = Vec::new();

    if let Some(o) = opts {
        opt_list = split_option_list(o);
        for opt in &opt_list {
            let opt = opt.trim();
            if opt.is_empty() {
                continue;
            }
            let negated = opt.starts_with('~');
            let o2 = opt.trim_start_matches('~');
            let (name, value) = match o2.find('=') {
                Some(i) => (&o2[..i], &o2[i + 1..]),
                None => (o2, ""),
            };
            match name {
                "third-party" | "3p" => flags |= if negated { flag::FIRST } else { flag::THIRD },
                "first-party" | "1p" => flags |= if negated { flag::THIRD } else { flag::FIRST },
                "strict1p" => flags |= flag::STRICT1P,
                "strict3p" => flags |= flag::STRICT3P,
                "important" => flags |= flag::IMPORTANT,
                "match-case" => flags |= flag::MATCH_CASE,
                "badfilter" => badfilter = true,
                "reason" => {}
                "domain" | "from" => match DomainSet::parse(value, '|') {
                    Some(d) => domains = Some(Box::new(d)),
                    None => return Parsed::Skip,
                },
                "to" => match DomainSet::parse(value, '|') {
                    Some(d) => to = Some(Box::new(d)),
                    None => return Parsed::Skip,
                },
                "denyallow" => match DomainSet::parse(value, '|') {
                    Some(d) => denyallow = Some(Box::new(d)),
                    None => return Parsed::Skip,
                },
                "all" => pos |= ty::ALL,
                "document" | "doc" => {
                    if negated {
                        neg |= ty::DOCUMENT
                    } else if exception {
                        flags |= flag::DOC_ALLOW
                    } else {
                        pos |= ty::DOCUMENT
                    }
                }
                "elemhide" | "ehide" => flags |= flag::ELEMHIDE,
                "generichide" | "ghide" => flags |= flag::GENERICHIDE,
                "specifichide" | "shide" => flags |= flag::SPECIFICHIDE,
                "genericblock" => flags |= flag::GENERICBLOCK,
                "redirect" | "redirect-rule" => {
                    flags |= if name == "redirect" { flag::REDIRECT } else { flag::REDIRECT_RULE };
                    let (n, p) = match value.rfind(':') {
                        Some(i) if value[i + 1..].parse::<i32>().is_ok() => (&value[..i], value[i + 1..].parse().unwrap_or(0)),
                        _ => (value, 0),
                    };
                    redirect = Some(n.to_string());
                    priority = p;
                }
                "rewrite" => match abp_resource(value) {
                    Some(n) => {
                        flags |= flag::REDIRECT;
                        redirect = Some(n.to_string());
                    }
                    None => return Parsed::Skip,
                },
                "empty" => {
                    flags |= flag::REDIRECT;
                    redirect = Some("empty".into());
                }
                "mp4" => {
                    flags |= flag::REDIRECT;
                    redirect = Some("noopmp4-1s".into());
                    pos |= ty::MEDIA;
                }
                "removeparam" | "queryprune" => {
                    flags |= flag::REMOVEPARAM;
                    param = Some(value.to_string().into_boxed_str());
                }
                "method" => {
                    for m in value.split('|') {
                        let (n, mm) = match m.strip_prefix('~') {
                            Some(x) => (true, x),
                            None => (false, m),
                        };
                        let bit = method::from_name(mm);
                        if n { meth_neg |= bit } else { meth |= bit }
                    }
                }
                "popunder" | "csp" | "header" | "permissions" | "replace" | "urlskip" | "uritransform" | "urltransform"
                | "cookie" | "hls" | "jsonprune" | "xmlprune" | "ipaddress" | "cname" | "inline-script" | "inline-font"
                | "webrtc" | "extension" | "jsinject" | "content" | "stealth" | "app" | "network" | "referrerpolicy" => {
                    return Parsed::Skip
                }
                other => match ty::from_name(other) {
                    Some(t) => {
                        if negated { neg |= t } else { pos |= t }
                    }
                    None => return Parsed::Skip,
                },
            }
        }
    }

    if badfilter {
        return Parsed::Badfilter(canonical_key(exception, pat, &opt_list));
    }

    let mut mask = if pos != 0 { pos } else { ty::DEFAULT } & !neg;
    if flags & flag::REMOVEPARAM != 0 && pos == 0 {
        mask |= ty::DOCUMENT;
    }
    if flags & flag::PAGE != 0 {
        mask |= ty::DOCUMENT;
    }
    if meth_neg != 0 && meth == 0 {
        meth = !meth_neg;
    }
    if mask == 0 && flags & flag::PAGE == 0 {
        return Parsed::Skip;
    }
    // Cosmetic-only exception options make no sense on block filters.
    if !exception && flags & flag::PAGE != 0 {
        return Parsed::Skip;
    }

    let match_case = flags & flag::MATCH_CASE != 0;
    let mut tokens = Vec::new();
    let pattern;
    let p = pat;

    if p.len() > 2 && p.starts_with('/') && p.ends_with('/') {
        regex_tokens(&p[1..p.len() - 1], &mut tokens);
        pattern = Pattern::regex(&p[1..p.len() - 1], match_case);
    } else {
        // Plain hostnames (hosts-file style) behave as `||hostname^`.
        if opts.is_none() && !exception && valid_hostname(p) && p.rsplit('.').next().is_some_and(|t| t.len() >= 2 && t.bytes().all(|c| c.is_ascii_alphabetic())) {
            return Parsed::Host(p.to_ascii_lowercase(), false);
        }
        let mut s = p;
        let (mut host_anchor, mut left, mut right) = (false, false, false);
        if let Some(x) = s.strip_prefix("||") {
            host_anchor = true;
            s = x;
        } else if let Some(x) = s.strip_prefix('|') {
            left = true;
            s = x;
        }
        if s.ends_with('|') {
            right = true;
            s = &s[..s.len() - 1];
        }
        if !host_anchor && !left {
            s = s.trim_start_matches('*');
        }
        if !right {
            while s.len() > 1 && s.ends_with('*') {
                s = &s[..s.len() - 1];
            }
            if s == "*" {
                s = "";
            }
        }
        let text = if match_case { s.to_string() } else { s.to_ascii_lowercase() };

        if text.is_empty() && !host_anchor {
            pattern = Pattern::Any;
        } else if host_anchor {
            let hp_end = text
                .find(|c: char| !(c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_'))
                .unwrap_or(text.len());
            let host = &text[..hp_end];
            let rest = &text[hp_end..];
            // Fast path: `||ads.example.com^` with no other constraints.
            if !exception
                && flags == 0
                && mask == ty::DEFAULT
                && opts.is_none_or(|o| o.trim().is_empty())
                && rest == "^"
                && valid_hostname(host)
            {
                return Parsed::Host(host.to_string(), false);
            }
            if !exception
                && flags == flag::THIRD
                && mask == ty::DEFAULT
                && domains.is_none()
                && to.is_none()
                && denyallow.is_none()
                && meth == 0
                && rest == "^"
                && valid_hostname(host)
            {
                return Parsed::Host(host.to_string(), true);
            }
            let simple = !host.is_empty()
                && !host.ends_with('.')
                && (rest.starts_with('^') || rest.starts_with('/') || rest.starts_with(':') || rest.starts_with('?') || (rest.is_empty() && right));
            candidate_tokens(&text, true, right, &mut tokens);
            pattern = Pattern::Host { full: text.as_bytes().into(), host_len: host.len(), right, simple };
        } else if !text.contains('*') && !text.contains('^') {
            candidate_tokens(&text, left, right, &mut tokens);
            pattern = Pattern::Plain { text: text.into_boxed_str(), left, right };
        } else {
            candidate_tokens(&text, left, right, &mut tokens);
            let mut g = Vec::with_capacity(text.len() + 1);
            if !left {
                g.push(b'*');
            }
            g.extend_from_slice(text.as_bytes());
            pattern = Pattern::Glob { text: g.into(), right };
        }
    }

    Parsed::Filter(
        NetFilter {
            mask,
            flags,
            method: meth,
            pattern,
            domains,
            to,
            denyallow,
            redirect: redirect.map(String::into_boxed_str),
            priority,
            param,
            raw: raw.into(),
        },
        tokens,
    )
}

// ---------------------------------------------------------------------------
// Token index

#[derive(Default)]
pub struct FilterSet {
    pub filters: Vec<NetFilter>,
    index: FastMap<u32, Vec<u32>>,
    /// Token-less filters restricted to specific sites, keyed by site hash.
    by_domain: FastMap<u64, Vec<u32>>,
    no_token: Vec<u32>,
}

impl FilterSet {
    fn build(items: Vec<(NetFilter, Vec<u32>)>, freq: &FastMap<u32, u32>) -> FilterSet {
        let mut set = FilterSet::default();
        for (f, toks) in items {
            let id = set.filters.len() as u32;
            set.filters.push(f);
            let best = toks.iter().min_by_key(|t| freq.get(t).copied().unwrap_or(0));
            match best {
                Some(t) => set.index.entry(*t).or_default().push(id),
                None => {
                    let f = &set.filters[id as usize];
                    match f.domains.as_ref().filter(|d| d.has_includes()) {
                        Some(d) => {
                            for h in d.includes() {
                                set.by_domain.entry(*h).or_default().push(id);
                            }
                        }
                        None => set.no_token.push(id),
                    }
                }
            }
        }
        set.index.shrink_to_fit();
        set
    }

    pub fn len(&self) -> usize {
        self.filters.len()
    }

    pub fn first_match(&self, r: &Request, skip: &dyn Fn(&NetFilter) -> bool) -> Option<&NetFilter> {
        for t in &r.tokens {
            if let Some(ids) = self.index.get(t) {
                for &id in ids {
                    let f = &self.filters[id as usize];
                    if !skip(f) && f.matches(r) {
                        return Some(f);
                    }
                }
            }
        }
        for h in &r.source_hashes {
            if let Some(ids) = self.by_domain.get(h) {
                for &id in ids {
                    let f = &self.filters[id as usize];
                    if !skip(f) && f.matches(r) {
                        return Some(f);
                    }
                }
            }
        }
        for &id in &self.no_token {
            let f = &self.filters[id as usize];
            if !skip(f) && f.matches(r) {
                return Some(f);
            }
        }
        None
    }

    pub fn all_matches<'a>(&'a self, r: &Request, out: &mut Vec<&'a NetFilter>) {
        for t in &r.tokens {
            if let Some(ids) = self.index.get(t) {
                for &id in ids {
                    let f = &self.filters[id as usize];
                    if f.matches(r) {
                        out.push(f);
                    }
                }
            }
        }
        let start = out.len();
        for h in &r.source_hashes {
            if let Some(ids) = self.by_domain.get(h) {
                for &id in ids {
                    let f = &self.filters[id as usize];
                    if f.matches(r) && !out[start..].iter().any(|x| std::ptr::eq(*x, f)) {
                        out.push(f);
                    }
                }
            }
        }
        for &id in &self.no_token {
            let f = &self.filters[id as usize];
            if f.matches(r) {
                out.push(f);
            }
        }
    }

    pub fn regex_count(&self) -> usize {
        self.filters.iter().filter(|f| matches!(f.pattern, Pattern::Regex { .. })).count()
    }

    pub fn untokenized(&self) -> usize {
        self.no_token.len()
    }
}

// ---------------------------------------------------------------------------

#[derive(Default)]
pub struct Builder {
    blocks: Vec<(NetFilter, Vec<u32>)>,
    exceptions: Vec<(NetFilter, Vec<u32>)>,
    important: Vec<(NetFilter, Vec<u32>)>,
    redirects: Vec<(NetFilter, Vec<u32>)>,
    redirect_exceptions: Vec<(NetFilter, Vec<u32>)>,
    page: Vec<(NetFilter, Vec<u32>)>,
    removeparams: Vec<(NetFilter, Vec<u32>)>,
    removeparam_exceptions: Vec<(NetFilter, Vec<u32>)>,
    hosts: Vec<String>,
    hosts_3p: Vec<String>,
    bad: FastSet<u64>,
    seen: FastSet<u64>,
}

impl Builder {
    pub fn add(&mut self, line: &str) -> bool {
        match parse(line) {
            Parsed::Skip => false,
            Parsed::Badfilter(k) => {
                self.bad.insert(hash_str(&k));
                true
            }
            Parsed::Host(h, third) => {
                if third { self.hosts_3p.push(h) } else { self.hosts.push(h) }
                true
            }
            Parsed::Filter(f, toks) => {
                if !self.seen.insert(hash_str(&f.raw)) {
                    return true;
                }
                let fl = f.flags;
                let item = (f, toks);
                if fl & flag::EXCEPTION != 0 {
                    if fl & flag::PAGE != 0 {
                        self.page.push(item);
                    } else if fl & (flag::REDIRECT | flag::REDIRECT_RULE) != 0 {
                        self.redirect_exceptions.push(item);
                    } else if fl & flag::REMOVEPARAM != 0 {
                        self.removeparam_exceptions.push(item);
                    } else {
                        self.exceptions.push(item);
                    }
                } else if fl & flag::REMOVEPARAM != 0 {
                    self.removeparams.push(item);
                } else if fl & flag::REDIRECT_RULE != 0 {
                    self.redirects.push(item);
                } else if fl & flag::REDIRECT != 0 {
                    // `$redirect=` both blocks and redirects.
                    self.redirects.push((item.0.clone(), item.1.clone()));
                    if fl & flag::IMPORTANT != 0 { self.important.push(item) } else { self.blocks.push(item) }
                } else if fl & flag::IMPORTANT != 0 {
                    self.important.push(item);
                } else {
                    self.blocks.push(item);
                }
                true
            }
        }
    }

    pub fn build(self) -> NetworkEngine {
        let bad = self.bad;
        let keep = |v: Vec<(NetFilter, Vec<u32>)>| -> Vec<(NetFilter, Vec<u32>)> {
            if bad.is_empty() {
                return v;
            }
            v.into_iter().filter(|(f, _)| !bad.contains(&hash_str(&canonical_key_of_raw(&f.raw)))).collect()
        };
        let lists = [
            keep(self.blocks),
            keep(self.exceptions),
            keep(self.important),
            keep(self.redirects),
            keep(self.redirect_exceptions),
            keep(self.page),
            keep(self.removeparams),
            keep(self.removeparam_exceptions),
        ];
        let mut freq: FastMap<u32, u32> = FastMap::default();
        for l in &lists {
            for (_, toks) in l {
                for t in toks {
                    *freq.entry(*t).or_insert(0) += 1;
                }
            }
        }
        let [blocks, exceptions, important, redirects, redirect_exceptions, page, removeparams, removeparam_exceptions] = lists;

        let mut host_blocks = FastSet::default();
        for h in &self.hosts {
            if !bad.contains(&hash_str(&format!("||{}^", h))) && !bad.contains(&hash_str(h)) {
                host_blocks.insert(hash_str(h));
            }
        }
        let mut host_blocks_3p = FastSet::default();
        for h in &self.hosts_3p {
            if !bad.contains(&hash_str(&format!("||{}^$3p", h))) {
                host_blocks_3p.insert(hash_str(h));
            }
        }
        NetworkEngine {
            blocks: FilterSet::build(blocks, &freq),
            exceptions: FilterSet::build(exceptions, &freq),
            important: FilterSet::build(important, &freq),
            redirects: FilterSet::build(redirects, &freq),
            redirect_exceptions: FilterSet::build(redirect_exceptions, &freq),
            page: FilterSet::build(page, &freq),
            removeparams: FilterSet::build(removeparams, &freq),
            removeparam_exceptions: FilterSet::build(removeparam_exceptions, &freq),
            host_blocks,
            host_blocks_3p,
        }
    }
}

pub struct NetworkEngine {
    pub blocks: FilterSet,
    pub exceptions: FilterSet,
    pub important: FilterSet,
    pub redirects: FilterSet,
    pub redirect_exceptions: FilterSet,
    pub page: FilterSet,
    pub removeparams: FilterSet,
    pub removeparam_exceptions: FilterSet,
    pub host_blocks: FastSet<u64>,
    pub host_blocks_3p: FastSet<u64>,
}

#[derive(Default, Debug, Clone)]
pub struct NetResult {
    pub blocked: bool,
    pub important: bool,
    pub exception: bool,
    pub page_allowed: bool,
    pub redirect: Option<String>,
    pub filter: Option<String>,
}

impl NetworkEngine {
    /// Union of page-level exception flags (`$document`, `$elemhide`, ...) for a page URL.
    pub fn page_flags(&self, page: &Request) -> u32 {
        let mut out = Vec::new();
        self.page.all_matches(page, &mut out);
        out.iter().fold(0, |acc, f| acc | (f.flags & flag::PAGE))
    }

    fn host_match(&self, r: &Request) -> Option<String> {
        if r.ty & (ty::DEFAULT | ty::DOCUMENT) == 0 {
            return None;
        }
        let host = r.host();
        let mut start = 0;
        loop {
            let h = hash_str(&host[start..]);
            if self.host_blocks.contains(&h) || (r.third_party && self.host_blocks_3p.contains(&h)) {
                return Some(format!("||{}^", &host[start..]));
            }
            match host[start..].find('.') {
                Some(i) => start += i + 1,
                None => return None,
            }
        }
    }

    pub fn check(&self, r: &Request, page_flags: u32) -> NetResult {
        let mut res = NetResult::default();
        if page_flags & flag::DOC_ALLOW != 0 {
            res.page_allowed = true;
            return res;
        }
        if let Some(f) = self.important.first_match(r, &|_| false) {
            res.blocked = true;
            res.important = true;
            res.filter = Some(f.raw.to_string());
            res.redirect = self.find_redirect(r);
            return res;
        }
        let generic_block_off = page_flags & flag::GENERICBLOCK != 0;
        let mut matched = self.host_match(r);
        if matched.is_none() {
            let skip = |f: &NetFilter| generic_block_off && f.is_generic();
            matched = self.blocks.first_match(r, &skip).map(|f| f.raw.to_string());
        }
        let Some(m) = matched else { return res };
        if let Some(e) = self.exceptions.first_match(r, &|_| false) {
            res.exception = true;
            res.filter = Some(e.raw.to_string());
            return res;
        }
        res.blocked = true;
        res.filter = Some(m);
        res.redirect = self.find_redirect(r);
        res
    }

    fn find_redirect(&self, r: &Request) -> Option<String> {
        let mut cands = Vec::new();
        self.redirects.all_matches(r, &mut cands);
        if cands.is_empty() {
            return None;
        }
        let mut excepted = Vec::new();
        self.redirect_exceptions.all_matches(r, &mut excepted);
        if excepted.iter().any(|e| e.redirect.as_deref().map_or(true, |n| n.is_empty() || n == "none")) && !excepted.is_empty() {
            if excepted.iter().any(|e| e.redirect.as_deref().map_or(true, |n| n.is_empty())) {
                return None;
            }
        }
        cands.sort_by_key(|f| -f.priority);
        cands
            .into_iter()
            .filter_map(|f| f.redirect.as_deref())
            .find(|name| *name != "none" && !excepted.iter().any(|e| e.redirect.as_deref() == Some(*name)))
            .map(|s| s.to_string())
    }

    /// Applies `$removeparam` filters. Returns the cleaned URL when something was removed.
    pub fn clean_url(&self, r: &Request) -> Option<String> {
        let url = &r.url_raw;
        let q = url.find('?')?;
        let frag = url[q..].find('#').map(|i| q + i).unwrap_or(url.len());
        let mut hits = Vec::new();
        self.removeparams.all_matches(r, &mut hits);
        if hits.is_empty() {
            return None;
        }
        let mut exc = Vec::new();
        self.removeparam_exceptions.all_matches(r, &mut exc);
        if exc.iter().any(|e| e.param.as_deref().is_none_or(|p| p.is_empty())) {
            return None;
        }
        let params: Vec<&str> = url[q + 1..frag].split('&').filter(|p| !p.is_empty()).collect();
        let mut keep = vec![true; params.len()];
        for f in &hits {
            let spec = f.param.as_deref().unwrap_or("");
            if exc.iter().any(|e| e.param.as_deref() == Some(spec)) {
                continue;
            }
            if spec.is_empty() {
                keep.iter_mut().for_each(|k| *k = false);
                continue;
            }
            let (negate, spec) = match spec.strip_prefix('~') {
                Some(s) => (true, s),
                None => (false, spec),
            };
            let re = if spec.len() > 2 && spec.starts_with('/') && spec.rfind('/').is_some_and(|i| i > 0) {
                let end = spec.rfind('/').unwrap();
                let flags = &spec[end + 1..];
                removeparam_regex(&spec[1..end], flags.contains('i'))
            } else {
                None
            };
            for (i, p) in params.iter().enumerate() {
                let name = p.split('=').next().unwrap_or("");
                let hit = match &re {
                    Some(re) => re.is_match(p),
                    None => name == spec,
                };
                if hit != negate {
                    keep[i] = false;
                }
            }
        }
        if keep.iter().all(|k| *k) {
            return None;
        }
        let kept: Vec<&str> = params.iter().zip(keep.iter()).filter(|(_, k)| **k).map(|(p, _)| *p).collect();
        let mut out = String::with_capacity(url.len());
        out.push_str(&url[..q]);
        if !kept.is_empty() {
            out.push('?');
            out.push_str(&kept.join("&"));
        }
        out.push_str(&url[frag..]);
        Some(out)
    }

    pub fn filter_count(&self) -> usize {
        self.blocks.len()
            + self.exceptions.len()
            + self.important.len()
            + self.redirects.len()
            + self.redirect_exceptions.len()
            + self.page.len()
            + self.removeparams.len()
            + self.removeparam_exceptions.len()
            + self.host_blocks.len()
            + self.host_blocks_3p.len()
    }
}

impl FilterSet {
    pub fn untokenized_raw(&self, n: usize) -> Vec<&str> {
        self.no_token.iter().take(n).map(|&i| &*self.filters[i as usize].raw).collect()
    }
    pub fn untokenized_with_domains(&self) -> usize {
        self.no_token.iter().filter(|&&i| self.filters[i as usize].domains.as_ref().is_some_and(|d| d.has_includes())).count()
    }
}

// ---------------------------------------------------------------------------
// Binary cache (see codec.rs). The engine is split into independent parts so
// they can be decoded on several threads: filter chunks, each set's index, and
// the hostname sets.

use crate::codec::{Reader, Writer, CHUNK};

pub(crate) enum NetPart {
    Filters(usize, Vec<NetFilter>),
    Index(usize, usize, FastMap<u32, Vec<u32>>, FastMap<u64, Vec<u32>>, Vec<u32>),
    Hosts(bool, FastSet<u64>),
}

const SETS: usize = 8;

impl DomainSet {
    fn encode(&self, w: &mut Writer) {
        w.hashes(&self.include);
        w.hashes(&self.exclude);
    }

    fn decode(r: &mut Reader) -> Option<DomainSet> {
        Some(DomainSet { include: r.hashes()?, exclude: r.hashes()? })
    }
}

fn encode_domains(w: &mut Writer, d: &Option<Box<DomainSet>>) {
    match d {
        Some(d) => {
            w.u8(1);
            d.encode(w);
        }
        None => w.u8(0),
    }
}

fn decode_domains(r: &mut Reader) -> Option<Option<Box<DomainSet>>> {
    Some(if r.bool()? { Some(Box::new(DomainSet::decode(r)?)) } else { None })
}

impl NetFilter {
    fn encode(&self, w: &mut Writer) {
        w.u32(self.mask);
        w.u32(self.flags);
        w.u8(self.method);
        self.pattern.encode(w);
        encode_domains(w, &self.domains);
        encode_domains(w, &self.to);
        encode_domains(w, &self.denyallow);
        w.opt_str(self.redirect.as_deref());
        w.i32(self.priority);
        w.opt_str(self.param.as_deref());
        w.str(&self.raw);
    }

    fn decode(r: &mut Reader) -> Option<NetFilter> {
        Some(NetFilter {
            mask: r.u32()?,
            flags: r.u32()?,
            method: r.u8()?,
            pattern: Pattern::decode(r)?,
            domains: decode_domains(r)?,
            to: decode_domains(r)?,
            denyallow: decode_domains(r)?,
            redirect: r.opt_boxed()?,
            priority: r.i32()?,
            param: r.opt_boxed()?,
            raw: r.boxed()?,
        })
    }
}

impl NetworkEngine {
    fn sets(&self) -> [&FilterSet; SETS] {
        [
            &self.blocks,
            &self.exceptions,
            &self.important,
            &self.redirects,
            &self.redirect_exceptions,
            &self.page,
            &self.removeparams,
            &self.removeparam_exceptions,
        ]
    }

    pub(crate) fn encode_parts(&self, out: &mut Vec<Vec<u8>>) {
        for (k, set) in self.sets().into_iter().enumerate() {
            for chunk in set.filters.chunks(CHUNK) {
                let mut w = Writer::with_capacity(chunk.len() * 160);
                w.u8(0);
                w.u8(k as u8);
                w.len(chunk.len());
                for f in chunk {
                    f.encode(&mut w);
                }
                out.push(w.buf);
            }
            let mut w = Writer::with_capacity(set.index.len() * 12 + 64);
            w.u8(1);
            w.u8(k as u8);
            w.len(set.filters.len());
            w.map_u32_ids(&set.index);
            w.map_u64_ids(&set.by_domain);
            w.ids(&set.no_token);
            out.push(w.buf);
        }
        for (third, hosts) in [(false, &self.host_blocks), (true, &self.host_blocks_3p)] {
            let mut w = Writer::with_capacity(hosts.len() * 8 + 16);
            w.u8(2);
            w.bool(third);
            w.hash_set(hosts);
            out.push(w.buf);
        }
    }

    pub(crate) fn decode_part(b: &[u8]) -> Option<NetPart> {
        let mut r = Reader::new(b);
        let part = match r.u8()? {
            0 => {
                let k = r.u8()? as usize;
                let n = r.len(8)?;
                let mut v = Vec::with_capacity(n);
                for _ in 0..n {
                    v.push(NetFilter::decode(&mut r)?);
                }
                NetPart::Filters(k, v)
            }
            1 => {
                let k = r.u8()? as usize;
                let n = usize::try_from(r.var()?).ok()?;
                NetPart::Index(k, n, r.map_u32_ids(n)?, r.map_u64_ids(n)?, r.ids_below(n)?)
            }
            2 => NetPart::Hosts(r.bool()?, r.hash_set()?),
            _ => return None,
        };
        if !r.at_end() {
            return None;
        }
        if let NetPart::Filters(k, _) | NetPart::Index(k, ..) = part {
            if k >= SETS {
                return None;
            }
        }
        Some(part)
    }

    /// Puts decoded parts (in their original order) back together.
    pub(crate) fn assemble(parts: Vec<NetPart>) -> Option<NetworkEngine> {
        let mut filters: [Vec<NetFilter>; SETS] = Default::default();
        let mut sets: [Option<FilterSet>; SETS] = Default::default();
        let mut hosts: [Option<FastSet<u64>>; 2] = [None, None];
        for p in parts {
            match p {
                NetPart::Filters(k, v) => {
                    if sets[k].is_some() {
                        return None; // a set's chunks come before its index
                    }
                    if filters[k].is_empty() {
                        filters[k] = v;
                    } else {
                        filters[k].extend(v);
                    }
                }
                NetPart::Index(k, n, index, by_domain, no_token) => {
                    if sets[k].is_some() || filters[k].len() != n {
                        return None;
                    }
                    sets[k] = Some(FilterSet { filters: std::mem::take(&mut filters[k]), index, by_domain, no_token });
                }
                NetPart::Hosts(third, set) => {
                    let slot = &mut hosts[third as usize];
                    if slot.is_some() {
                        return None;
                    }
                    *slot = Some(set);
                }
            }
        }
        let [blocks, exceptions, important, redirects, redirect_exceptions, page, removeparams, removeparam_exceptions] = sets;
        let [host_blocks, host_blocks_3p] = hosts;
        Some(NetworkEngine {
            blocks: blocks?,
            exceptions: exceptions?,
            important: important?,
            redirects: redirects?,
            redirect_exceptions: redirect_exceptions?,
            page: page?,
            removeparams: removeparams?,
            removeparam_exceptions: removeparam_exceptions?,
            host_blocks: host_blocks?,
            host_blocks_3p: host_blocks_3p?,
        })
    }
}
