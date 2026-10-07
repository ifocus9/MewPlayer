package com.fongmi.android.tv.web.page;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 底部「网页」Tab 的网页列表存储。
 * <p>
 * Prefers 中保存一个 Gson JSON 数组。
 * 默认页单独保存 id，避免出现多条「默认」。
 * 以 {@link #KEY_PAGES} 为前缀的所有键随本地备份一起保存；恢复时若备份里没有，会保留当前设备上的网页列表（见 Backup.restore）。
 */
public final class WebPageManager {

    public static final String KEY_PAGES = "web_home_pages";
    public static final String KEY_DEFAULT = "web_home_pages_default";
    public static final String KEY_MIGRATED = "web_home_pages_migrated";

    private static final Type TYPE = new TypeToken<List<WebPage>>() {
    }.getType();

    private WebPageManager() {
    }

    public static boolean isPref(String key) {
        return key != null && key.startsWith(KEY_PAGES);
    }

    public static synchronized List<WebPage> list() {
        return new ArrayList<>(read());
    }

    public static synchronized boolean isEmpty() {
        return read().isEmpty();
    }

    public static synchronized WebPage get(String id) {
        return find(read(), id);
    }

    public static synchronized WebPage getDefault() {
        List<WebPage> items = read();
        return find(items, resolveDefaultId(items, Prefers.getString(KEY_DEFAULT)));
    }

    public static synchronized String getDefaultId() {
        return resolveDefaultId(read(), Prefers.getString(KEY_DEFAULT));
    }

    public static synchronized void setDefault(String id) {
        if (find(read(), id) != null) Prefers.put(KEY_DEFAULT, id);
    }

    /**
     * 新增或按 id 覆盖。第一条网页自动设为默认。
     */
    public static synchronized void save(WebPage page) {
        if (page == null || !WebPage.isHttpUrl(page.getUrl())) return;
        List<WebPage> items = upsert(read(), page);
        write(items);
        if (find(items, Prefers.getString(KEY_DEFAULT)) == null) Prefers.put(KEY_DEFAULT, page.getId());
    }

    public static synchronized void remove(String id) {
        List<WebPage> items = removeById(read(), id);
        write(items);
        Prefers.put(KEY_DEFAULT, resolveDefaultId(items, Prefers.getString(KEY_DEFAULT)));
    }

    public static synchronized void move(String id, int delta) {
        write(moveById(read(), id, delta));
    }

    private static List<WebPage> read() {
        List<WebPage> items = new ArrayList<>();
        try {
            List<WebPage> restored = App.gson().fromJson(Prefers.getString(KEY_PAGES), TYPE);
            if (restored != null) items.addAll(restored);
        } catch (Throwable ignored) {
        }
        items = sorted(items);
        if (!Prefers.getBoolean(KEY_MIGRATED)) items = migrate(items);
        return items;
    }

    private static void write(List<WebPage> items) {
        Prefers.put(KEY_PAGES, App.gson().toJson(sorted(items)));
    }

    /**
     * 一次性把旧的 web_home_page（无设置 UI，仅可能来自备份恢复）迁移为一条可信网页。
     * 旧键保留不删，用 KEY_MIGRATED 保证只迁移一次。
     */
    private static List<WebPage> migrate(List<WebPage> items) {
        Prefers.put(KEY_MIGRATED, true);
        WebPage legacy = fromLegacy(Setting.getWebHomePage());
        if (legacy == null || containsUrl(items, legacy.getUrl())) return items;
        List<WebPage> result = upsert(items, legacy);
        Prefers.put(KEY_PAGES, App.gson().toJson(result));
        if (find(result, Prefers.getString(KEY_DEFAULT)) == null) Prefers.put(KEY_DEFAULT, legacy.getId());
        return result;
    }

    // ---- 以下为纯逻辑，便于 JVM 单元测试 ----

    static WebPage fromLegacy(String url) {
        if (!WebPage.isHttpUrl(url)) return null;
        return WebPage.create("WebHome", url, "", null, true);
    }

    static List<WebPage> sorted(List<WebPage> items) {
        List<WebPage> result = new ArrayList<>();
        if (items != null) {
            for (WebPage item : items) {
                if (item == null || !item.normalize()) continue;
                if (find(result, item.getId()) != null) continue;
                result.add(item);
            }
        }
        result.sort(Comparator.comparingInt(WebPage::getOrder).thenComparingLong(WebPage::getCreateTime));
        for (int i = 0; i < result.size(); i++) result.get(i).setOrder(i);
        return result;
    }

    static List<WebPage> upsert(List<WebPage> items, WebPage page) {
        List<WebPage> result = sorted(items);
        if (page == null || !page.normalize()) return result;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).getId().equals(page.getId())) {
                page.setOrder(result.get(i).getOrder());
                result.set(i, page);
                return result;
            }
        }
        page.setOrder(result.size());
        result.add(page);
        return result;
    }

    static List<WebPage> removeById(List<WebPage> items, String id) {
        List<WebPage> result = sorted(items);
        result.removeIf(item -> item.getId().equals(id));
        return sorted(result);
    }

    static List<WebPage> moveById(List<WebPage> items, String id, int delta) {
        List<WebPage> result = sorted(items);
        int from = indexOf(result, id);
        if (from < 0 || delta == 0) return result;
        int to = Math.max(0, Math.min(result.size() - 1, from + delta));
        if (to == from) return result;
        WebPage item = result.remove(from);
        result.add(to, item);
        for (int i = 0; i < result.size(); i++) result.get(i).setOrder(i);
        return result;
    }

    static String resolveDefaultId(List<WebPage> items, String defaultId) {
        if (items == null || items.isEmpty()) return "";
        if (find(items, defaultId) != null) return defaultId;
        return items.get(0).getId();
    }

    static WebPage find(List<WebPage> items, String id) {
        if (items == null || id == null || id.isEmpty()) return null;
        for (WebPage item : items) if (item.getId().equals(id)) return item;
        return null;
    }

    private static int indexOf(List<WebPage> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).getId().equals(id)) return i;
        return -1;
    }

    private static boolean containsUrl(List<WebPage> items, String url) {
        for (WebPage item : items) if (item.getUrl().equals(url)) return true;
        return false;
    }
}
