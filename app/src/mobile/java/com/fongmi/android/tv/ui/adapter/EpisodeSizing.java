package com.fongmi.android.tv.ui.adapter;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/**
 * 选集卡片尺寸规则：详情页横排的卡片宽度、抽屉网格的列数共用同一套标题长度阈值。
 * 调用前剧集需已执行 EpisodeTitleCompact.apply（EpisodeAdapter.addAll 内部会执行），保证 displayName 正确。
 */
public final class EpisodeSizing {

    private EpisodeSizing() {
    }

    public static int getMaxTitleLength(List<Episode> items) {
        int maxLen = 0;
        if (items == null) return maxLen;
        for (Episode item : items) maxLen = Math.max(maxLen, item.getDisplayName().length());
        return maxLen;
    }

    /**
     * 横排卡片宽度（dp）：纯集数 72，短标题 104，较长 130，长标题（两行）150。
     */
    public static int getRowItemWidthDp(int maxLen) {
        if (maxLen >= EpisodeAdapter.LONG_TITLE_LENGTH) return 150;
        if (maxLen >= 10) return 130;
        if (maxLen >= 7) return 104;
        return 72;
    }

    /**
     * 网格列数：长标题按“单列 / 双列”设置；否则按理想卡片宽度计算，限制在 [2, maxSpan]。
     */
    public static int getGridSpan(List<Episode> items, int availableWidth, int maxSpan) {
        if (items == null || items.size() <= 1) return 1;
        int maxLen = getMaxTitleLength(items);
        if (maxLen >= EpisodeAdapter.LONG_TITLE_LENGTH) return PlayerSetting.getEpisodeColumn();
        int ideal = maxLen >= 10 ? 130 : maxLen >= 7 ? 104 : 80;
        int span = Math.max(1, availableWidth) / ResUtil.dp2px(ideal);
        return Math.max(2, Math.min(maxSpan, span));
    }
}
