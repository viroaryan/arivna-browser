//! Public Suffix List lookups (registrable domain / eTLD+1), used for
//! first-party vs third-party decisions and `example.*` entity filters.

use crate::hash::{hash_str, FastSet};
use std::sync::OnceLock;

static DATA: &str = include_str!("../data/public_suffix_list.dat");
static PSL: OnceLock<Psl> = OnceLock::new();

pub struct Psl {
    rules: FastSet<u64>,
    wild: FastSet<u64>,
    except: FastSet<u64>,
}

pub fn psl() -> &'static Psl {
    PSL.get_or_init(|| Psl::parse(DATA))
}

pub fn is_ip(host: &str) -> bool {
    if host.starts_with('[') || host.contains(':') {
        return true;
    }
    !host.is_empty() && host.bytes().all(|c| c.is_ascii_digit() || c == b'.')
}

impl Psl {
    fn parse(text: &str) -> Psl {
        let mut rules = FastSet::default();
        let mut wild = FastSet::default();
        let mut except = FastSet::default();
        for line in text.lines() {
            let l = line.trim();
            if l.is_empty() || l.starts_with("//") {
                continue;
            }
            let rule = l.split_whitespace().next().unwrap_or("").to_lowercase();
            if let Some(r) = rule.strip_prefix('!') {
                except.insert(hash_str(r));
            } else if let Some(r) = rule.strip_prefix("*.") {
                wild.insert(hash_str(r));
            } else {
                rules.insert(hash_str(&rule));
            }
        }
        Psl { rules, wild, except }
    }

    /// Byte offset where the public suffix begins inside `host`.
    pub fn suffix_start(&self, host: &str) -> usize {
        let mut starts: Vec<usize> = Vec::with_capacity(6);
        starts.push(0);
        for (i, c) in host.bytes().enumerate() {
            if c == b'.' {
                starts.push(i + 1);
            }
        }
        for (k, &s) in starts.iter().enumerate() {
            let h = hash_str(&host[s..]);
            if self.except.contains(&h) {
                return starts.get(k + 1).copied().unwrap_or(host.len());
            }
            if self.rules.contains(&h) {
                return s;
            }
            if let Some(&next) = starts.get(k + 1) {
                if self.wild.contains(&hash_str(&host[next..])) {
                    return s;
                }
            }
        }
        *starts.last().unwrap()
    }

    /// Registrable domain (eTLD+1) of a hostname.
    pub fn domain<'a>(&self, host: &'a str) -> &'a str {
        if host.is_empty() || is_ip(host) {
            return host;
        }
        let s = self.suffix_start(host);
        if s == 0 {
            return host;
        }
        let before = &host[..s - 1];
        match before.rfind('.') {
            Some(i) => &host[i + 1..],
            None => host,
        }
    }

    /// The hostname with its public suffix removed: `www.google.co.uk` -> `www.google`.
    pub fn without_suffix<'a>(&self, host: &'a str) -> &'a str {
        if host.is_empty() || is_ip(host) {
            return "";
        }
        let s = self.suffix_start(host);
        if s == 0 {
            ""
        } else {
            &host[..s - 1]
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn domains() {
        let p = psl();
        assert_eq!(p.domain("www.google.com"), "google.com");
        assert_eq!(p.domain("a.b.bbc.co.uk"), "bbc.co.uk");
        assert_eq!(p.domain("foo.github.io"), "foo.github.io");
        assert_eq!(p.domain("localhost"), "localhost");
        assert_eq!(p.domain("192.168.1.1"), "192.168.1.1");
        assert_eq!(p.without_suffix("www.google.co.uk"), "www.google");
    }
}
