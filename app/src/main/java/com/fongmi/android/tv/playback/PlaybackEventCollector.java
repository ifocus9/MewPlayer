package com.fongmi.android.tv.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.player.PlayerManager;

/**
 * 把播放器状态喂给 {@link PlaybackRuntime}，供只读接口 /api/playback/current 使用。
 */
public final class PlaybackEventCollector {

    private static final PlaybackEventCollector INSTANCE = new PlaybackEventCollector();

    private PlaybackEventCollector() {
    }

    public static PlaybackEventCollector get() {
        return INSTANCE;
    }

    public void setPlayer(@Nullable PlayerManager player) {
        PlaybackRuntime.setPlayer(player);
    }

    public void updateHistory(@Nullable History history) {
        PlaybackRuntime.updateHistory(history);
    }

    public void onProgress(@Nullable History history, @Nullable PlayerManager player) {
        updateHistory(history);
    }

    public void onPlaybackStateChanged(@Nullable PlayerManager player, int state) {
        if (state == Player.STATE_ENDED) PlaybackRuntime.clearSession();
    }

    public void onIsPlayingChanged(@Nullable PlayerManager player, boolean isPlaying) {
    }

    public void onStop(@Nullable PlayerManager player) {
        PlaybackRuntime.clearSession();
    }
}
