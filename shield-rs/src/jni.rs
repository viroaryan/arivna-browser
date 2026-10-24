//! JNI entry points for `com.arivna.browser.ShieldNative`.
//! Every call is panic-safe; strings leave Rust as UTF-8 byte arrays.

use crate::engine::{Engine, ListInput};
use jni_sys::{jboolean, jbooleanArray, jbyte, jbyteArray, jclass, jint, jlong, jobjectArray, jsize, jstring, JNIEnv};
use std::ffi::CStr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

unsafe fn get_string(env: *mut JNIEnv, s: jstring) -> String {
    if s.is_null() {
        return String::new();
    }
    let p = ((**env).GetStringUTFChars.unwrap())(env, s, null_mut());
    if p.is_null() {
        return String::new();
    }
    let out = CStr::from_ptr(p).to_string_lossy().into_owned();
    ((**env).ReleaseStringUTFChars.unwrap())(env, s, p);
    out
}

unsafe fn get_bytes(env: *mut JNIEnv, a: jbyteArray) -> Vec<u8> {
    if a.is_null() {
        return Vec::new();
    }
    let len = ((**env).GetArrayLength.unwrap())(env, a) as usize;
    let mut buf = vec![0u8; len];
    ((**env).GetByteArrayRegion.unwrap())(env, a, 0, len as jsize, buf.as_mut_ptr() as *mut jbyte);
    buf
}

unsafe fn new_bytes(env: *mut JNIEnv, data: &[u8]) -> jbyteArray {
    let a = ((**env).NewByteArray.unwrap())(env, data.len() as jsize);
    if !a.is_null() && !data.is_empty() {
        ((**env).SetByteArrayRegion.unwrap())(env, a, 0, data.len() as jsize, data.as_ptr() as *const jbyte);
    }
    a
}

unsafe fn new_string(env: *mut JNIEnv, s: &str) -> jstring {
    let mut c = s.replace('\0', "").into_bytes();
    c.push(0);
    ((**env).NewStringUTF.unwrap())(env, c.as_ptr() as *const _)
}

