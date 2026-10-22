package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.fongmi.nodejs.NodeBundleManager;
import com.google.gson.JsonObject;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SiteApi {

    public static final String PUSH = "push_agent";

    /**
     * Node.js 标准猫源站点：api = "node:" + 站点路由（如 node:/spider/kkys/3），
     * 各操作以 POST JSON 调用 {@code http://127.0.0.1:9988<路由>/<init|home|category|detail|play|search>}。
     */
    public static final String NODE_PREFIX = "node:";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Set<String> NODE_INITED = ConcurrentHashMap.newKeySet();

    /** 熔断保护：连续失败 5 次进入 30 秒冷却，避免故障 Node 源拖慢整个页面与聚合搜索 */
    private static final Map<String, Integer> FAILURE_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILURE_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long CIRCUIT_COOLDOWN_MS = 30_000;

    public static boolean isNode(@NonNull Site site) {
        return site.getApi().startsWith(NODE_PREFIX);
    }

    /** 配置重新加载 / Node 重启后需要重新调用各站点的 /init 与重置熔断状态 */
    public static void clearNodeInit() {
        NODE_INITED.clear();
        FAILURE_COUNTS.clear();
        FAILURE_TIMESTAMPS.clear();
    }

    private static void checkCircuitBreaker(String key) throws IOException {
        Integer count = FAILURE_COUNTS.get(key);
        if (count != null && count >= MAX_CONSECUTIVE_FAILURES) {
            Long lastTime = FAILURE_TIMESTAMPS.get(key);
            if (lastTime != null && System.currentTimeMillis() - lastTime < CIRCUIT_COOLDOWN_MS) {
                throw new IOException("Site [" + key + "] 连续失败超限，已进入保护降级状态 (冷却中)");
            }
        }
    }

    private static void recordSuccess(String key) {
        FAILURE_COUNTS.remove(key);
        FAILURE_TIMESTAMPS.remove(key);
    }

    private static void recordFailure(String key) {
        FAILURE_COUNTS.compute(key, (k, v) -> v == null ? 1 : v + 1);
        FAILURE_TIMESTAMPS.put(key, System.currentTimeMillis());
    }

    private static String nodeUrl(@NonNull Site site, @NonNull String op) {
        String route = site.getApi().substring(NODE_PREFIX.length()).trim();
        if (route.startsWith("http://") || route.startsWith("https://")) return trimSlash(route) + "/" + op;
        if (!route.startsWith("/")) route = "/" + route;
        return NodeBundleManager.baseUrl() + trimSlash(route) + "/" + op;
    }

    private static String trimSlash(String route) {
        while (route.endsWith("/")) route = route.substring(0, route.length() - 1);
        return route;
    }

    private static long opTimeout(String op) {
        switch (op) {
            case "init": return 5000;
            case "search": return 12000;
            case "play": return 20000;
            case "support": return 3000;
            default: return 15000;
        }
    }

    private static String nodePost(@NonNull Site site, @NonNull String op, @NonNull JsonObject body) throws IOException {
        return nodePostWithTimeout(site, op, body, opTimeout(op));
    }

    private static String nodePostWithTimeout(@NonNull Site site, @NonNull String op, @NonNull JsonObject body, long readTimeoutMs) throws IOException {
        String url = nodeUrl(site, op);
        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(3000, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(5000, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
        try (Response response = client.newCall(new Request.Builder().url(url).headers(Headers.of(site.getHeader())).post(RequestBody.create(body.toString(), JSON)).build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IOException("Node " + op + " HTTP " + response.code() + " url=" + url + " body=" + text);
            return text;
        }
    }

    private static String nodeCall(@NonNull Site site, @NonNull String op, @NonNull JsonObject body) throws IOException {
        checkCircuitBreaker(site.getKey());
        String text;
        try {
            nodeInit(site);
            text = nodePost(site, op, body);
            recordSuccess(site.getKey());
        } catch (java.net.ConnectException e) {
            // :node 进程被系统回收：用已下载的 bundle 拉起后重试一次（重启后各站点需重新 init）
            if (!recoverNode()) {
                recordFailure(site.getKey());
                throw e;
            }
            clearNodeInit();
            nodeInit(site);
            try {
                text = nodePost(site, op, body);
                recordSuccess(site.getKey());
            } catch (IOException ex) {
                recordFailure(site.getKey());
                throw ex;
            }
        } catch (IOException e) {
            recordFailure(site.getKey());
            throw e;
        }
        SpiderDebug.log("node", "site=%s op=%s body=%s result=%s", site.getKey(), op, body, text);
        return text;
    }

    @Nullable
    public static Boolean support(@NonNull Site site, @NonNull String url) {
        if (!isNode(site)) return null;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", url);
            String text = nodePostWithTimeout(site, "support", body, 3000);
            if (TextUtils.isEmpty(text)) return null;
            String trimmed = text.trim();
            if ("true".equalsIgnoreCase(trimmed)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(trimmed)) return Boolean.FALSE;
            com.google.gson.JsonElement el = Json.parse(trimmed);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("result")) return obj.get("result").getAsBoolean();
                if (obj.has("support")) return obj.get("support").getAsBoolean();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean recoverNode() {
        SpiderDebug.log("node", "node service unreachable, restarting");
        NodeBundleManager.startIfPresent(App.get());
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10000) {
            if (NodeBundleManager.isServiceRunning()) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void nodeInit(@NonNull Site site) {
        if (!NODE_INITED.add(site.getKey())) return;
        try {
            JsonObject body = new JsonObject();
            if (!site.getExt().isEmpty()) body.addProperty("ext", site.getExt());
            nodePost(site, "init", body);
        } catch (Throwable e) {
            // init 失败不阻断后续调用（部分爬虫没有 init），下次加载配置后会重试
            SpiderDebug.log("node", "site=%s init failed: %s", site.getKey(), e.getMessage());
        }
    }

    private static int toPage(String page) {
        try {
            int value = Integer.parseInt(page.trim());
            return value <= 0 ? 1 : value;
        } catch (Throwable e) {
            return 1;
        }
    }

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        try (Response response = call.execute()) {
            return response.body().string();
        }
    }

    private static boolean isSpider(@NonNull Site site) {
        return site.getType() == 3;
    }

    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("filter", true);
            Result result = Result.fromJson(nodeCall(site, "home", body));
            setTypes(site, result);
            return result;
        } else if (isSpider(site)) {
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spider.homeContent(true);
            String video = crash ? "" : spider.homeVideoContent();
            Prefers.put("crash", false);
            SpiderDebug.log("home", home);
            SpiderDebug.log("homeVideo", video);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                String homeContent = response.body().string();
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return result;
            }
        }
    }

    @NonNull
    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("id", tid);
            body.addProperty("page", toPage(page));
            body.addProperty("filter", filter);
            body.add("filters", App.gson().toJsonTree(extend));
            return Result.fromJson(nodeCall(site, "category", body));
        } else if (isSpider(site)) {
            String categoryContent = site.recent().spider().categoryContent(tid, page, filter, extend);
            SpiderDebug.log("category", categoryContent);
            return Result.fromJson(categoryContent);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return Result.fromType(site.getType(), categoryContent);
        }
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        SpiderDebug.log("detail", "key=%s,id=%s", key, id);
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.detail(id);
        Site site = VodConfig.get().getSite(key);
        if (site.isEmpty() && PUSH.equals(key)) {
            Vod vod = new Vod();
            vod.setId(id);
            vod.setName(id);
            vod.setPlayUrl(id);
            vod.setPlayFrom(ResUtil.getString(R.string.push));
            vod.setPic(ResUtil.getString(R.string.push_image));
            Source.get().parse(vod.setFlags());
            return Result.vod(vod);
        } else if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            Result result = Result.fromJson(nodeCall(site, "detail", body));
            Source.get().parse(result.getVod().setFlags());
            return result;
        } else if (isSpider(site)) {
            String detailContent = site.recent().spider().detailContent(Arrays.asList(id));
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromJson(detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromType(site.getType(), detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        }
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        return playerContent(key, flag, id, PlayerSetting.getPlayer());
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType) throws Exception {
        SpiderDebug.log("player", "key=%s,flag=%s,id=%s", key, flag, id);
        Source.get().stop();
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.player(flag, id);
        Site site = VodConfig.get().getSite(key);
        if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("flag", flag);
            body.addProperty("id", id);
            body.add("flags", App.gson().toJsonTree(VodConfig.get().getFlags()));
            Result result = Result.fromJson(nodeCall(site, "play", body));
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result, playerType));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return result;
        } else if (site.getType() == 3) {
            String playerContent = site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags());
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result, playerType));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            String playerContent = call(site, params);
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result, playerType));
            result.setHeader(site.getHeader());
            return result;
        } else if (site.isEmpty() && "push_agent".equals(key)) {
            Result result = new Result();
            result.setUrl(id);
            result.setParse(0);
            result.setFlag(flag);
            result.setUrl(Source.get().fetch(result, playerType));
            SpiderDebug.log("player", result.toString());
            return result;
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(Source.get().fetch(result, playerType));
            SpiderDebug.log("player", result.toString());
            return result;
        }
    }

    @NonNull
    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("wd", keyword);
            body.addProperty("page", toPage(page));
            body.addProperty("quick", quick);
            Result result = Result.fromJson(nodeCall(site, "search", body));
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else if (isSpider(site)) {
            String searchContent = hasPage ? site.spider().searchContent(keyword, quick, page) : site.spider().searchContent(keyword, quick);
            SpiderDebug.log("search", searchContent);
            Result result = Result.fromJson(searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            String searchContent = call(site, params);
            SpiderDebug.log("search", searchContent);
            Result result = fetchPic(site, Result.fromType(site.getType(), searchContent));
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        }
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        Site site = VodConfig.get().getSite(key);
        SpiderDebug.log("action", "key=%s,action=%s", key, action);
        if (isNode(site)) {
            JsonObject body = new JsonObject();
            body.addProperty("action", action);
            return Result.fromJson(nodeCall(site, "action", body));
        }
        if (site.getType() == 3) return Result.fromJson(site.recent().spider().action(action));
        if (site.getType() == 4) return Result.fromJson(OkHttp.string(action));
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            result.setList(Result.fromType(site.getType(), response.body().string()).getList());
            return result;
        }
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        Map<String, Class> typeByName = new HashMap<>();
        result.getTypes().forEach(type -> typeByName.put(type.getTypeName(), type));
        List<Class> types = site.getCategories().stream().map(typeByName::get).filter(Objects::nonNull).toList();
        if (!types.isEmpty()) result.setTypes(types);
    }
}
