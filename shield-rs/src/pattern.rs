//! ABP/uBO URL pattern matching: `*` wildcards, `^` separators, `|` and `||` anchors, `/regex/`.

use crate::url::is_separator;
// Filters match URLs, which are ASCII once percent-encoded. Byte regexes with Unicode off compile
// 10-100x faster (`w{30,}` is a few states instead of a huge Unicode automaton).
use regex::bytes::{Regex, RegexBuilder};
use std::sync::OnceLock;

#[derive(Clone)]
pub enum Pattern {
    /// Empty pattern: matches every URL (constraints come from options).
    Any,
    Plain { text: Box<str>, left: bool, right: bool },
    Glob { text: Box<[u8]>, right: bool },
    /// `||host...` — `full[..host_len]` is the leading hostname part, the rest what follows it.
    Host { full: Box<[u8]>, host_len: usize, right: bool, simple: bool },
    Regex { src: Box<str>, case: bool, re: OnceLock<Option<Regex>> },
}

/// Matches `p` against the start of `t`. `*` matches any run, `^` matches one
/// separator character or the end of the input. When `prefix` is true the
/// pattern only has to match a prefix of `t`.
pub fn glob(p: &[u8], t: &[u8], prefix: bool) -> bool {
    let (mut pi, mut ti) = (0usize, 0usize);
    let mut star: Option<(usize, usize)> = None;
    loop {
        if pi < p.len() {
            match p[pi] {
                b'*' => {
                    star = Some((pi, ti));
                    pi += 1;
                    continue;
                }
                b'^' => {
                    if ti < t.len() && is_separator(t[ti]) {
                        pi += 1;
                        ti += 1;
                        continue;
                    }
                    if ti == t.len() {
                        pi += 1;
                        continue;
                    }
                }
                c => {
                    if ti < t.len() && t[ti] == c {
                        pi += 1;
                        ti += 1;
                        continue;
                    }
                }
            }
        } else if prefix || ti == t.len() {
            return true;
        }
        match star {
            Some((sp, st)) if st < t.len() => {
                star = Some((sp, st + 1));
                pi = sp + 1;
                ti = st + 1;
            }
            _ => return false,
        }
    }
}

impl Pattern {
    pub fn regex(src: &str, case: bool) -> Pattern {
        Pattern::Regex { src: src.into(), case, re: OnceLock::new() }
    }

    /// `url` is lowercased unless the filter is `match-case`.
    pub fn matches(&self, url: &str, host_start: usize, host_end: usize) -> bool {
        match self {
            Pattern::Any => true,
            Pattern::Plain { text, left, right } => match (left, right) {
                (true, true) => url == &**text,
                (true, false) => url.starts_with(&**text),
                (false, true) => url.ends_with(&**text),
                (false, false) => url.contains(&**text),
            },
            Pattern::Glob { text, right } => glob(text, url.as_bytes(), !right),
            Pattern::Host { full, host_len, right, simple } => {
                let b = url.as_bytes();
                if *simple {
                    let (host, rest) = full.split_at((*host_len).min(full.len()));
                    let h = &b[host_start..host_end];
                    let ok = h == host || (h.len() > host.len() && h.ends_with(host) && h[h.len() - host.len() - 1] == b'.');
                    return ok && glob(rest, &b[host_end..], !right);
                }
                // General case: the pattern may start at any label boundary of the hostname.
                let mut p = host_start;
                while p < host_end {
                    if (p == host_start || b[p - 1] == b'.') && glob(full, &b[p..], !right) {
                        return true;
                    }
                    p += 1;
                }
                false
            }
            Pattern::Regex { src, case, re } => {
                let re = re.get_or_init(|| {
                    RegexBuilder::new(src).unicode(false).case_insensitive(!case).size_limit(2 << 20).dfa_size_limit(2 << 20).build().ok()
                });
                match re {
                    Some(r) => r.is_match(url.as_bytes()),
                    None => false,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn globbing() {
        assert!(glob(b"*/ads/*", b"https://x.com/ads/1.js", false));
        assert!(glob(b"*/ads^", b"https://x.com/ads", false));
        assert!(glob(b"*/ads^", b"https://x.com/ads?x", true));
        assert!(!glob(b"*/ads^", b"https://x.com/adsx", true));
        assert!(glob(b"*banner*.gif", b"http://a.com/banner_1.gif", false));
        assert!(glob(b"^", b"", false));
    }
}

// ---------------------------------------------------------------------------
// Binary cache (see codec.rs). Regexes are stored as source and compiled lazily, as when built.

use crate::codec::{Reader, Writer};

impl Pattern {
    pub(crate) fn encode(&self, w: &mut Writer) {
        match self {
            Pattern::Any => w.u8(0),
            Pattern::Plain { text, left, right } => {
                w.u8(1);
                w.str(text);
                w.u8(*left as u8 | (*right as u8) << 1);
            }
            Pattern::Glob { text, right } => {
                w.u8(2);
                w.bytes(text);
                w.bool(*right);
            }
            Pattern::Host { full, host_len, right, simple } => {
                w.u8(3);
                w.bytes(full);
                w.len(*host_len);
                w.u8(*right as u8 | (*simple as u8) << 1);
            }
            Pattern::Regex { src, case, .. } => {
                w.u8(4);
                w.str(src);
                w.bool(*case);
            }
        }
    }

    pub(crate) fn decode(r: &mut Reader) -> Option<Pattern> {
        Some(match r.u8()? {
            0 => Pattern::Any,
            1 => {
                let text = r.boxed()?;
                let b = r.u8()?;
                if b > 3 {
                    return None;
                }
                Pattern::Plain { text, left: b & 1 != 0, right: b & 2 != 0 }
            }
            2 => Pattern::Glob { text: r.bytes()?.into(), right: r.bool()? },
            3 => {
                let full: Box<[u8]> = r.bytes()?.into();
                let host_len = usize::try_from(r.var()?).ok()?;
                let b = r.u8()?;
                if b > 3 || host_len > full.len() {
                    return None;
                }
                Pattern::Host { full, host_len, right: b & 1 != 0, simple: b & 2 != 0 }
            }
            4 => {
                let src = r.str()?;
                Pattern::regex(src, r.bool()?)
            }
            _ => return None,
        })
    }
}