unsafe fn engine<'a>(ptr: jlong) -> Option<&'a Engine> {
    if ptr == 0 {
        None
    } else {
        Some(&*(ptr as *const Engine))
    }
}

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_create(
    env: *mut JNIEnv,
    _c: jclass,
    lists: jobjectArray,
    trusted: jbooleanArray,
) -> jlong {
    guard(0, || {
        let n = ((**env).GetArrayLength.unwrap())(env, lists) as usize;
        let mut flags = vec![0 as jboolean; n];
        if !trusted.is_null() && n > 0 {
            ((**env).GetBooleanArrayRegion.unwrap())(env, trusted, 0, n as jsize, flags.as_mut_ptr());
        }
        let mut texts = Vec::with_capacity(n);
        for i in 0..n {
            let el = ((**env).GetObjectArrayElement.unwrap())(env, lists, i as jsize);
            let bytes = get_bytes(env, el as jbyteArray);
            ((**env).DeleteLocalRef.unwrap())(env, el);
            texts.push(String::from_utf8_lossy(&bytes).into_owned());
        }
        let inputs: Vec<ListInput> = texts.iter().zip(flags.iter()).map(|(t, f)| ListInput { text: t, trusted: *f != 0 }).collect();
        Box::into_raw(Box::new(Engine::new(&inputs))) as jlong
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_destroy(_env: *mut JNIEnv, _c: jclass, ptr: jlong) {
    if ptr != 0 {
        let _ = catch_unwind(|| drop(Box::from_raw(ptr as *mut Engine)));
    }
}

/// Returns null when nothing matched, otherwise `code\tfilter\tredirect`.
/// code bits: 1 blocked, 2 important, 4 exception, 8 page allowlisted by filters.
#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_check(
    env: *mut JNIEnv,
    _c: jclass,
    ptr: jlong,
    url: jstring,
    source: jstring,
    request_type: jint,
    method: jint,
) -> jbyteArray {
    guard(null_mut(), || {
        let Some(e) = engine(ptr) else { return null_mut() };
        let u = get_string(env, url);
        let s = get_string(env, source);
        let r = e.check(&u, &s, request_type as u32, method as u8);
        if !r.blocked && !r.exception && !r.page_allowed {
            return null_mut();
        }
        let code = (r.blocked as u32) | ((r.important as u32) << 1) | ((r.exception as u32) << 2) | ((r.page_allowed as u32) << 3);
        let out = format!("{}\t{}\t{}", code, r.filter.unwrap_or_default(), r.redirect.unwrap_or_default());
        new_bytes(env, out.as_bytes())
    })
}

/// `mime\n` followed by the body of a redirect resource.
#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_resource(env: *mut JNIEnv, _c: jclass, name: jstring) -> jbyteArray {
    guard(null_mut(), || {
        let n = get_string(env, name);
        match crate::resources::get(&n) {
            Some(r) => {
                let mut v = Vec::with_capacity(r.body.len() + 32);
                v.extend_from_slice(r.mime.as_bytes());
                v.push(b'\n');
                v.extend_from_slice(&r.body);
                new_bytes(env, &v)
            }
            None => null_mut(),
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_cleanUrl(
    env: *mut JNIEnv,
    _c: jclass,
    ptr: jlong,
    url: jstring,
    source: jstring,
) -> jstring {
    guard(null_mut(), || {
        let Some(e) = engine(ptr) else { return null_mut() };
        match e.clean_url(&get_string(env, url), &get_string(env, source)) {
            Some(u) => new_string(env, &u),
            None => null_mut(),
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_cosmetic(env: *mut JNIEnv, _c: jclass, ptr: jlong, url: jstring) -> jbyteArray {
    guard(null_mut(), || {
        let Some(e) = engine(ptr) else { return new_bytes(env, b"{\"off\":true}") };
        new_bytes(env, e.cosmetic_json(&get_string(env, url)).as_bytes())
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_hidden(
    env: *mut JNIEnv,
    _c: jclass,
    ptr: jlong,
    url: jstring,
    classes: jstring,
    ids: jstring,
) -> jbyteArray {
    guard(null_mut(), || {
        let Some(e) = engine(ptr) else { return new_bytes(env, b"[]") };
        let json = e.hidden_json(&get_string(env, url), &get_string(env, classes), &get_string(env, ids));
        new_bytes(env, json.as_bytes())
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_stats(env: *mut JNIEnv, _c: jclass, ptr: jlong) -> jbyteArray {
    guard(null_mut(), || {
        let Some(e) = engine(ptr) else { return new_bytes(env, b"{}") };
        new_bytes(env, e.stats_json().as_bytes())
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_contentScript(env: *mut JNIEnv, _c: jclass) -> jbyteArray {
    guard(null_mut(), || new_bytes(env, crate::content_script().as_bytes()))
}

/// Loads an engine from a cache file written by `saveCache`. Returns 0 when the
/// file is missing, the key differs, or the data is damaged.
#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_createFromCache(env: *mut JNIEnv, _c: jclass, path: jstring, key: jstring) -> jlong {
    guard(0, || {
        let p = get_string(env, path);
        let k = get_string(env, key);
        match Engine::load_cache(std::path::Path::new(&p), &k) {
            Some(e) => Box::into_raw(Box::new(e)) as jlong,
            None => 0,
        }
    })
}

/// Writes an engine to a cache file atomically. Returns false on failure.
#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_saveCache(
    env: *mut JNIEnv,
    _c: jclass,
    ptr: jlong,
    path: jstring,
    key: jstring,
) -> jboolean {
    guard(0, || {
        let Some(e) = engine(ptr) else { return 0 };
        let p = get_string(env, path);
        let k = get_string(env, key);
        e.save_cache(std::path::Path::new(&p), &k).is_ok() as jboolean
    })
}

/// Stats JSON for an engine loaded with `createFromCache`, so the app can tell cache hits apart.
#[no_mangle]
pub unsafe extern "system" fn Java_com_arivna_browser_ShieldNative_isCached(_env: *mut JNIEnv, _c: jclass, ptr: jlong) -> jboolean {
    guard(0, || engine(ptr).map_or(0, |e| e.stats.cached as jboolean))
}
