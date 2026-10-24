//! Compact binary encoding for a built [`crate::Engine`], so the app can load a
//! cached engine instead of re-parsing every filter list at startup.
//!
//! File layout (all integers little-endian):
//!
//! ```text
//! magic    8 bytes  "ARVSHLD\0"
//! version  u32      FORMAT_VERSION
//! key      varint length + UTF-8 bytes (caller-defined cache key)
//! length   u64      payload length
//! checksum u64      word-wise hash of the payload
//! payload  ...      Engine::encode
//! ```
//!
//! Inside the payload, lengths and ids are LEB128 varints, hashes are fixed u64,
//! strings are a varint length followed by UTF-8 bytes. The reader never trusts
//! the input: every read is bounds-checked and decoding returns `None` on any
//! inconsistency instead of panicking.

use crate::hash::{FastMap, FastSet};
use std::sync::atomic::{AtomicUsize, Ordering};

pub const MAGIC: &[u8; 8] = b"ARVSHLD\0";
/// Bump whenever the encoding of any engine structure changes.
pub const FORMAT_VERSION: u32 = 1;
/// Items per independently decodable chunk of a large vector.
pub const CHUNK: usize = 4096;

pub struct Writer {
    pub buf: Vec<u8>,
}

impl Writer {
    pub fn with_capacity(n: usize) -> Writer {
        Writer { buf: Vec::with_capacity(n) }
    }

    #[inline]
    pub fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }

    #[inline]
    pub fn bool(&mut self, v: bool) {
        self.buf.push(v as u8);
    }

    #[inline]
    pub fn var(&mut self, mut v: u64) {
        while v >= 0x80 {
            self.buf.push((v as u8) | 0x80);
            v >>= 7;
        }
        self.buf.push(v as u8);
    }

    #[inline]
    pub fn u32(&mut self, v: u32) {
        self.var(v as u64)
    }

    #[inline]
    pub fn i32(&mut self, v: i32) {
        // zig-zag so small negatives stay short
        self.var(((v << 1) ^ (v >> 31)) as u32 as u64)
    }

    #[inline]
    pub fn u64(&mut self, v: u64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    #[inline]
    pub fn len(&mut self, n: usize) {
        self.var(n as u64)
    }

    pub fn bytes(&mut self, b: &[u8]) {
        self.len(b.len());
        self.buf.extend_from_slice(b);
    }

    pub fn str(&mut self, s: &str) {
        self.bytes(s.as_bytes())
    }

    pub fn opt_str(&mut self, s: Option<&str>) {
        match s {
            Some(s) => {
                self.u8(1);
                self.str(s);
            }
            None => self.u8(0),
        }
    }

    pub fn ids(&mut self, v: &[u32]) {
        self.len(v.len());
        for &x in v {
            self.u32(x);
        }
    }

    pub fn hashes(&mut self, v: &[u64]) {
        self.len(v.len());
        for &x in v {
            self.u64(x);
        }
    }

    pub fn opt_hashes(&mut self, v: Option<&[u64]>) {
        match v {
            Some(v) => {
                self.u8(1);
                self.hashes(v);
            }
            None => self.u8(0),
        }
    }

    pub fn hash_set(&mut self, s: &FastSet<u64>) {
        self.len(s.len());
        for &h in s {
            self.u64(h);
        }
    }

    pub fn map_u64_ids(&mut self, m: &FastMap<u64, Vec<u32>>) {
        self.len(m.len());
        for (k, v) in m {
            self.u64(*k);
            self.ids(v);
        }
    }

    pub fn map_u32_ids(&mut self, m: &FastMap<u32, Vec<u32>>) {
        self.len(m.len());
        for (k, v) in m {
            self.buf.extend_from_slice(&k.to_le_bytes());
            self.ids(v);
        }
    }

    pub fn map_u64_strs(&mut self, m: &FastMap<u64, Vec<Box<str>>>) {
        self.len(m.len());
        for (k, v) in m {
            self.u64(*k);
            self.len(v.len());
            for s in v {
                self.str(s);
            }
        }
    }
}

