//! Minimal URL splitting — just enough to find the scheme and hostname quickly.

#[derive(Clone, Copy, Debug)]
pub struct Parts {
    pub scheme_end: usize,
    pub host_start: usize,
    pub host_end: usize,
}

pub fn parse(url: &str) -> Option<Parts> {
    let scheme_end = url.find("://")?;
    if scheme_end == 0 || scheme_end > 16 {
        return None;
    }
    let auth_start = scheme_end + 3;
    let auth_end = url[auth_start..]
        .find(['/', '?', '#'])
        .map(|i| auth_start + i)
        .unwrap_or(url.len());
    let mut host_start = auth_start;
    if let Some(at) = url[auth_start..auth_end].rfind('@') {
        host_start = auth_start + at + 1;
    }
    let auth = &url[host_start..auth_end];
    let mut host_end = auth_end;
    if auth.starts_with('[') {
        if let Some(i) = auth.find(']') {
            host_end = host_start + i + 1;
        }
    } else if let Some(i) = auth.rfind(':') {
        host_end = host_start + i;
    }
    while host_end > host_start && url.as_bytes()[host_end - 1] == b'.' {
        host_end -= 1;
    }
    Some(Parts { scheme_end, host_start, host_end })
}

pub fn host(url: &str) -> &str {
    match parse(url) {
        Some(p) => &url[p.host_start..p.host_end],
        None => "",
    }
}

pub fn is_token_char(c: u8) -> bool {
    c.is_ascii_alphanumeric() || c == b'%'
}

/// ABP "separator" class used by `^`.
#[inline]
pub fn is_separator(c: u8) -> bool {
    !(c.is_ascii_alphanumeric() || c == b'_' || c == b'-' || c == b'.' || c == b'%')
}
