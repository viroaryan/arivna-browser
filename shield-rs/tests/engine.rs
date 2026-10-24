use arivna_shield::{ty, Engine, ListInput};

fn engine(text: &str) -> Engine {
    Engine::new(&[ListInput { text, trusted: true }])
}

fn blocked(e: &Engine, url: &str, src: &str, t: u32) -> bool {
    e.check(url, src, t, 1).blocked
}

#[test]
fn hostname_and_exceptions() {
    let e = engine("||ads.example.com^\n@@||ads.example.com/allowed/\nexample.net\n");
    assert!(blocked(&e, "https://ads.example.com/x.js", "https://site.com/", ty::SCRIPT));
    assert!(blocked(&e, "https://sub.ads.example.com/x.js", "https://site.com/", ty::SCRIPT));
    assert!(!blocked(&e, "https://ads.example.com/allowed/x.js", "https://site.com/", ty::SCRIPT));
    assert!(!blocked(&e, "https://notads.example.com/x.js", "https://site.com/", ty::SCRIPT));
    assert!(blocked(&e, "https://example.net/a.png", "https://site.com/", ty::IMAGE));
}

#[test]
fn important_beats_exception() {
    let e = engine("||tracker.io^$important\n@@||tracker.io^\n");
    assert!(blocked(&e, "https://tracker.io/t.js", "https://site.com/", ty::SCRIPT));
}

#[test]
fn badfilter_disables() {
    let e = engine("||cdn.site.com/ads/*$script\n||cdn.site.com/ads/*$script,badfilter\n");
    assert!(!blocked(&e, "https://cdn.site.com/ads/a.js", "https://x.com/", ty::SCRIPT));
}

#[test]
fn options_party_type_domain() {
    let e = engine("/banner/*$image,third-party,domain=news.com|~sports.news.com\n");
    assert!(blocked(&e, "https://img.cdn.net/banner/1.png", "https://news.com/a", ty::IMAGE));
    assert!(!blocked(&e, "https://img.cdn.net/banner/1.png", "https://sports.news.com/a", ty::IMAGE));
    assert!(!blocked(&e, "https://img.cdn.net/banner/1.png", "https://news.com/a", ty::SCRIPT));
    assert!(!blocked(&e, "https://news.com/banner/1.png", "https://news.com/a", ty::IMAGE));
    assert!(!blocked(&e, "https://img.cdn.net/banner/1.png", "https://other.com/", ty::IMAGE));
}

#[test]
fn entity_and_regex() {
    let e = engine("||adzone.net^$domain=google.*\n/^https:\\/\\/x[0-9]+\\.evil\\.com\\//\n");
    assert!(blocked(&e, "https://adzone.net/a", "https://www.google.co.uk/", ty::SCRIPT));
    assert!(!blocked(&e, "https://adzone.net/a", "https://www.bing.com/", ty::SCRIPT));
    assert!(blocked(&e, "https://x123.evil.com/p", "https://a.com/", ty::XHR));
    assert!(!blocked(&e, "https://y.evil.com/p", "https://a.com/", ty::XHR));
}

#[test]
fn separator_and_anchors() {
    let e = engine("|https://start.com/ad\n.gif|\n/track^\n");
    assert!(blocked(&e, "https://start.com/ad1", "", ty::IMAGE));
    assert!(blocked(&e, "https://a.com/x.gif", "", ty::IMAGE));
    assert!(!blocked(&e, "https://a.com/x.gif?q=1", "", ty::IMAGE));
    assert!(blocked(&e, "https://a.com/track?id=1", "", ty::XHR));
    assert!(!blocked(&e, "https://a.com/tracker", "", ty::XHR));
}

#[test]
fn documents_and_page_exceptions() {
    let e = engine("||malware.bad^\n||ads.net^\n@@||trusted.org^$document\n");
    assert!(blocked(&e, "https://malware.bad/", "https://a.com/", ty::DOCUMENT));
    assert!(!blocked(&e, "https://ads.net/x.js", "https://trusted.org/page", ty::SCRIPT));
    assert!(e.check("https://ads.net/x.js", "https://trusted.org/page", ty::SCRIPT, 1).page_allowed);
}

#[test]
fn redirects() {
    let e = engine("||ga.com/analytics.js$script,redirect=google-analytics_analytics.js\n||pix.com^$image,redirect-rule=1x1.gif\n||pix.com^\n");
    let r = e.check("https://ga.com/analytics.js", "https://a.com/", ty::SCRIPT, 1);
    assert!(r.blocked);
    assert_eq!(r.redirect.as_deref(), Some("google-analytics_analytics.js"));
    let r = e.check("https://pix.com/p.gif", "https://a.com/", ty::IMAGE, 1);
    assert_eq!(r.redirect.as_deref(), Some("1x1.gif"));
    assert!(e.resource("1x1.gif").is_some());
}

