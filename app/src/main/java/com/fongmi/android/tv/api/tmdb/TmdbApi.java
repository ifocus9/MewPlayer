package com.fongmi.android.tv.api.tmdb;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.api.tmdb.bean.TmdbDetail;
import com.fongmi.android.tv.api.tmdb.bean.TmdbMedia;
import com.fongmi.android.tv.api.tmdb.bean.TmdbResult;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB API 客户端封装。
 * <p>
 * 使用方式：
 * <pre>
 *   TmdbApi.get().searchMulti("复仇者联盟", new TmdbApi.Callback() { ... });
 * </pre>
 * <p>
 * 所有网络请求均在 OkHttp 后台线程执行，结果通过 {@link SearchCallback} /
 * {@link DetailCallback} 回调（非主线程，调用方自行切换到 UI 线程）。
 */
public class TmdbApi {

    private static final String TAG = "TmdbApi";

    /** 默认 API Base URL */
    private static final String DEFAULT_API_BASE = "https://api.tmdb.org";
    /** 默认图片 CDN Base URL（不含尺寸路径） */
    public static final String DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p";

    private static volatile TmdbApi sInstance;

    private final OkHttpClient mClient;
    private final OkHttpClient mTestClient;
    private final Gson mGson;

    // ---- 单例 ----

