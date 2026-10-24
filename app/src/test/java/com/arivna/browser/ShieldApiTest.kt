package com.arivna.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM-safe app contracts; full native filter semantics live in shield-rs/tests. */
class ShieldApiTest {
    @Test fun searchEngineLookupHasSafeDefault() {
        assertEquals(SearchEngine.BRAVE, SearchEngine.of("brave"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.of("not-an-engine"))
    }

    @Test fun everySearchEngineHasAnHttpsTemplate() {
        SearchEngine.entries.forEach { engine ->
            assert(engine.searchUrl.startsWith("https://"))
            assert(engine.searchUrl.contains("%s"))
        }
    }

    @Test fun scriptRedirectGuardOnlyAllowsSameSiteHosts() {
        assert(sameSiteHost("www.example.com", "example.com"))
        assert(sameSiteHost("example.com", "cdn.example.com"))
        assert(sameSiteHost("hdhub4u.kg", "hdhub4u.ms"))
        assert(sameSiteHost("hdhub4u.kg", "new8.hdhub4u.cl"))
        assert(!sameSiteHost("hdhub4u.kg", "fresh-ad-domain.example"))
        assert(!sameSiteHost("bollyflix.bi", "click-network.example"))
        assert(!sameSiteHost("shop.apple.com", "apple.scam-site.org"))
        assert(!sameSiteHost("google.com", "google.malicious-domain.com"))
        assert(!sameSiteHost("hdhub4u.kg", "hdhub4u.attacker.com"))
    }

    @Test fun addressBarInputParsingTests() {
        val tmpl = "https://www.google.com/search?q=%s"
        assertEquals("http://localhost:3000", parseNavInput("localhost:3000", tmpl))
        assertEquals("http://localhost", parseNavInput("localhost", tmpl))
        assertEquals("http://192.168.1.1", parseNavInput("192.168.1.1", tmpl))
        assertEquals("https://example.com", parseNavInput("example.com", tmpl))
        assertEquals("https://www.google.com/search?q=node.js", parseNavInput("node.js", tmpl))
        assertEquals("https://www.google.com/search?q=kotlin+android", parseNavInput("kotlin android", tmpl))
        assertEquals("https://github.com/torvalds", parseNavInput("github.com/torvalds", tmpl))
        assertEquals("about:blank", parseNavInput("about:blank", tmpl))
    }
}
