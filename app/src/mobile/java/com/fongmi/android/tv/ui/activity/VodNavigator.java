package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.setting.Setting;

/**
 * 集中式影视详情/播放路由调度器。
 * <p>
 * 分流策略：
 * <ul>
 *   <li><b>分支 A（历史记录/继续观看）</b>：fromHistory == true 时直接调用 VideoActivity 秒开播放；</li>
 *   <li><b>分支 B（TMDB 开启且详情页开启）</b>：进入全屏暗色大画报详情页 VodDetailActivity；</li>
 *   <li><b>分支 C（未配置 TMDB 或已关闭详情页）</b>：保持原有原生半屏即时起播体验。</li>
 * </ul>
 */
public class VodNavigator {

    public static void open(Activity activity, String key, String id, String name, String pic) {
        open(activity, key, id, name, pic, null, false, null, null, false);
    }

    public static void open(Activity activity, String key, String id, String name, String pic, String mark) {
        open(activity, key, id, name, pic, mark, false, null, null, false);
    }

    public static void open(Activity activity, String key, String id, String name, String pic, String mark, boolean fromHistory) {
        open(activity, key, id, name, pic, mark, false, null, null, fromHistory);
    }

    public static void open(Activity activity, String key, String id, String name, String pic, String mark, String wallPic, boolean fromHistory) {
        open(activity, key, id, name, pic, mark, false, wallPic, null, fromHistory);
    }

    public static void open(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, String wallPic, String content, boolean fromHistory) {
        if (shouldOpenDetail(fromHistory, name)) {
            VodDetailActivity.start(activity, key, id, name, pic, mark, collect, wallPic, content);
        } else {
            VideoActivity.start(activity, key, id, name, pic, mark, collect, wallPic, content);
        }
    }

    public static void collect(Activity activity, String key, String id, String name, String pic, String wallPic) {
        open(activity, key, id, name, pic, null, true, wallPic, null, false);
    }

    private static boolean shouldOpenDetail(boolean fromHistory, String name) {
        if (fromHistory) return false;
        if (TextUtils.isEmpty(name)) return false;
        if (!Setting.isTmdbEnable()) return false;
        return TmdbApi.get().isConfigured();
    }
}
