//! Arivna Shield — a from-scratch content blocker compatible with uBlock Origin,
//! EasyList and AdGuard filter syntax, built for Android WebView.
//!
//! * Network: token-indexed matcher with hostname fast path, `$important`,
//!   exceptions, `$badfilter`, `$redirect`/`$redirect-rule`, `$removeparam`,
//!   `$domain`/`$to`/`$denyallow`, party, type and method options.
//! * Cosmetic: lazy generic hiding by class/id, site-specific hiding, `:style()`,
//!   procedural selectors and uBO-compatible scriptlets.

pub mod codec;
pub mod cosmetic;
pub mod engine;
pub mod hash;
pub mod json;
pub mod network;
pub mod pattern;
pub mod preprocess;
pub mod psl;
pub mod request;
pub mod resources;
pub mod url;

mod jni;

pub use engine::{Engine, ListInput};
pub use request::ty;

static SCRIPTLETS_JS: &str = include_str!("js/scriptlets.js");
static CONTENT_JS: &str = include_str!("js/content.js");

/// Document-start script: scriptlet library + cosmetic runtime.
pub fn content_script() -> String {
    let mut s = String::with_capacity(SCRIPTLETS_JS.len() + CONTENT_JS.len() + 64);
    s.push_str("(function(){'use strict';\n");
    s.push_str(SCRIPTLETS_JS);
    s.push('\n');
    s.push_str(CONTENT_JS);
    s.push_str("\n})();");
    s
}
