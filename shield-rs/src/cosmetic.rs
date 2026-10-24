//! Cosmetic filters: element hiding, CSS styling, procedural selectors and scriptlets.

use crate::hash::{hash_entity, hash_str, suffix_hashes, FastMap, FastSet};
use crate::json::{push_str, push_str_array};
use crate::psl::psl;

const PROCEDURAL_OPS: &[&str] = &[
    ":has-text(", ":upward(", ":xpath(", ":matches-css(", ":matches-css-before(", ":matches-css-after(",
    ":min-text-length(", ":remove(", ":remove-attr(", ":remove-class(", ":matches-attr(", ":matches-prop(",
    ":matches-media(", ":others(", ":watch-attr(", ":shadow(", ":if(", ":if-not(", ":nth-ancestor(", ":style(",
    ":matches-path(",
];

const TRUSTED_ONLY: &[&str] = &["replace-node-text", "rpnt"];

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Hide,
    Style,
    Procedural,
}

struct Rule {
    kind: Kind,
    sel: Box<str>,
    css: Option<Box<str>>,
    exclude: Option<Box<[u64]>>,
    path: Option<Box<str>>,
}

struct Generic {
    sel: Box<str>,
    exclude: Option<Box<[u64]>>,
}

struct Scriptlet {
    args: Vec<Box<str>>,
    exclude: Option<Box<[u64]>>,
}

#[derive(Default)]
pub struct CosmeticEngine {
    generic: Vec<Generic>,
    class_map: FastMap<u64, Vec<u32>>,
    id_map: FastMap<u64, Vec<u32>>,
    always: Vec<u32>,
    rules: Vec<Rule>,
    specific: FastMap<u64, Vec<u32>>,
    exceptions: FastMap<u64, Vec<Box<str>>>,
    global_exceptions: FastSet<u64>,
    scriptlets: Vec<Scriptlet>,
    scriptlet_hosts: FastMap<u64, Vec<u32>>,
    generic_scriptlets: Vec<u32>,
    scriptlet_exceptions: FastMap<u64, Vec<Box<str>>>,
    seen: FastSet<u64>,
}

#[derive(Default, Clone)]
pub struct CosmeticResult {
    pub hide: Vec<String>,
    pub style: Vec<(String, String)>,
    pub procedural: Vec<String>,
    pub scriptlets: Vec<Vec<String>>,
    pub generic_hide: bool,
}

/// Canonical scriptlet names, so aliases and `.js` suffixes compare equal.
pub fn canonical_scriptlet(name: &str) -> String {
    let n = name.trim().trim_start_matches("ubo-").trim_end_matches(".js");
    let c = match n {
        "set" | "set-constant" => "set-constant",
        "aopr" | "abort-on-property-read" => "abort-on-property-read",
        "aopw" | "abort-on-property-write" => "abort-on-property-write",
        "acs" | "acis" | "abort-current-script" | "abort-current-inline-script" => "abort-current-script",
        "aost" | "abort-on-stack-trace" => "abort-on-stack-trace",
        "nowoif" | "no-window-open-if" | "window.open-defuser" | "prevent-window-open" => "no-window-open-if",
        "nostif" | "no-setTimeout-if" | "setTimeout-defuser" | "prevent-setTimeout" => "no-setTimeout-if",
        "nosiif" | "no-setInterval-if" | "setInterval-defuser" | "prevent-setInterval" => "no-setInterval-if",
        "aeld" | "addEventListener-defuser" | "prevent-addEventListener" => "addEventListener-defuser",
        "rmnt" | "remove-node-text" => "remove-node-text",
        "rpnt" | "replace-node-text" => "replace-node-text",
        "no-fetch-if" | "prevent-fetch" => "no-fetch-if",
        "no-xhr-if" | "prevent-xhr" => "no-xhr-if",
        "nano-sib" | "nano-setInterval-booster" | "adjust-setInterval" => "nano-setInterval-booster",
        "nano-stb" | "nano-setTimeout-booster" | "adjust-setTimeout" => "nano-setTimeout-booster",
        "noeval-if" | "prevent-eval-if" => "noeval-if",
        "ra" | "remove-attr" => "remove-attr",
        "rc" | "remove-class" => "remove-class",
        "trusted-set" | "trusted-set-constant" => "trusted-set-constant",
        "trusted-rpnt" | "trusted-replace-node-text" => "replace-node-text",
        "trusted-rpfr" | "trusted-replace-fetch-response" => "trusted-replace-fetch-response",
        "remove-cookie" | "cookie-remover" => "cookie-remover",
        "nowebrtc" => "nowebrtc",
        "norafif" | "no-requestAnimationFrame-if" | "prevent-requestAnimationFrame" => "no-requestAnimationFrame-if",
        "nobab" | "bab-defuser" => "nobab",
        "nofab" | "fuckadblock.js-3.2.0" => "nofab",
        "popads-dummy" | "popads.net" => "popads-dummy",
        "refresh-defuser" | "prevent-refresh" => "refresh-defuser",
        "window.name-defuser" => "window.name-defuser",
        "close-window" | "window-close-if" => "window-close-if",
        "json-prune" => "json-prune",
        "abort-current-inline-script-if" => "abort-current-script",
        other => other,
    };
    c.to_string()
}