    private TmdbApi() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        mTestClient = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build();
        mGson = new Gson();
    }

    public static TmdbApi get() {
        if (sInstance == null) {
            synchronized (TmdbApi.class) {
                if (sInstance == null) sInstance = new TmdbApi();
            }
        }
        return sInstance;
    }

    // ---- 配置检测与规范化 ----

    /** 返回 true 表示用户已配置合法的 API Key 且总开关已开启 */
    public static boolean isConfigured() {
        return Setting.isTmdbEnable() && !TextUtils.isEmpty(Setting.getTmdbApiKey());
    }

    /** 规范化 API Base URL（去除多余斜杠、补充协议头、去除结尾的 /3） */
    public static String normalizeApiBase(String raw) {
        if (raw == null) return DEFAULT_API_BASE;
        String s = raw.trim();
        if (s.isEmpty()) return DEFAULT_API_BASE;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/3")) {
            s = s.substring(0, s.length() - 2);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? DEFAULT_API_BASE : s;
    }

    /** 规范化图片 CDN Base URL（去除多余斜杠、补充协议头、确保 tmdb 官方域名带 /t/p） */
    public static String normalizeImageBase(String raw) {
        if (raw == null) return DEFAULT_IMAGE_BASE;
        String s = raw.trim();
        if (s.isEmpty()) return DEFAULT_IMAGE_BASE;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.endsWith("/t/p") && (s.contains("themoviedb.org") || s.contains("tmdb.org"))) {
            s = s + "/t/p";
        }
        return s.isEmpty() ? DEFAULT_IMAGE_BASE : s;
    }

    public String apiBase() {
        try {
            return normalizeApiBase(Setting.getTmdbApiBase());
        } catch (Throwable e) {
            return DEFAULT_API_BASE;
        }
    }

    public static String imageBase() {
        try {
            return normalizeImageBase(Setting.getTmdbImageBase());
        } catch (Throwable e) {
            return DEFAULT_IMAGE_BASE;
        }
    }

    /**
     * 拼装图片完整 URL。
     * @param path      TMDB 返回的路径，如 "/abc.jpg"
     * @param size      TMDB 图片尺寸，如 "w780"、"original"
     */
    public static String imageUrl(String path, String size) {
        if (path == null || path.isEmpty()) return "";
        return imageBase() + "/" + size + path;
    }

    // ---- 片名智能清洗 ----

    /**
     * 清洗搜索关键词，去除影片名称中常见的干扰后缀，提高 TMDB 匹配率。
     * <p>
     * 处理场景举例：
     * <ul>
     *   <li>"复仇者联盟4 更新至第3集" → "复仇者联盟4"</li>
     *   <li>"流浪地球2 4K/HDR国语版" → "流浪地球2"</li>
     *   <li>"陈情令（完结）粤语" → "陈情令"</li>
     * </ul>
     */
    public static String cleanTitle(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw.trim();

        // 1. 去除末尾或中间括号内容（完结、粤语、国语等）
        s = s.replaceAll("[（(][^）)]{0,20}[）)]", "").trim();

        // 2. 去除"更新至第xx集"、"全xx集"、"共xx集"等
        s = s.replaceAll("(?i)\\s*(更新至|更新到|连载至|更新|全|共)?\\s*(第)?\\s*\\d+\\s*(集|话|ep|episode|期).*$", "").trim();

        // 3. 去除画质及后续技术标签：4K、2K、2160P、1080P、720P、HDR、HEVC、H265、BluRay 等
        s = s.replaceAll("(?i)\\s*(4K|2K|2160[pP]|1080[pP]|720[pP]|480[pP]|HDR10?\\+?|Dolby|HEVC|H\\.?265|H\\.?264|BD|BluRay|WEB-?DL|REMUX|x265|x264).*$", "").trim();

        // 4. 去除语言标签：国语版、粤语版、普通话、中字等
        s = s.replaceAll("(?i)\\s*(国语|普通话|粤语|中字|字幕|英语|日语|韩语)(版|字幕)?.*$", "").trim();

        // 5. 去除"完结"标签
        s = s.replaceAll("(?i)\\s*(完结|已完结|全集|连载中).*$", "").trim();

        // 6. 去除多余的特殊字符（但保留冒号和点，用于"复仇者: 终局之战"类片名）
        s = s.replaceAll("[_＿【】\\[\\]《》]", " ").replaceAll("\\s{2,}", " ").trim();

        return s;
    }

    // ---- 搜索 ----

    public interface SearchCallback {
        void onSuccess(TmdbResult result);
        void onError(String message);
    }

    public interface DetailCallback {
        void onSuccess(TmdbDetail detail);
        void onError(String message);
    }

    public interface ConnectCallback {
        void onSuccess(String message);
        void onError(String message);
    }

    /**
     * 组合检索：结合两级缓存与智能匹配，直接从原始片名获取最佳影视详情。
     */
    public void searchAndGetDetail(String rawTitle, DetailCallback callback) {
        String cleaned = cleanTitle(rawTitle);
        if (TextUtils.isEmpty(cleaned)) {
            callback.onError("片名为空");
            return;
        }

        // 1. 负缓存检查
        if (TmdbCache.get().isNegative(cleaned)) {
            callback.onError("负缓存中，跳过");
            return;
        }

        // 2. 内存正缓存检查
        TmdbDetail cached = TmdbCache.get().getDetailByTitle(cleaned);
        if (cached != null) {
            callback.onSuccess(cached);
            return;
        }

        // 3. 异步搜索
        searchMulti(rawTitle, new SearchCallback() {
            @Override
            public void onSuccess(TmdbResult result) {
                TmdbMedia best = pickBestMatch(rawTitle, result);
                if (best == null) {
                    TmdbCache.get().markNegative(cleaned);
                    callback.onError("未找到匹配影视");
                    return;
                }

                // 4. 获取详细数据
                fetchDetail(best.getMediaType(), best.getId(), new DetailCallback() {
                    @Override
                    public void onSuccess(TmdbDetail detail) {
                        TmdbCache.get().putDetailByTitle(cleaned, detail);
                        TmdbCache.get().putDetail(best.getMediaType(), best.getId(), detail);
                        callback.onSuccess(detail);
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    // ---- 鉴权支持（同时兼容 v3 API Key 和 v4 API Read Access Token） ----

    /**
     * 判断是否为 TMDB v4 读访问令牌（Read Access Token，通常为以 ey 开头的 JWT 字符串，长度远大于 32 字符的 hex API Key；
     * 或者用户直接粘贴了带 Bearer 前缀的字符串）。
     */
    public static boolean isBearerToken(String keyOrToken) {
        if (keyOrToken == null) return false;
        String s = keyOrToken.trim();
        if (s.toLowerCase().startsWith("bearer ")) return true;
        return s.startsWith("ey") || s.length() > 40;
    }

    /**
     * 规范化 Bearer Token 内容（去除多余的前缀 Bearer）
     */
    public static String extractBearerToken(String keyOrToken) {
        if (keyOrToken == null) return "";
        String s = keyOrToken.trim();
        if (s.toLowerCase().startsWith("bearer ")) {
            s = s.substring(7).trim();
        }
        return s;
    }

    /**
     * 为请求应用鉴权配置：
     * 若为 Token（v4 Bearer Token），添加 Authorization: Bearer <Token> 请求头；
     * 若为 API Key（v3 32位 Hex），添加到 query parameter ?api_key=<Key>。
     */
    public static void applyAuth(Request.Builder requestBuilder, HttpUrl.Builder urlBuilder, String keyOrToken) {
        if (keyOrToken == null || keyOrToken.trim().isEmpty()) return;
        String trimmed = keyOrToken.trim();
        if (isBearerToken(trimmed)) {
            requestBuilder.header("Authorization", "Bearer " + extractBearerToken(trimmed));
        } else {
            urlBuilder.addQueryParameter("api_key", trimmed);
        }
    }

    /**
     * 多类型搜索（movie + tv 一并返回）
     * @param rawTitle  原始片名（会自动清洗）
     * @param callback  结果回调（后台线程）
     */
    public void searchMulti(String rawTitle, SearchCallback callback) {
        String cleaned = cleanTitle(rawTitle);
        if (TextUtils.isEmpty(cleaned)) {
            callback.onError("片名为空");
            return;
        }
        String apiKey = Setting.getTmdbApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            callback.onError("未配置 TMDB API Key 或 Token");
            return;
        }

        try {
            HttpUrl parsed = HttpUrl.parse(apiBase() + "/3/search/multi");
            if (parsed == null) {
                callback.onError("TMDB API 基础地址格式无效");
                return;
            }
            HttpUrl.Builder urlBuilder = parsed.newBuilder()
                    .addQueryParameter("query", cleaned)
                    .addQueryParameter("language", "zh-CN")
                    .addQueryParameter("page", "1");
            Request.Builder requestBuilder = new Request.Builder();
            applyAuth(requestBuilder, urlBuilder, apiKey);
            requestBuilder.url(urlBuilder.build());

            enqueueRequest(requestBuilder.build(), new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("网络错误: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }
                    try {
                        TmdbResult result = mGson.fromJson(body, TmdbResult.class);
                        callback.onSuccess(result);
                    } catch (Exception e) {
                        callback.onError("解析失败: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            callback.onError("请求发起失败: " + t.getMessage());
        }
    }

    /**
     * 查询详情（含演职员 credits 和 genres）。
     * @param mediaType  "movie" 或 "tv"
     * @param tmdbId     TMDB ID
     * @param callback   结果回调（后台线程）
     */
    public void fetchDetail(String mediaType, int tmdbId, DetailCallback callback) {
        String apiKey = Setting.getTmdbApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            callback.onError("未配置 TMDB API Key 或 Token");
            return;
        }

        try {
            HttpUrl parsed = HttpUrl.parse(apiBase() + "/3/" + mediaType + "/" + tmdbId);
            if (parsed == null) {
                callback.onError("TMDB API 基础地址格式无效");
                return;
            }
            HttpUrl.Builder urlBuilder = parsed.newBuilder()
                    .addQueryParameter("append_to_response", "credits,images,recommendations,similar")
                    .addQueryParameter("include_image_language", "zh,null,en")
                    .addQueryParameter("language", "zh-CN");
            Request.Builder requestBuilder = new Request.Builder();
            applyAuth(requestBuilder, urlBuilder, apiKey);
            requestBuilder.url(urlBuilder.build());

            enqueueRequest(requestBuilder.build(), new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("网络错误: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }
                    try {
                        TmdbDetail detail = mGson.fromJson(body, TmdbDetail.class);
                        callback.onSuccess(detail);
                    } catch (Exception e) {
                        callback.onError("解析失败: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            callback.onError("请求发起失败: " + t.getMessage());
        }
    }

    /**
     * 测试 API 连通性（请求 /3/configuration 并检查响应码）。
     */
    public void testConnectivity(ConnectCallback callback) {
        String apiKey = Setting.getTmdbApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            callback.onError("请先配置 TMDB API Key 或 Token");
            return;
        }

        try {
            String base = apiBase();
            HttpUrl parsed = HttpUrl.parse(base + "/3/configuration");
            if (parsed == null) {
                callback.onError("API 地址格式无效: " + base);
                return;
            }
            HttpUrl.Builder urlBuilder = parsed.newBuilder();
            Request.Builder requestBuilder = new Request.Builder();
            applyAuth(requestBuilder, urlBuilder, apiKey);
            requestBuilder.url(urlBuilder.build());

            mTestClient.newCall(requestBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (e instanceof java.net.SocketTimeoutException) {
                        callback.onError("连接超时（8s）。若在大陆直连，请配置 TMDB 代理地址");
                    } else if (e instanceof java.net.UnknownHostException) {
                        callback.onError("无法解析域名，请检查代理地址是否正确");
                    } else {
                        callback.onError("连接失败: " + msg);
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            callback.onSuccess("API 连接正常 ✓");
                        } else {
                            int code = response.code();
                            if (code == 401) {
                                callback.onError("HTTP 401: Key 或 Token 无效或未激活");
                            } else if (code == 404) {
                                callback.onError("HTTP 404: 代理路径错误（未找到 /3/configuration）");
                            } else {
                                callback.onError("HTTP " + code + "（代理或服务返回异常）");
                            }
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable t) {
            callback.onError("发起测试失败: " + t.getMessage());
        }
    }

    /**
     * 测试图片 CDN 连通性（请求测试海报并检查响应码）。
     */
    public void testImageCdn(ConnectCallback callback) {
        try {
            String base = imageBase();
            String testUrl = base + "/w92/9cqNxx0GxF0bflZmeSMuL5tnGzr.jpg";
            HttpUrl parsed = HttpUrl.parse(testUrl);
            if (parsed == null) {
                callback.onError("图片 CDN 地址格式无效: " + base);
                return;
            }

            Request request = new Request.Builder().url(testUrl).build();
            mTestClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (e instanceof java.net.SocketTimeoutException) {
                        callback.onError("图片 CDN 连接超时（8s）。建议配置图片代理");
                    } else if (e instanceof java.net.UnknownHostException) {
                        callback.onError("图片域名无法解析，请检查配置");
                    } else {
                        callback.onError("图片 CDN 连接失败: " + e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            callback.onSuccess("图片 CDN 正常 ✓");
                        } else {
                            callback.onError("图片 CDN 返回 HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable t) {
            callback.onError("发起图片测试失败: " + t.getMessage());
        }
    }

    // ---- 内部工具 ----

    /**
     * 选择最佳匹配结果（按片名相似度 + popularity 打分）。
     * @param rawTitle  清洗前的原始片名（用于字符串比对）
     * @param result    TMDB 搜索结果
     * @return 最优匹配，若无合适结果返回 null
     */
    public static TmdbMedia pickBestMatch(String rawTitle, TmdbResult result) {
        if (result == null || result.getResults() == null || result.getResults().isEmpty()) {
            return null;
        }
        String cleaned = cleanTitle(rawTitle).toLowerCase();
        TmdbMedia best = null;
        double bestScore = -1;

        for (TmdbMedia m : result.getResults()) {
            // 过滤掉 person 类型
            if ("person".equals(m.getMediaType())) continue;

            String mTitle = m.getDisplayTitle().toLowerCase();
            String mOrig = m.getOriginalTitle() != null ? m.getOriginalTitle().toLowerCase() : "";

            double titleSim = stringSimilarity(cleaned, mTitle);
            double origSim = stringSimilarity(cleaned, mOrig);
            double maxSim = Math.max(titleSim, origSim);

            // 加权：相似度 * 70% + 热度归一化 * 30%
            double score = maxSim * 0.7 + Math.min(m.getPopularity() / 200.0, 1.0) * 0.3;

            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        // 相似度极低时放弃
        return bestScore > 0.2 ? best : null;
    }

    /** 计算两个字符串的 Jaccard-like 相似度（基于字符二元组集合） */
    private static double stringSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        java.util.Set<String> setA = bigrams(a);
        java.util.Set<String> setB = bigrams(b);
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        if (union.isEmpty()) return 0;
        return (double) intersection.size() / union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        if (s.length() == 1) set.add(s); // 单字符也加入
        return set;
    }

    private void enqueueGet(String url, Callback callback) {
        Log.d(TAG, "GET " + url);
        Request request = new Request.Builder().url(url).build();
        mClient.newCall(request).enqueue(callback);
    }

    private void enqueueRequest(Request request, Callback callback) {
        Log.d(TAG, request.method() + " " + request.url());
        mClient.newCall(request).enqueue(callback);
    }
}
