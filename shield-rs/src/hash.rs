//! Small, dependency-free hashing helpers used throughout the engine.

use std::collections::{HashMap, HashSet};
use std::hash::{BuildHasherDefault, Hasher};

const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;

#[inline]
pub fn fnv_extend(mut h: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(FNV_PRIME);
    }
    h
}

#[inline]
pub fn hash_bytes(bytes: &[u8]) -> u64 {
    fnv_extend(FNV_OFFSET, bytes)
}

#[inline]
pub fn hash_str(s: &str) -> u64 {
    hash_bytes(s.as_bytes())
}

/// Hash for an entity pattern such as `example.*`.
#[inline]
pub fn hash_entity(name: &str) -> u64 {
    fnv_extend(hash_str(name), b".*")
}

#[inline]
pub fn token_hash(bytes: &[u8]) -> u32 {
    let h = hash_bytes(bytes);
    (h ^ (h >> 32)) as u32
}

/// Hasher for keys that are already well-distributed hashes.
#[derive(Default, Clone, Copy)]
pub struct PreHashed(u64);

impl Hasher for PreHashed {
    #[inline]
    fn finish(&self) -> u64 {
        self.0
    }
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        self.0 = fnv_extend(self.0 ^ FNV_OFFSET, bytes);
    }
    #[inline]
    fn write_u64(&mut self, n: u64) {
        self.0 = (n ^ n.rotate_left(29)).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    }
    #[inline]
    fn write_u32(&mut self, n: u32) {
        self.write_u64(n as u64)
    }
    #[inline]
    fn write_usize(&mut self, n: usize) {
        self.write_u64(n as u64)
    }
}

pub type FastMap<K, V> = HashMap<K, V, BuildHasherDefault<PreHashed>>;
pub type FastSet<K> = HashSet<K, BuildHasherDefault<PreHashed>>;

/// Hashes of `host` and every parent domain: `a.b.com`, `b.com`, `com`.
pub fn suffix_hashes(host: &str, out: &mut Vec<u64>) {
    if host.is_empty() {
        return;
    }
    let mut start = 0;
    loop {
        out.push(hash_str(&host[start..]));
        match host[start..].find('.') {
            Some(i) => start += i + 1,
            None => break,
        }
    }
}