fn ident_end(s: &[u8], start: usize) -> usize {
    let mut i = start;
    while i < s.len() && (s[i].is_ascii_alphanumeric() || s[i] == b'-' || s[i] == b'_' || s[i] >= 0x80) {
        i += 1;
    }
    i
}

/// First `.class` or `#id` of a selector, used to look generic rules up lazily.
fn selector_key(sel: &str) -> Option<(bool, u64)> {
    let b = sel.as_bytes();
    if b.len() < 2 || (b[0] != b'.' && b[0] != b'#') {
        return None;
    }
    // Selector lists can't be keyed by a single name.
    let mut depth = 0i32;
    for &c in b {
        match c {
            b'(' | b'[' => depth += 1,
            b')' | b']' => depth -= 1,
            b',' if depth == 0 => return None,
            _ => {}
        }
    }
    let end = ident_end(b, 1);
    if end == 1 || (end < b.len() && b[end] == b'\\') {
        return None;
    }
    Some((b[0] == b'.', hash_str(&sel[1..end])))
}

/// Splits scriptlet arguments, honouring quotes and `\,` escapes.
pub fn split_args(s: &str) -> Vec<String> {
    let chars: Vec<char> = s.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i <= chars.len() {
        while i < chars.len() && chars[i] == ' ' {
            i += 1;
        }
        let mut cur = String::new();
        if i < chars.len() && (chars[i] == '\'' || chars[i] == '"' || chars[i] == '`') {
            let q = chars[i];
            let mut j = i + 1;
            let mut closed = None;
            while j < chars.len() {
                if chars[j] == '\\' && j + 1 < chars.len() && chars[j + 1] == q {
                    cur.push(q);
                    j += 2;
                    continue;
                }
                if chars[j] == q {
                    closed = Some(j);
                    break;
                }
                cur.push(chars[j]);
                j += 1;
            }
            if let Some(c) = closed {
                let mut k = c + 1;
                while k < chars.len() && chars[k] == ' ' {
                    k += 1;
                }
                if k >= chars.len() || chars[k] == ',' {
                    out.push(cur);
                    i = k + 1;
                    continue;
                }
            }
            cur.clear();
        }
        let mut j = i;
        while j < chars.len() {
            if chars[j] == '\\' && j + 1 < chars.len() && chars[j + 1] == ',' {
                cur.push(',');
                j += 2;
                continue;
            }
            if chars[j] == ',' {
                break;
            }
            cur.push(chars[j]);
            j += 1;
        }
        out.push(cur.trim().to_string());
        i = j + 1;
    }
    out
}