#[test]
fn removeparam() {
    let e = engine("$removeparam=/^utm_/\n$removeparam=fbclid\n");
    assert_eq!(e.clean_url("https://a.com/p?id=1&utm_source=x&fbclid=y#top", "").as_deref(), Some("https://a.com/p?id=1#top"));
    assert_eq!(e.clean_url("https://a.com/p?utm_medium=z", "").as_deref(), Some("https://a.com/p"));
    assert_eq!(e.clean_url("https://a.com/p?id=1", ""), None);
}

#[test]
fn cosmetic_rules() {
    let e = engine(
        "##.ad-box\n##div[data-ad]\nnews.com##.promo\nnews.com#@#.ad-box\nnews.com##.x:has-text(Sponsored)\n\
         news.com##.y:style(opacity: 0 !important)\nnews.com##+js(set, adsOn, false)\nnews.com#@#+js(set, adsOn, false)\n\
         other.com##+js(aopr, adblock)\n",
    );
    let c = e.cosmetic("https://www.news.com/a").unwrap();
    assert!(c.hide.contains(&".promo".to_string()));
    assert!(c.hide.contains(&"div[data-ad]".to_string()));
    assert_eq!(c.procedural, vec![".x:has-text(Sponsored)".to_string()]);
    assert_eq!(c.style, vec![(".y".to_string(), "opacity: 0 !important".to_string())]);
    assert!(c.scriptlets.is_empty(), "scriptlet exception must apply");
    assert!(e.hidden("https://www.news.com/a", "ad-box", "").is_empty(), "#@# exception must apply");
    assert_eq!(e.hidden("https://foo.com/", "ad-box other", ""), vec![".ad-box".to_string()]);
    let o = e.cosmetic("https://other.com/").unwrap();
    assert_eq!(o.scriptlets, vec![vec!["abort-on-property-read".to_string(), "adblock".to_string()]]);
}

#[test]
fn untrusted_lists_cannot_use_trusted_scriptlets() {
    let e = Engine::new(&[ListInput { text: "a.com##+js(trusted-set-cookie, x, y)\na.com##+js(set-cookie, consent, yes)", trusted: false }]);
    let c = e.cosmetic("https://a.com/").unwrap();
    assert_eq!(c.scriptlets.len(), 1);
    assert_eq!(c.scriptlets[0][0], "set-cookie");
}

#[test]
fn preprocessor() {
    let e = engine("!#if env_firefox\n||ff-only.com^\n!#else\n||chromium-only.com^\n!#endif\n");
    assert!(!blocked(&e, "https://ff-only.com/a", "", ty::SCRIPT));
    assert!(blocked(&e, "https://chromium-only.com/a", "", ty::SCRIPT));
}

#[test]
fn badfilter_matches_through_option_aliases_and_case() {
    // `$third-party` disabled by `$3p,badfilter` (host fast path)
    let e = engine("||velaro.com^$third-party\n||velaro.com^$3p,badfilter\n");
    assert!(!blocked(&e, "https://velaro.com/x.js", "https://a.com/", ty::SCRIPT));
    // pattern-level case differences
    let e = engine("||bing.com/rewardsapp/reportActivity?\n||bing.com/rewardsapp/reportactivity?$badfilter\n");
    assert!(!blocked(&e, "https://bing.com/rewardsapp/reportactivity?x=1", "https://a.com/", ty::XHR));
    // option order and type aliases
    let e = engine("||ads.net/x.js$script,3p\n||ads.net/x.js$third-party,script,badfilter\n");
    assert!(!blocked(&e, "https://ads.net/x.js", "https://a.com/", ty::SCRIPT));
    let e = engine("||ads.net/y.js$xhr,3p\n||ads.net/y.js$3p,xmlhttprequest,badfilter\n");
    assert!(!blocked(&e, "https://ads.net/y.js", "https://a.com/", ty::XHR));
    // a badfilter must not disable a filter with different options
    let e = engine("||ads.net^$third-party\n||ads.net^$image,badfilter\n");
    assert!(blocked(&e, "https://ads.net/x.js", "https://a.com/", ty::SCRIPT));
    // exception filters can be disabled too
    let e = engine("||ads.net^\n@@||ads.net/ok/$script\n@@||ads.net/ok/$script,badfilter\n");
    assert!(blocked(&e, "https://ads.net/ok/a.js", "https://a.com/", ty::SCRIPT));
}
