package com.fongmi.android.tv.web.page;

import com.google.gson.annotations.SerializedName;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 底部「网页」Tab 中用户保存的一个网页。
 * <p>
 * 注意：本类只使用纯 Java API（不依赖 TextUtils / Uri），便于在 JVM 单元测试中直接验证。
 */
public class WebPage {

    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;
    @SerializedName("ua")
    private String ua;
    @SerializedName("headers")
    private Map<String, String> headers;
    @SerializedName("trusted")
    private boolean trusted;
    @SerializedName("order")
    private int order;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("updateTime")
    private long updateTime;

    public static WebPage create(String name, String url, String ua, Map<String, String> headers, boolean trusted) {
        WebPage page = new WebPage();
        page.id = UUID.randomUUID().toString();
        page.createTime = System.currentTimeMillis();
        page.updateTime = page.createTime;
        page.update(name, url, ua, headers, trusted);
        return page;
    }

    public void update(String name, String url, String ua, Map<String, String> headers, boolean trusted) {
        this.name = trim(name);
        this.url = trim(url);
        this.ua = trim(ua);
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        this.trusted = trusted;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 修正反序列化后可能为空的字段。返回 false 表示该记录无效（URL 不是 http/https），应丢弃。
     */
    boolean normalize() {
        name = trim(name);
        url = trim(url);
        ua = trim(ua);
        if (headers == null) headers = new LinkedHashMap<>();
        if (isEmpty(id)) id = UUID.randomUUID().toString();
        if (createTime <= 0) createTime = System.currentTimeMillis();
        if (updateTime <= 0) updateTime = createTime;
        return isHttpUrl(url);
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    /**
     * 名称为空时退回显示 host，再退回完整 URL。
     */
    public String getDisplayName() {
        if (!isEmpty(name)) return name;
        String host = host(url);
        return isEmpty(host) ? getUrl() : host;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public String getUa() {
        return ua == null ? "" : ua;
    }

    public Map<String, String> getHeaders() {
        return headers == null ? new LinkedHashMap<>() : headers;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public int getOrder() {
        return order;
    }

    void setOrder(int order) {
        this.order = order;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    /**
     * 合成的站点 key，用于桥接层 site.info / cache 命名空间等需要稳定标识的场景。
     */
    public String getKey() {
        return "webpage_" + getId();
    }

    public static boolean isHttpUrl(String url) {
        String value = trim(url).toLowerCase(Locale.ROOT);
        if (value.startsWith("http://")) return value.length() > "http://".length();
        if (value.startsWith("https://")) return value.length() > "https://".length();
        return false;
    }

    /**
     * 计算 origin：scheme://host:port（端口缺省时按 http=80 / https=443 补齐，统一小写）。
     * 非 http/https 或无法解析时返回空串。
     */
    public static String origin(String url) {
        String value = trim(url);
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) return "";
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return "";
        String rest = value.substring(schemeEnd + 3);
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int index = rest.indexOf(c);
            if (index >= 0 && index < end) end = index;
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String host;
        String port = "";
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return "";
            host = authority.substring(0, close + 1);
            if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') port = authority.substring(close + 2);
        } else {
            int colon = authority.lastIndexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
            if (colon >= 0) port = authority.substring(colon + 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return "";
        if (port.isEmpty()) port = "https".equals(scheme) ? "443" : "80";
        for (int i = 0; i < port.length(); i++) if (!Character.isDigit(port.charAt(i))) return "";
        return scheme + "://" + host + ":" + Integer.parseInt(port);
    }

    public static boolean sameOrigin(String a, String b) {
        String left = origin(a);
        return !left.isEmpty() && left.equals(origin(b));
    }

    /**
     * 解析「每行一个 Key: Value」的请求头文本；忽略空行与无冒号的行。
     */
    public static Map<String, String> parseHeaders(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) return result;
        for (String line : text.split("\\r?\\n")) {
            int index = line.indexOf(':');
            if (index <= 0) continue;
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            if (!key.isEmpty()) result.put(key, value);
        }
        return result;
    }

    public static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(entry.getKey()).append(": ").append(entry.getValue() == null ? "" : entry.getValue());
        }
        return builder.toString();
    }

    private static String host(String url) {
        String origin = origin(url);
        if (origin.isEmpty()) return "";
        String rest = origin.substring(origin.indexOf("://") + 3);
        int colon = rest.lastIndexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
