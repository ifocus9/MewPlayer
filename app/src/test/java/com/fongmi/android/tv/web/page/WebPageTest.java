package com.fongmi.android.tv.web.page;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebPageTest {

    @Test
    public void onlyHttpAndHttpsUrlsAreAccepted() {
        assertTrue(WebPage.isHttpUrl("http://127.0.0.1:9988/"));
        assertTrue(WebPage.isHttpUrl(" HTTPS://example.com/path "));
        assertFalse(WebPage.isHttpUrl("https://"));
        assertFalse(WebPage.isHttpUrl("file:///sdcard/a.html"));
        assertFalse(WebPage.isHttpUrl("javascript:alert(1)"));
        assertFalse(WebPage.isHttpUrl("intent://scan#Intent;end"));
        assertFalse(WebPage.isHttpUrl(""));
        assertFalse(WebPage.isHttpUrl(null));
    }

    @Test
    public void originNormalizesSchemeHostAndDefaultPort() {
        assertEquals("https://example.com:443", WebPage.origin("https://Example.COM/a?b=1#c"));
        assertEquals("http://example.com:80", WebPage.origin("http://example.com"));
        assertEquals("http://127.0.0.1:9988", WebPage.origin("http://127.0.0.1:9988/index.html"));
        assertEquals("http://[::1]:8080", WebPage.origin("http://[::1]:8080/x"));
        assertEquals("https://example.com:443", WebPage.origin("https://user:pass@example.com/"));
        assertEquals("", WebPage.origin("about:blank"));
        assertEquals("", WebPage.origin("file:///android_asset/a.html"));
        assertEquals("", WebPage.origin("http://example.com:abc/"));
    }

    @Test
    public void sameOriginRequiresSchemeHostAndPortMatch() {
        assertTrue(WebPage.sameOrigin("https://example.com/a", "https://example.com:443/b"));
        assertFalse(WebPage.sameOrigin("http://example.com/", "https://example.com/"));
        assertFalse(WebPage.sameOrigin("https://example.com/", "https://evil.example.com/"));
        assertFalse(WebPage.sameOrigin("http://127.0.0.1:9988/", "http://localhost:9988/"));
        assertFalse(WebPage.sameOrigin("about:blank", "about:blank"));
    }

    @Test
    public void headersRoundTripThroughText() {
        Map<String, String> headers = WebPage.parseHeaders("Referer: https://a.com/\n\nbad line\n Cookie : k=v; x=y \r\nX-Empty:");
        assertEquals(3, headers.size());
        assertEquals("https://a.com/", headers.get("Referer"));
        assertEquals("k=v; x=y", headers.get("Cookie"));
        assertEquals("", headers.get("X-Empty"));

        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("A", "1");
        ordered.put("B", "2");
        assertEquals("A: 1\nB: 2", WebPage.formatHeaders(ordered));
        assertEquals(ordered, WebPage.parseHeaders(WebPage.formatHeaders(ordered)));
    }

    @Test
    public void validUrlsIncludeHttpAndLocalPages() {
        assertTrue(WebPage.isValidUrl("http://127.0.0.1:9988/"));
        assertTrue(WebPage.isValidUrl("https://example.com/"));
        assertTrue(WebPage.isValidUrl("file:///sdcard/a.html"));
        assertTrue(WebPage.isValidUrl("file://TV/index.html"));
        assertTrue(WebPage.isValidUrl("file:/TV/index.html"));
        assertTrue(WebPage.isValidUrl("assets://web/index.html"));
        assertTrue(WebPage.isValidUrl("/storage/emulated/0/test.html"));
        assertFalse(WebPage.isValidUrl("javascript:alert(1)"));
        assertFalse(WebPage.isValidUrl("intent://scan#Intent;end"));
        assertFalse(WebPage.isValidUrl(""));
        assertFalse(WebPage.isValidUrl(null));
    }

    @Test
    public void displayNameFallsBackToHostOrFileName() {
        WebPage named = WebPage.create("  影视前端 ", "https://example.com/app", "", null, false);
        WebPage unnamed = WebPage.create("", "https://example.com:8443/app", "", null, false);
        WebPage localNamed = WebPage.create("本地页面", "file:///sdcard/test.html", "", null, false);
        WebPage localUnnamed = WebPage.create("", "file:///sdcard/test.html", "", null, false);
        WebPage localPath = WebPage.create("", "/storage/emulated/0/my_web/index.html", "", null, false);

        assertEquals("影视前端", named.getDisplayName());
        assertEquals("example.com", unnamed.getDisplayName());
        assertEquals("本地页面", localNamed.getDisplayName());
        assertEquals("test.html", localUnnamed.getDisplayName());
        assertEquals("index.html", localPath.getDisplayName());
        assertEquals("file:///storage/emulated/0/my_web/index.html", localPath.getUrl());
        assertTrue(unnamed.getKey().startsWith("webpage_"));
        assertFalse(unnamed.isTrusted());
    }
}