pub struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(b: &'a [u8]) -> Reader<'a> {
        Reader { b, pos: 0 }
    }

    pub fn at_end(&self) -> bool {
        self.pos == self.b.len()
    }

    #[inline]
    fn remaining(&self) -> usize {
        self.b.len() - self.pos
    }

    #[inline]
    pub fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if n > self.remaining() {
            return None;
        }
        let s = &self.b[self.pos..self.pos + n];
        self.pos += n;
        Some(s)
    }

    #[inline]
    pub fn u8(&mut self) -> Option<u8> {
        let v = *self.b.get(self.pos)?;
        self.pos += 1;
        Some(v)
    }

    #[inline]
    pub fn bool(&mut self) -> Option<bool> {
        match self.u8()? {
            0 => Some(false),
            1 => Some(true),
            _ => None,
        }
    }

    #[inline]
    pub fn var(&mut self) -> Option<u64> {
        let mut v = 0u64;
        let mut shift = 0;
        loop {
            let c = self.u8()?;
            if shift == 63 && c > 1 {
                return None;
            }
            v |= ((c & 0x7f) as u64) << shift;
            if c & 0x80 == 0 {
                return Some(v);
            }
            shift += 7;
            if shift > 63 {
                return None;
            }
        }
    }

    #[inline]
    pub fn u32(&mut self) -> Option<u32> {
        u32::try_from(self.var()?).ok()
    }

    #[inline]
    pub fn i32(&mut self) -> Option<i32> {
        let z = self.u32()?;
        Some(((z >> 1) as i32) ^ -((z & 1) as i32))
    }

    #[inline]
    pub fn u64(&mut self) -> Option<u64> {
        let s = self.take(8)?;
        Some(u64::from_le_bytes(s.try_into().ok()?))
    }

    #[inline]
    fn raw_u32(&mut self) -> Option<u32> {
        let s = self.take(4)?;
        Some(u32::from_le_bytes(s.try_into().ok()?))
    }

    /// A collection length. Every element occupies at least `min_size` bytes,
    /// so lengths that can't fit in the remaining input are rejected before
    /// anything is allocated.
    #[inline]
    pub fn len(&mut self, min_size: usize) -> Option<usize> {
        let n = usize::try_from(self.var()?).ok()?;
        if n.checked_mul(min_size.max(1))? > self.remaining() {
            return None;
        }
        Some(n)
    }

    #[inline]
    pub fn bytes(&mut self) -> Option<&'a [u8]> {
        let n = self.len(1)?;
        self.take(n)
    }

    #[inline]
    pub fn str(&mut self) -> Option<&'a str> {
        std::str::from_utf8(self.bytes()?).ok()
    }

    #[inline]
    pub fn boxed(&mut self) -> Option<Box<str>> {
        Some(self.str()?.into())
    }

    pub fn opt_boxed(&mut self) -> Option<Option<Box<str>>> {
        Some(if self.bool()? { Some(self.boxed()?) } else { None })
    }

    pub fn ids(&mut self) -> Option<Vec<u32>> {
        let n = self.len(1)?;
        let mut v = Vec::with_capacity(n);
        for _ in 0..n {
            v.push(self.u32()?);
        }
        Some(v)
    }

    /// Ids that index into a collection of `bound` elements.
    pub fn ids_below(&mut self, bound: usize) -> Option<Vec<u32>> {
        let v = self.ids()?;
        if v.iter().any(|&i| i as usize >= bound) {
            return None;
        }
        Some(v)
    }

    pub fn hashes(&mut self) -> Option<Box<[u64]>> {
        let n = self.len(8)?;
        let s = self.take(n * 8)?;
        Some(s.chunks_exact(8).map(|c| u64::from_le_bytes([c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]])).collect())
    }

    pub fn opt_hashes(&mut self) -> Option<Option<Box<[u64]>>> {
        Some(if self.bool()? { Some(self.hashes()?) } else { None })
    }

    pub fn hash_set(&mut self) -> Option<FastSet<u64>> {
        let n = self.len(8)?;
        let mut s = FastSet::with_capacity_and_hasher(n, Default::default());
        for _ in 0..n {
            s.insert(self.u64()?);
        }
        Some(s)
    }

    pub fn map_u64_ids(&mut self, bound: usize) -> Option<FastMap<u64, Vec<u32>>> {
        let n = self.len(9)?;
        let mut m = FastMap::with_capacity_and_hasher(n, Default::default());
        for _ in 0..n {
            let k = self.u64()?;
            m.insert(k, self.ids_below(bound)?);
        }
        Some(m)
    }

    pub fn map_u32_ids(&mut self, bound: usize) -> Option<FastMap<u32, Vec<u32>>> {
        let n = self.len(5)?;
        let mut m = FastMap::with_capacity_and_hasher(n, Default::default());
        for _ in 0..n {
            let k = self.raw_u32()?;
            m.insert(k, self.ids_below(bound)?);
        }
        Some(m)
    }

    pub fn map_u64_strs(&mut self) -> Option<FastMap<u64, Vec<Box<str>>>> {
        let n = self.len(9)?;
        let mut m = FastMap::with_capacity_and_hasher(n, Default::default());
        for _ in 0..n {
            let k = self.u64()?;
            let c = self.len(1)?;
            let mut v = Vec::with_capacity(c);
            for _ in 0..c {
                v.push(self.boxed()?);
            }
            m.insert(k, v);
        }
        Some(m)
    }
}

