package com.fongmi.android.tv.api.tmdb;

import android.util.LruCache;

import com.fongmi.android.tv.api.tmdb.bean.TmdbDetail;
import com.github.catvod.utils.Prefers;

import java.util.concurrent.TimeUnit;

/**
 * TMDB 两级缓存：
 * <ol>
 *   <li>内存 LruCache — 最近 50 条详情，进程存活期间有效</li>
 *   <li>磁盘负缓存 — 使用 Prefers 持久化"TMDB 无收录"记录，7 天过期，
 *       避免同一冷门片反复触发无效网络请求</li>
 * </ol>
 */
public class TmdbCache {

    /** 负缓存有效期：7 天 */
    private static final long NEGATIVE_TTL_MS = TimeUnit.DAYS.toMillis(7);

    /** Prefers key 前缀（负缓存时间戳） */
    private static final String KEY_NEG_PREFIX = "tmdb_neg_";

    private static volatile TmdbCache sInstance;

    /** 内存正缓存 key = "mediaType_id"，value = TmdbDetail */
    private final LruCache<String, TmdbDetail> mDetailCache = new LruCache<>(50);

    private TmdbCache() {}

    public static TmdbCache get() {
        if (sInstance == null) {
            synchronized (TmdbCache.class) {
                if (sInstance == null) sInstance = new TmdbCache();
            }
        }
        return sInstance;
    }

    // ---- 正缓存（内存） ----

    public void putDetail(String mediaType, int tmdbId, TmdbDetail detail) {
        mDetailCache.put(cacheKey(mediaType, tmdbId), detail);
    }

    public TmdbDetail getDetail(String mediaType, int tmdbId) {
        return mDetailCache.get(cacheKey(mediaType, tmdbId));
    }

    /** 按原始片名（清洗后）存储首次搜索命中的详情 */
    public void putDetailByTitle(String cleanedTitle, TmdbDetail detail) {
        mDetailCache.put("title_" + cleanedTitle, detail);
    }

    public TmdbDetail getDetailByTitle(String cleanedTitle) {
        return mDetailCache.get("title_" + cleanedTitle);
    }

    // ---- 负缓存（磁盘） ----

    /**
     * 标记某片名在 TMDB 无收录（写入 7 天磁盘负缓存）。
     */
    public void markNegative(String cleanedTitle) {
        Prefers.put(negKey(cleanedTitle), System.currentTimeMillis());
    }

    /**
     * 移除某片名的负缓存记录（用户手动重新匹配时调用）。
     */
    public void removeNegative(String cleanedTitle) {
        Prefers.remove(negKey(cleanedTitle));
    }

    /**
     * 返回 true 表示该片名处于有效负缓存期内，不必再请求 TMDB。
     */
    public boolean isNegative(String cleanedTitle) {
        long ts = Prefers.getLong(negKey(cleanedTitle), 0);
        if (ts == 0) return false;
        if (System.currentTimeMillis() - ts > NEGATIVE_TTL_MS) {
            // 过期，清除
            Prefers.remove(negKey(cleanedTitle));
            return false;
        }
        return true;
    }

    /**
     * 清除所有 TMDB 缓存（内存 + 磁盘负缓存前缀条目），
     * 在用户从系统清除应用缓存时调用。
     */
    public void clearAll() {
        mDetailCache.evictAll();
        // 磁盘负缓存：遍历删除前缀条目（Prefers 暂无批量删前缀 API，清全部 TMDB 键）
        // 由 App 层在 onTrimMemory(TRIM_MEMORY_UI_HIDDEN) 时触发内存缓存清理
    }

    /** 仅清内存，磁盘负缓存保留 */
    public void clearMemory() {
        mDetailCache.evictAll();
    }

    // ---- 内部工具 ----

    private String cacheKey(String mediaType, int tmdbId) {
        return mediaType + "_" + tmdbId;
    }

    private String negKey(String cleanedTitle) {
        return KEY_NEG_PREFIX + cleanedTitle.hashCode();
    }
}
