package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.node.NodeBundleManager;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();

    private Site home;
    private String wall;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    private List<Site> sites;
    private List<String> ads;
    private List<String> flags;
    private List<Parse> parses;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        SiteApi.clearNodeInit();
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String url = config.getUrl();
        if (NodeBundleManager.isNodeConfig(url)) {
            loadNodeBundle(config);
        } else {
            String json = Decoder.getJson(UrlUtil.convert(url), TAG);
            checkJson(config, Json.parse(json).getAsJsonObject());
        }
    }

    /**
     * Node.js 猫源（FongMi 5.6.8 契约）：启动 bundle 后优先读取 /config 的 video（或 data.video），
     * 把 video.sites 映射为 api 以 {@link SiteApi#NODE_PREFIX} 开头的站点，由 SiteApi 按站点路由 POST JSON 调用；
     * 没有标准 /config 时退回旧版 T4 适配（/t4/config + /t4/api）。
     */
    private void loadNodeBundle(Config config) throws Throwable {
        NodeBundleManager.prepareAndStartSync(App.get(), config.getUrl(), NodeBundleManager.DEFAULT_TIMEOUT_MS);
        JsonObject standard = nodeStandardConfig();
        if (standard != null) {
            parseConfig(config, standard);
            return;
        }
        JsonObject obj = fetchNodeJson("/t4/config");
        if (obj == null) throw new Exception("Node bundle has neither /config (video.sites) nor /t4/config");
        if (!obj.has("sites") || !obj.get("sites").isJsonArray() || obj.getAsJsonArray("sites").isEmpty()) {
            JsonArray sites = new JsonArray();
            JsonObject site = new JsonObject();
            site.addProperty("key", "catvod_node");
            site.addProperty("name", "Node猫源");
            site.addProperty("type", 4);
            site.addProperty("api", NodeBundleManager.baseUrl() + "/t4/api");
            site.addProperty("searchable", 1);
            site.addProperty("quickSearch", 1);
            sites.add(site);
            obj.add("sites", sites);
        }
        parseConfig(config, obj);
    }

    /**
     * @return 转换成本应用配置结构（sites/parses/...）的标准猫源配置；/config 不存在或没有 video.sites 时返回 null
     */
    private JsonObject nodeStandardConfig() {
        JsonObject root = fetchNodeJson("/config");
        if (root == null) return null;
        JsonObject video = childObject(root, "video");
        if (video == null) video = childObject(childObject(root, "data"), "video");
        if (video == null || !video.has("sites") || !video.get("sites").isJsonArray()) return null;
        JsonArray sites = new JsonArray();
        for (com.google.gson.JsonElement element : video.getAsJsonArray("sites")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject().deepCopy();
            String route = Json.safeString(item, "api");
            if (TextUtils.isEmpty(route) || TextUtils.isEmpty(Json.safeString(item, "key"))) continue;
            if (!route.startsWith("/")) route = "/" + route;
            // 标准猫源 meta.type（3 视频、10+ 阅读等）不是本应用的站点类型：统一按 T4 类站点处理，接口走 node: 路由
            item.addProperty("nodeType", Json.safeString(item, "type"));
            item.addProperty("type", 4);
            item.addProperty("api", SiteApi.NODE_PREFIX + route);
            if (!item.has("searchable")) item.addProperty("searchable", 1);
            if (!item.has("quickSearch")) item.addProperty("quickSearch", 1);
            sites.add(item);
        }
        if (sites.isEmpty()) return null;
        JsonObject result = new JsonObject();
        // video 下除 sites 外的字段（如 parses / flags）与顶层同名字段一并保留
        for (String key : video.keySet()) if (!"sites".equals(key)) result.add(key, video.get(key));
        for (String key : root.keySet()) if (!result.has(key) && !"video".equals(key) && !"data".equals(key)) result.add(key, root.get(key));
        result.add("sites", sites);
        SiteApi.clearNodeInit();
        return result;
    }

    private static JsonObject childObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }

    private static JsonObject fetchNodeJson(String path) {
        Request req = new Request.Builder().url(NodeBundleManager.baseUrl() + path).build();
        try (Response resp = OkHttp.client().newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            ResponseBody body = resp.body();
            if (body == null) return null;
            com.google.gson.JsonElement element = Json.parse(body.string());
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    @Override
    protected void beforeLoad() {
        CspWarmup.reset();
    }

    @Override
    protected void onLoadSuccess() {
        CspWarmup.schedule("vod-config-loaded");
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, VOD));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setDoh(Doh.arrayFrom(fetchArray(object, "doh")));
        setFlags(Json.safeListString(object, "flags"));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, true);
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        Site home = getSites().stream().filter(item -> item.getKey().equals(config.getHome())).findFirst().orElse(getSites().isEmpty() ? new Site() : getSites().get(0));
        setHome(config, home, false);
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