/// Decodes `parts` on up to `available_parallelism` threads, largest first, and
/// returns the results in their original order. Decoding is dominated by small
/// allocations, which scale well across threads. None if any part fails.
pub fn par_decode<T: Send>(parts: &[&[u8]], decode: impl Fn(usize, &[u8]) -> Option<T> + Sync) -> Option<Vec<T>> {
    let threads = std::thread::available_parallelism().map_or(1, |n| n.get()).min(parts.len()).min(8);
    if threads <= 1 {
        return parts.iter().enumerate().map(|(i, p)| decode(i, p)).collect();
    }
    let mut order: Vec<usize> = (0..parts.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(parts[i].len()));
    let next = AtomicUsize::new(0);
    let work = || {
        let mut done = Vec::new();
        loop {
            let j = next.fetch_add(1, Ordering::Relaxed);
            let Some(&i) = order.get(j) else { break };
            match decode(i, parts[i]) {
                Some(v) => done.push((i, v)),
                None => {
                    // Make every worker stop early.
                    next.store(usize::MAX / 2, Ordering::Relaxed);
                    return None;
                }
            }
        }
        Some(done)
    };
    let mut slots: Vec<Option<T>> = (0..parts.len()).map(|_| None).collect();
    let ok = std::thread::scope(|s| {
        let handles: Vec<_> = (1..threads).map(|_| s.spawn(&work)).collect();
        let mut ok = true;
        for (i, v) in work().unwrap_or_else(|| {
            ok = false;
            Vec::new()
        }) {
            slots[i] = Some(v);
        }
        for h in handles {
            match h.join() {
                Ok(Some(done)) => {
                    for (i, v) in done {
                        slots[i] = Some(v);
                    }
                }
                _ => ok = false,
            }
        }
        ok
    });
    if !ok {
        return None;
    }
    slots.into_iter().collect()
}

/// Fast integrity hash (8 bytes per step); detects truncation and bit rot,
/// not tampering.
pub fn checksum(data: &[u8]) -> u64 {
    const K: u64 = 0x9E37_79B9_7F4A_7C15;
    let mut h = 0xcbf2_9ce4_8422_2325u64 ^ (data.len() as u64).wrapping_mul(K);
    let mut chunks = data.chunks_exact(8);
    for c in &mut chunks {
        let w = u64::from_le_bytes([c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]]);
        h = (h ^ w).wrapping_mul(K).rotate_left(23);
    }
    for &b in chunks.remainder() {
        h = (h ^ b as u64).wrapping_mul(K);
    }
    h ^ (h >> 29)
}

/// Wraps an encoded payload in the cache container.
pub fn wrap(key: &str, payload: &[u8]) -> Vec<u8> {
    let mut w = Writer::with_capacity(payload.len() + key.len() + 40);
    w.buf.extend_from_slice(MAGIC);
    w.buf.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    w.str(key);
    w.u64(payload.len() as u64);
    w.u64(checksum(payload));
    w.buf.extend_from_slice(payload);
    w.buf
}

/// Returns the payload when the header, key and checksum all match.
pub fn unwrap<'a>(data: &'a [u8], key: &str) -> Option<&'a [u8]> {
    let mut r = Reader::new(data);
    if r.take(8)? != MAGIC {
        return None;
    }
    if u32::from_le_bytes(r.take(4)?.try_into().ok()?) != FORMAT_VERSION {
        return None;
    }
    if r.str()? != key {
        return None;
    }
    let len = usize::try_from(r.u64()?).ok()?;
    let sum = r.u64()?;
    let payload = r.take(len)?;
    if !r.at_end() || checksum(payload) != sum {
        return None;
    }
    Some(payload)
}

/// Reads the cache key stored in a container without validating the payload.
pub fn stored_key(data: &[u8]) -> Option<&str> {
    let mut r = Reader::new(data);
    if r.take(8)? != MAGIC {
        return None;
    }
    r.take(4)?;
    r.str()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varints_and_zigzag() {
        let mut w = Writer::with_capacity(64);
        for v in [0u64, 1, 127, 128, 300, u32::MAX as u64, u64::MAX] {
            w.var(v);
        }
        for v in [0i32, -1, 1, -1000, i32::MIN, i32::MAX] {
            w.i32(v);
        }
        let mut r = Reader::new(&w.buf);
        for v in [0u64, 1, 127, 128, 300, u32::MAX as u64, u64::MAX] {
            assert_eq!(r.var(), Some(v));
        }
        for v in [0i32, -1, 1, -1000, i32::MIN, i32::MAX] {
            assert_eq!(r.i32(), Some(v));
        }
        assert!(r.at_end());
        assert_eq!(r.u8(), None);
    }

    #[test]
    fn container_rejects_bad_input() {
        let c = wrap("k1", b"hello world, payload");
        assert_eq!(unwrap(&c, "k1"), Some(&b"hello world, payload"[..]));
        assert_eq!(stored_key(&c), Some("k1"));
        assert_eq!(unwrap(&c, "k2"), None);
        assert_eq!(unwrap(&c[..c.len() - 1], "k1"), None);
        let mut bad = c.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert_eq!(unwrap(&bad, "k1"), None);
        let mut long = c.clone();
        long.push(0);
        assert_eq!(unwrap(&long, "k1"), None);
        assert_eq!(unwrap(b"", "k1"), None);
    }

    #[test]
    fn lengths_are_bounded_by_input() {
        let mut w = Writer::with_capacity(16);
        w.var(1 << 40);
        assert!(Reader::new(&w.buf).ids().is_none());
        assert!(Reader::new(&w.buf).hashes().is_none());
    }
}
