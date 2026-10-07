package com.fongmi.android.tv.web.page;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 只覆盖 WebPageManager 的纯逻辑部分（不触达 Prefers / App）。
 */
public class WebPageManagerTest {

    private static WebPage page(String name, String url) {
        return WebPage.create(name, url, "", null, false);
    }

    private static List<String> names(List<WebPage> items) {
        List<String> result = new ArrayList<>();
        for (WebPage item : items) result.add(item.getName());
        return result;
    }

    @Test
    public void upsertAppendsNewAndReplacesExistingInPlace() {
        WebPage a = page("A", "https://a.com/");
        WebPage b = page("B", "https://b.com/");
        List<WebPage> items = WebPageManager.upsert(new ArrayList<>(), a);
        items = WebPageManager.upsert(items, b);
        assertEquals(Arrays.asList("A", "B"), names(items));

        a.update("A2", "https://a2.com/", "", null, true);
        items = WebPageManager.upsert(items, a);
        assertEquals(Arrays.asList("A2", "B"), names(items));
        assertEquals(0, items.get(0).getOrder());
        assertEquals(1, items.get(1).getOrder());
    }

    @Test
    public void sortedDropsInvalidAndDuplicateEntries() {
        WebPage valid = page("Valid", "https://ok.com/");
        WebPage invalid = page("Invalid", "file:///sdcard/x.html");
        List<WebPage> items = WebPageManager.sorted(Arrays.asList(valid, null, invalid, valid));
        assertEquals(Arrays.asList("Valid"), names(items));
    }

    @Test
    public void moveClampsToBoundsAndRenumbersOrder() {
        WebPage a = page("A", "https://a.com/");
        WebPage b = page("B", "https://b.com/");
        WebPage c = page("C", "https://c.com/");
        List<WebPage> items = WebPageManager.upsert(WebPageManager.upsert(WebPageManager.upsert(new ArrayList<>(), a), b), c);

        items = WebPageManager.moveById(items, c.getId(), -1);
        assertEquals(Arrays.asList("A", "C", "B"), names(items));
        items = WebPageManager.moveById(items, c.getId(), -5);
        assertEquals(Arrays.asList("C", "A", "B"), names(items));
        items = WebPageManager.moveById(items, b.getId(), 3);
        assertEquals(Arrays.asList("C", "A", "B"), names(items));
        for (int i = 0; i < items.size(); i++) assertEquals(i, items.get(i).getOrder());
    }

    @Test
    public void defaultFallsBackToFirstWhenMissing() {
        WebPage a = page("A", "https://a.com/");
        WebPage b = page("B", "https://b.com/");
        List<WebPage> items = WebPageManager.upsert(WebPageManager.upsert(new ArrayList<>(), a), b);

        assertEquals(b.getId(), WebPageManager.resolveDefaultId(items, b.getId()));
        assertEquals(a.getId(), WebPageManager.resolveDefaultId(items, "missing"));
        assertEquals(a.getId(), WebPageManager.resolveDefaultId(items, ""));

        List<WebPage> removed = WebPageManager.removeById(items, a.getId());
        assertEquals(Arrays.asList("B"), names(removed));
        assertEquals(b.getId(), WebPageManager.resolveDefaultId(removed, a.getId()));
        assertEquals("", WebPageManager.resolveDefaultId(new ArrayList<>(), a.getId()));
    }

    @Test
    public void legacyHomePageMigratesOnlyHttpUrls() {
        WebPage legacy = WebPageManager.fromLegacy("http://127.0.0.1:9988/");
        assertTrue(legacy.isTrusted());
        assertEquals("http://127.0.0.1:9988/", legacy.getUrl());
        assertNull(WebPageManager.fromLegacy(""));
        assertNull(WebPageManager.fromLegacy("./home.html"));
    }

    @Test
    public void prefKeysShareCommonPrefix() {
        assertTrue(WebPageManager.isPref(WebPageManager.KEY_PAGES));
        assertTrue(WebPageManager.isPref(WebPageManager.KEY_DEFAULT));
        assertTrue(WebPageManager.isPref(WebPageManager.KEY_MIGRATED));
        assertFalse(WebPageManager.isPref("web_home_page"));
        assertFalse(WebPageManager.isPref("web_home_extension"));
        assertFalse(WebPageManager.isPref(null));
    }
}