fn parse_domains(list: &str) -> Option<(Vec<u64>, Vec<u64>)> {
    let mut inc = Vec::new();
    let mut exc = Vec::new();
    for raw in list.split(',') {
        let d = raw.trim();
        if d.is_empty() {
            continue;
        }
        let (neg, name) = match d.strip_prefix('~') {
            Some(n) => (true, n),
            None => (false, d),
        };
        if name.starts_with('/') || name.contains('/') {
            if !neg {
                return None;
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
    exc.sort_unstable();
    Some((inc, exc))
}

fn balanced_end(s: &str, open: usize) -> Option<usize> {
    let b = s.as_bytes();
    let mut depth = 0;
    let mut i = open;
    while i < b.len() {
        match b[i] {
            b'\\' => i += 1,
            b'(' => depth += 1,
            b')' => {
                depth -= 1;
                if depth == 0 {
                    return Some(i);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

impl CosmeticEngine {
    /// Returns false if `line` is not a cosmetic filter at all.
    pub fn add(&mut self, line: &str, trusted: bool) -> Option<bool> {
        let hash_pos = line.find('#')?;
        let (domains, rest) = line.split_at(hash_pos);
        let (marker, body) = [
            ("#@$?#", "@css"), ("#@%#", "@js"), ("#@$#", "@css"), ("#@?#", "@hide"), ("#@#", "@hide"),
            ("#$?#", "css"), ("#$#", "css"), ("#%#", "js"), ("#?#", "hide"), ("##", "hide"),
        ]
        .iter()
        .find(|(m, _)| rest.starts_with(m))
        .map(|(m, k)| (*k, &rest[m.len()..]))?;
        if domains.contains(['/', '|', '$', '=']) && !domains.is_empty() {
            return None;
        }
        if !self.seen.insert(hash_str(line)) {
            return Some(true);
        }
        let Some((inc, exc)) = parse_domains(domains) else { return Some(false) };
        let body = body.trim();
        if body.is_empty() {
            return Some(false);
        }
        let exception = marker.starts_with('@');
        let kind = marker.trim_start_matches('@');

        match kind {
            "js" => {
                // AdGuard: #%#//scriptlet('name', 'arg')
                let Some(inner) = body.strip_prefix("//scriptlet(").and_then(|b| b.strip_suffix(')')) else { return Some(false) };
                let mut args = split_args(inner);
                if args.is_empty() {
                    return Some(false);
                }
                args[0] = canonical_scriptlet(&args[0]);
                self.add_scriptlet(args, &inc, &exc, exception, false);
                Some(true)
            }
            "css" => {
                let Some(brace) = body.rfind('{') else { return Some(false) };
                let sel = body[..brace].trim();
                let css = body[brace + 1..].trim().trim_end_matches('}').trim();
                if exception || inc.is_empty() || sel.is_empty() || css.contains("url(") {
                    return Some(false);
                }
                self.add_specific(Kind::Style, sel, Some(css), None, &inc, &exc);
                Some(true)
            }
            _ => {
                if let Some(inner) = body.strip_prefix("+js(") {
                    let Some(inner) = inner.strip_suffix(')') else { return Some(false) };
                    let mut args = split_args(inner);
                    if args.is_empty() || (args.len() == 1 && args[0].is_empty()) {
                        if exception {
                            for h in &inc {
                                self.scriptlet_exceptions.entry(*h).or_default().push("".into());
                            }
                            return Some(true);
                        }
                        return Some(false);
                    }
                    args[0] = canonical_scriptlet(&args[0]);
                    self.add_scriptlet(args, &inc, &exc, exception, trusted);
                    return Some(true);
                }
                if body.starts_with('^') {
                    return Some(false);
                }
                let mut sel = body
                    .replace(":-abp-has(", ":has(")
                    .replace(":-abp-contains(", ":has-text(")
                    .replace(":contains(", ":has-text(");
                let mut path = None;
                if sel.starts_with(":matches-path(") {
                    let end = balanced_end(&sel, ":matches-path".len())?;
                    path = Some(sel[":matches-path(".len()..end].to_string());
                    sel = sel[end + 1..].trim().to_string();
                    if sel.is_empty() {
                        return Some(false);
                    }
                }
                if exception {
                    if inc.is_empty() {
                        self.global_exceptions.insert(hash_str(&sel));
                    } else {
                        for h in &inc {
                            self.exceptions.entry(*h).or_default().push(sel.clone().into());
                        }
                    }
                    return Some(true);
                }
                let procedural = PROCEDURAL_OPS.iter().any(|op| sel.contains(op));
                if procedural {
                    if inc.is_empty() {
                        return Some(false); // generic procedural filters are too costly
                    }
                    // `css-selector:style(...)` needs no JavaScript.
                    if let Some(i) = sel.find(":style(") {
                        let prefix = &sel[..i];
                        if sel.ends_with(')') && !PROCEDURAL_OPS.iter().any(|op| prefix.contains(op)) {
                            let css = &sel[i + 7..sel.len() - 1];
                            if !css.contains("url(") && !css.contains('{') {
                                let (p, c) = (prefix.to_string(), css.to_string());
                                self.add_specific(Kind::Style, &p, Some(&c), path.as_deref(), &inc, &exc);
                                return Some(true);
                            }
                            return Some(false);
                        }
                    }
                    self.add_specific(Kind::Procedural, &sel, None, path.as_deref(), &inc, &exc);
                    return Some(true);
                }
                if sel.contains('{') || sel.contains('}') {
                    return Some(false);
                }
                if inc.is_empty() {
                    let id = self.generic.len() as u32;
                    self.generic.push(Generic {
                        sel: sel.as_str().into(),
                        exclude: if exc.is_empty() { None } else { Some(exc.into()) },
                    });
                    match selector_key(&sel) {
                        Some((true, k)) => self.class_map.entry(k).or_default().push(id),
                        Some((false, k)) => self.id_map.entry(k).or_default().push(id),
                        None => self.always.push(id),
                    }
                } else {
                    self.add_specific(Kind::Hide, &sel, None, path.as_deref(), &inc, &exc);
                }
                Some(true)
            }
        }
    }

    fn add_specific(&mut self, kind: Kind, sel: &str, css: Option<&str>, path: Option<&str>, inc: &[u64], exc: &[u64]) {
        let id = self.rules.len() as u32;
        self.rules.push(Rule {
            kind,
            sel: sel.into(),
            css: css.map(Into::into),
            exclude: if exc.is_empty() { None } else { Some(exc.into()) },
            path: path.map(Into::into),
        });
        for h in inc {
            self.specific.entry(*h).or_default().push(id);
        }
    }

    fn add_scriptlet(&mut self, args: Vec<String>, inc: &[u64], exc: &[u64], exception: bool, trusted: bool) {
        let name = args[0].as_str();
        if exception {
            let key: Box<str> = if args.len() == 1 { name.into() } else { args.join(",").into() };
            for h in inc {
                self.scriptlet_exceptions.entry(*h).or_default().push(key.clone());
            }
            return;
        }
        if (name.starts_with("trusted-") || TRUSTED_ONLY.contains(&name)) && !trusted {
            return;
        }
        let id = self.scriptlets.len() as u32;
        self.scriptlets.push(Scriptlet {
            args: args.into_iter().map(String::into_boxed_str).collect(),
            exclude: if exc.is_empty() { None } else { Some(exc.to_vec().into()) },
        });
        if inc.is_empty() {
            self.generic_scriptlets.push(id);
        } else {
            for h in inc {
                self.scriptlet_hosts.entry(*h).or_default().push(id);
            }
        }
    }

    pub fn page_hashes(host: &str) -> Vec<u64> {
        let mut v = Vec::with_capacity(10);
        suffix_hashes(host, &mut v);
        let base = psl().without_suffix(host);
        if !base.is_empty() {
            let mut start = 0;
            loop {
                v.push(hash_entity(&base[start..]));
                match base[start..].find('.') {
                    Some(i) => start += i + 1,
                    None => break,
                }
            }
        }
        v
    }

    fn excluded(ex: &Option<Box<[u64]>>, hashes: &[u64]) -> bool {
        match ex {
            Some(e) => hashes.iter().any(|h| e.binary_search(h).is_ok()),
            None => false,
        }
    }

    fn page_exceptions(&self, hashes: &[u64]) -> FastSet<u64> {
        let mut set = FastSet::default();
        for h in hashes {
            if let Some(v) = self.exceptions.get(h) {
                for s in v {
                    set.insert(hash_str(s));
                }
            }
        }
        set
    }

    /// Everything a page needs at document start.
    pub fn url_resources(&self, host: &str, path: &str, elemhide: bool, generichide: bool, specifichide: bool) -> CosmeticResult {
        let hashes = Self::page_hashes(host);
        let exc = self.page_exceptions(&hashes);
        let allowed = |sel: &str| {
            let h = hash_str(sel);
            !exc.contains(&h) && !self.global_exceptions.contains(&h)
        };
        let mut res = CosmeticResult { generic_hide: elemhide || generichide, ..Default::default() };

        if !elemhide {
            if !generichide {
                for &id in &self.always {
                    let g = &self.generic[id as usize];
                    if !Self::excluded(&g.exclude, &hashes) && allowed(&g.sel) {
                        res.hide.push(g.sel.to_string());
                    }
                }
            }
            if !specifichide {
                let mut ids: Vec<u32> = hashes.iter().filter_map(|h| self.specific.get(h)).flatten().copied().collect();
                ids.sort_unstable();
                ids.dedup();
                for id in ids {
                    let r = &self.rules[id as usize];
                    if Self::excluded(&r.exclude, &hashes) || !allowed(&r.sel) {
                        continue;
                    }
                    if let Some(p) = &r.path {
                        if !path_matches(p, path) {
                            continue;
                        }
                    }
                    match r.kind {
                        Kind::Hide => res.hide.push(r.sel.to_string()),
                        Kind::Style => res.style.push((r.sel.to_string(), r.css.as_deref().unwrap_or("").to_string())),
                        Kind::Procedural => res.procedural.push(r.sel.to_string()),
                    }
                }
            }
        }

        // Scriptlets
        let mut sx: Vec<&str> = Vec::new();
        for h in &hashes {
            if let Some(v) = self.scriptlet_exceptions.get(h) {
                sx.extend(v.iter().map(|s| &**s));
            }
        }
        if !sx.contains(&"") {
            let mut ids: Vec<u32> = hashes.iter().filter_map(|h| self.scriptlet_hosts.get(h)).flatten().copied().collect();
            ids.extend(self.generic_scriptlets.iter().copied());
            ids.sort_unstable();
            ids.dedup();
            for id in ids {
                let s = &self.scriptlets[id as usize];
                if Self::excluded(&s.exclude, &hashes) {
                    continue;
                }
                let full = s.args.join(",");
                if sx.iter().any(|e| *e == &*s.args[0] || *e == full) {
                    continue;
                }
                res.scriptlets.push(s.args.iter().map(|a| a.to_string()).collect());
            }
        }
        res
    }

    /// Generic selectors keyed by classes/ids that are present in the page.
    pub fn hidden_selectors(&self, host: &str, classes: &str, ids: &str) -> Vec<String> {
        let hashes = Self::page_hashes(host);
        let exc = self.page_exceptions(&hashes);
        let mut out = Vec::new();
        let mut seen = FastSet::default();
        let mut visit = |map: &FastMap<u64, Vec<u32>>, names: &str| {
            for n in names.split(|c: char| c.is_whitespace() || c == ',').filter(|s| !s.is_empty()) {
                if let Some(v) = map.get(&hash_str(n)) {
                    for &id in v {
                        if !seen.insert(id) {
                            continue;
                        }
                        let g = &self.generic[id as usize];
                        let h = hash_str(&g.sel);
                        if Self::excluded(&g.exclude, &hashes) || exc.contains(&h) || self.global_exceptions.contains(&h) {
                            continue;
                        }
                        out.push(g.sel.to_string());
                    }
                }
            }
        };
        visit(&self.class_map, classes);
        visit(&self.id_map, ids);
        out
    }

    pub fn counts(&self) -> (usize, usize, usize, usize) {
        (self.generic.len(), self.rules.len(), self.scriptlets.len(), self.always.len())
    }
}

/// `:matches-path()` accepts a plain substring or a `/regex/`.
fn path_matches(spec: &str, path: &str) -> bool {
    let (neg, spec) = match spec.strip_prefix('!') {
        Some(s) => (true, s),
        None => (false, spec),
    };
    let hit = if spec.len() > 2 && spec.starts_with('/') && spec.ends_with('/') {
        regex::Regex::new(&spec[1..spec.len() - 1]).map(|r| r.is_match(path)).unwrap_or(false)
    } else {
        path.contains(spec)
    };
    hit != neg
}

impl CosmeticResult {
    pub fn to_json(&self) -> String {
        let mut o = String::with_capacity(256 + self.hide.iter().map(|s| s.len() + 3).sum::<usize>());
        o.push_str("{\"hide\":");
        push_str_array(&mut o, self.hide.iter().map(|s| s.as_str()));
        o.push_str(",\"style\":[");
        for (i, (s, c)) in self.style.iter().enumerate() {
            if i > 0 {
                o.push(',');
            }
            o.push('[');
            push_str(&mut o, s);
            o.push(',');
            push_str(&mut o, c);
            o.push(']');
        }
        o.push_str("],\"proc\":");
        push_str_array(&mut o, self.procedural.iter().map(|s| s.as_str()));
        o.push_str(",\"js\":[");
        for (i, a) in self.scriptlets.iter().enumerate() {
            if i > 0 {
                o.push(',');
            }
            push_str_array(&mut o, a.iter().map(|s| s.as_str()));
        }
        o.push_str("],\"ghide\":");
        o.push_str(if self.generic_hide { "true" } else { "false" });
        o.push('}');
        o
    }
}

// ---------------------------------------------------------------------------
// Binary cache (see codec.rs). Large vectors are chunked and every map is its
// own part, so parts can be decoded on several threads.

use crate::codec::{Reader, Writer, CHUNK};

/// One decoded part; opaque outside this module.
pub(crate) struct CosPart(Piece);

enum Piece {
    Generic(Vec<Generic>),
    Rules(Vec<Rule>),
    Scriptlets(Vec<Scriptlet>),
    ClassMap(usize, FastMap<u64, Vec<u32>>),
    IdMap(usize, FastMap<u64, Vec<u32>>, Vec<u32>),
    Specific(usize, FastMap<u64, Vec<u32>>),
    Exceptions(FastMap<u64, Vec<Box<str>>>, FastSet<u64>),
    ScriptletIndex(usize, FastMap<u64, Vec<u32>>, Vec<u32>, FastMap<u64, Vec<Box<str>>>),
}

fn kind_code(k: Kind) -> u8 {
    match k {
        Kind::Hide => 0,
        Kind::Style => 1,
        Kind::Procedural => 2,
    }
}

impl CosmeticEngine {
    pub(crate) fn encode_parts(&self, out: &mut Vec<Vec<u8>>) {
        for chunk in self.generic.chunks(CHUNK) {
            let mut w = Writer::with_capacity(chunk.len() * 40);
            w.u8(0);
            w.len(chunk.len());
            for g in chunk {
                w.str(&g.sel);
                w.opt_hashes(g.exclude.as_deref());
            }
            out.push(w.buf);
        }
        for chunk in self.rules.chunks(CHUNK) {
            let mut w = Writer::with_capacity(chunk.len() * 48);
            w.u8(1);
            w.len(chunk.len());
            for r in chunk {
                w.u8(kind_code(r.kind));
                w.str(&r.sel);
                w.opt_str(r.css.as_deref());
                w.opt_hashes(r.exclude.as_deref());
                w.opt_str(r.path.as_deref());
            }
            out.push(w.buf);
        }
        for chunk in self.scriptlets.chunks(CHUNK) {
            let mut w = Writer::with_capacity(chunk.len() * 48);
            w.u8(2);
            w.len(chunk.len());
            for s in chunk {
                w.len(s.args.len());
                for a in &s.args {
                    w.str(a);
                }
                w.opt_hashes(s.exclude.as_deref());
            }
            out.push(w.buf);
        }
        let mut w = Writer::with_capacity(self.class_map.len() * 14 + 16);
        w.u8(3);
        w.len(self.generic.len());
        w.map_u64_ids(&self.class_map);
        out.push(w.buf);

        let mut w = Writer::with_capacity(self.id_map.len() * 14 + self.always.len() * 3 + 16);
        w.u8(4);
        w.len(self.generic.len());
        w.map_u64_ids(&self.id_map);
        w.ids(&self.always);
        out.push(w.buf);

        let mut w = Writer::with_capacity(self.specific.len() * 14 + 16);
        w.u8(5);
        w.len(self.rules.len());
        w.map_u64_ids(&self.specific);
        out.push(w.buf);

        let mut w = Writer::with_capacity(self.exceptions.len() * 40 + self.global_exceptions.len() * 8 + 16);
        w.u8(6);
        w.map_u64_strs(&self.exceptions);
        w.hash_set(&self.global_exceptions);
        out.push(w.buf);

        let mut w = Writer::with_capacity(self.scriptlet_hosts.len() * 14 + 64);
        w.u8(7);
        w.len(self.scriptlets.len());
        w.map_u64_ids(&self.scriptlet_hosts);
        w.ids(&self.generic_scriptlets);
        w.map_u64_strs(&self.scriptlet_exceptions);
        out.push(w.buf);
        // `seen` only deduplicates while building and is not stored.
    }

    pub(crate) fn decode_part(b: &[u8]) -> Option<CosPart> {
        let mut r = Reader::new(b);
        let part = match r.u8()? {
            0 => {
                let n = r.len(2)?;
                let mut v = Vec::with_capacity(n);
                for _ in 0..n {
                    v.push(Generic { sel: r.boxed()?, exclude: r.opt_hashes()? });
                }
                Piece::Generic(v)
            }
            1 => {
                let n = r.len(4)?;
                let mut v = Vec::with_capacity(n);
                for _ in 0..n {
                    let kind = match r.u8()? {
                        0 => Kind::Hide,
                        1 => Kind::Style,
                        2 => Kind::Procedural,
                        _ => return None,
                    };
                    v.push(Rule { kind, sel: r.boxed()?, css: r.opt_boxed()?, exclude: r.opt_hashes()?, path: r.opt_boxed()? });
                }
                Piece::Rules(v)
            }
            2 => {
                let n = r.len(2)?;
                let mut v = Vec::with_capacity(n);
                for _ in 0..n {
                    let c = r.len(1)?;
                    if c == 0 {
                        return None; // args[0] is the scriptlet name
                    }
                    let mut args = Vec::with_capacity(c);
                    for _ in 0..c {
                        args.push(r.boxed()?);
                    }
                    v.push(Scriptlet { args, exclude: r.opt_hashes()? });
                }
                Piece::Scriptlets(v)
            }
            3 => {
                let n = usize::try_from(r.var()?).ok()?;
                Piece::ClassMap(n, r.map_u64_ids(n)?)
            }
            4 => {
                let n = usize::try_from(r.var()?).ok()?;
                Piece::IdMap(n, r.map_u64_ids(n)?, r.ids_below(n)?)
            }
            5 => {
                let n = usize::try_from(r.var()?).ok()?;
                Piece::Specific(n, r.map_u64_ids(n)?)
            }
            6 => Piece::Exceptions(r.map_u64_strs()?, r.hash_set()?),
            7 => {
                let n = usize::try_from(r.var()?).ok()?;
                Piece::ScriptletIndex(n, r.map_u64_ids(n)?, r.ids_below(n)?, r.map_u64_strs()?)
            }
            _ => return None,
        };
        if !r.at_end() {
            return None;
        }
        Some(CosPart(part))
    }

    /// Puts decoded parts (in their original order) back together. Index parts
    /// record how many items they refer to, which is checked here.
    pub(crate) fn assemble(parts: Vec<CosPart>) -> Option<CosmeticEngine> {
        let mut e = CosmeticEngine::default();
        let mut seen = [false; 5];
        let mut once = |i: usize| -> Option<()> {
            if std::mem::replace(&mut seen[i], true) {
                None
            } else {
                Some(())
            }
        };
        for CosPart(p) in parts {
            match p {
                Piece::Generic(v) => e.generic.extend(v),
                Piece::Rules(v) => e.rules.extend(v),
                Piece::Scriptlets(v) => e.scriptlets.extend(v),
                Piece::ClassMap(n, m) => {
                    once(0)?;
                    (n == e.generic.len()).then_some(())?;
                    e.class_map = m;
                }
                Piece::IdMap(n, m, always) => {
                    once(1)?;
                    (n == e.generic.len()).then_some(())?;
                    e.id_map = m;
                    e.always = always;
                }
                Piece::Specific(n, m) => {
                    once(2)?;
                    (n == e.rules.len()).then_some(())?;
                    e.specific = m;
                }
                Piece::Exceptions(m, g) => {
                    once(3)?;
                    e.exceptions = m;
                    e.global_exceptions = g;
                }
                Piece::ScriptletIndex(n, hosts, generic, exc) => {
                    once(4)?;
                    (n == e.scriptlets.len()).then_some(())?;
                    e.scriptlet_hosts = hosts;
                    e.generic_scriptlets = generic;
                    e.scriptlet_exceptions = exc;
                }
            }
        }
        seen.iter().all(|s| *s).then_some(e)
    }
}
