package com.fongmi.android.tv.player;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.codec.CodecCapabilityInspector;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.exo.ass.ExoAssSession;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.exo.ExoDecoderResourceSession;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardBufferPolicy;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardController;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardSession;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardEligibility;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagController;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagSession;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagPolicy;
import com.fongmi.android.tv.player.exo.ForwardBufferTrend;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.player.mpv.MpvOutputCoordinator;
import com.fongmi.android.tv.player.mpv.MpvPlaybackController;
import com.fongmi.android.tv.player.mpv.MpvAutoControlPolicy;
import com.fongmi.android.tv.player.mpv.MpvAutoOutputPolicy;
import com.fongmi.android.tv.player.mpv.MpvAutoRenderPolicy;
import com.fongmi.android.tv.player.mpv.MpvBackCacheController;
import com.fongmi.android.tv.player.mpv.MpvBackCachePolicy;
import com.fongmi.android.tv.player.mpv.MpvCacheTargetCoordinator;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.player.mpv.MpvForwardCacheController;
import com.fongmi.android.tv.player.mpv.MpvForwardCachePolicy;
import com.fongmi.android.tv.player.mpv.MpvHlsVariantController;
import com.fongmi.android.tv.player.mpv.MpvHlsVariantPolicy;
import com.fongmi.android.tv.player.mpv.MpvPreloadController;
import com.fongmi.android.tv.player.mpv.MpvPreloadPolicy;
import com.fongmi.android.tv.player.mpv.MpvResourcePressureController;
import com.fongmi.android.tv.player.mpv.MpvVulkanBackendPolicy;
import com.fongmi.android.tv.player.mpv.MpvResourcePressurePolicy;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackExperimentSetting;
import com.fongmi.android.tv.setting.PlaybackLightweightAssessmentSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackProfileAbSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.LocalProxyDebug;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import is.xyz.mpv.MPVLib;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private static final String NETWORK_GUARD_DEBUG = "EXO_NETWORK_GUARD";

    private static void logNetworkGuard(String message) {
        if (SpiderDebug.isEnabled()) Log.d(NETWORK_GUARD_DEBUG, message);
    }

    private static final long LOCAL_PROXY_READY_TIMEOUT_MS = 5000;
    private static final long LOCAL_PROXY_RETRY_DELAY_MS = 1000;
    private static final long HARD_DECODE_SWITCH_RETRY_DELAY_MS = 1200;
    private static final long EXO_TUNNELING_RETRY_DELAY_MS = 250;
    private static final long EXO_DECODER_RUNTIME_RETRY_DELAY_MS = 1200;
    private static final long EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS = 1200;
    private static final int LOCAL_PROXY_MAX_RETRY = 2;
    private static final long PLAYBACK_TELEMETRY_INTERVAL_MS = 5000L;
    private static final long MPV_FRAME_TIMING_LOG_INTERVAL_MS = 5000L;
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000L;
    private static final float[] SPEED_PRESETS = new float[]{0.5f, 0.75f, 1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 5f};
    private static final DecimalFormat SPEED_FORMAT = new DecimalFormat("0.##x");

    private final Runnable runnable;
    private final Callback callback;
    private final PlaybackAudioSession playbackAudioSession;
    private final PlaybackBufferingTracker playbackBufferingTracker;
    private final PlaybackTrace playbackTrace;
    private final PlaybackAutoContextStore playbackAutoContextStore;
    private final PlaybackTelemetrySession playbackTelemetrySession;
    private final PlaybackMediaFactsCoordinator playbackMediaFactsCoordinator;
    private final ExoDecoderResourceSession exoDecoderResourceSession;
    private final ExoRtspLiveLagSession exoRtspLiveLagSession;
    private final MpvPlaybackController mpvPlaybackController;
    private final MpvOutputCoordinator mpvOutputCoordinator;
    private final ExoNetworkGuardSession exoNetworkGuardSession;
    private final PlaybackErrorHandler playbackErrorHandler;
    private final PlaybackExperimentCoordinator playbackExperimentCoordinator;
    private final PlaybackExperimentCoordinator.Registration playbackExperimentRegistration;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private String lastLoggedRouteTraceId = PlaybackTrace.NONE;
    private PlaybackAutoContext.SessionToken playbackAutoSession = PlaybackAutoContext.SessionToken.none();
    private long playbackTrackSequence;
    private long pendingSwitchPositionMs = C.TIME_UNSET;
    private long pendingInitialStartPositionMs = C.TIME_UNSET;
    private float pendingSwitchSpeed = 1f;
    private boolean pendingSwitchRepeat;
    private boolean pendingSwitchRestore;

    private boolean initTrack;
    private boolean playWhenReady = true;
    private boolean hardDecodeSwitchRetryArmed;
    private int playerType;
    private int retry;
    private int prepareSeq;
    private float userPlaybackSpeed = 1f;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        PlaybackExperimentSetting.ensureInitialized();
        this.playbackExperimentCoordinator =
                PlaybackExperimentCoordinator.process();
        this.playbackExperimentRegistration =
                playbackExperimentCoordinator.addListener(update ->
                        App.post(() -> onPlaybackExperimentPolicyChanged(update)));
        this.runnable = this::onPlaybackTimeout;
        this.playbackBufferingTracker = new PlaybackBufferingTracker();
        this.playbackTrace = new PlaybackTrace();
        this.playbackAutoContextStore = PlaybackAutoContextStore.process();
        this.playbackTelemetrySession = new PlaybackTelemetrySession(new PlaybackTelemetryHost());
        this.playbackMediaFactsCoordinator = new PlaybackMediaFactsCoordinator(playbackAutoContextStore);
        this.playbackAudioSession = new PlaybackAudioSession(new PlaybackAudioHost());
        this.exoDecoderResourceSession = new ExoDecoderResourceSession(new ExoDecoderResourceHost());
        this.exoRtspLiveLagSession = new ExoRtspLiveLagSession(new ExoRtspLiveLagHost());
        this.mpvPlaybackController = new MpvPlaybackController(new MpvHost());
        this.mpvOutputCoordinator = new MpvOutputCoordinator(new MpvOutputHost());
        this.exoNetworkGuardSession = new ExoNetworkGuardSession(new ExoNetworkGuardHost());
        this.playbackErrorHandler = new PlaybackErrorHandler(new PlaybackErrorHost());
        this.playerType = PlayerSetting.getPlayer();
        this.engine = buildEngine(playerType, PlayerEngine.HARD);
        this.player = engine.getPlayer();
    }

    public void release() {
        prepareSeq++;
        prepareTerminalRelease();
        exoNetworkGuardSession.release();
        exoDecoderResourceSession.clear(true);
        player.removeListener(listener);
        App.removeCallbacks(runnable);
        playbackTelemetrySession.release();
        if (mpvPlaybackController != null) mpvPlaybackController.release();
        mpvOutputCoordinator.release();
        playbackExperimentRegistration.close();
        playbackAudioSession.stop();
        playbackTelemetrySession.endSession("release");
        clearPlaybackAutoContext();
        if (engine == null) return;
        engine.release();
        engine = null;
        player = null;
        playbackBufferingTracker.reset();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }

    public void prepareTerminalRelease() {
        if (engine instanceof MpvPlayerEngine mpv) mpv.prepareTerminalRelease();
    }

    private boolean experimentAllowed(PlaybackExperimentPolicy.Action action) {
        return PlaybackExperimentSetting.isAllowed(action);
    }

    private void onPlaybackExperimentPolicyChanged(
            PlaybackExperimentCoordinator.Update update) {
        if (update == null) return;
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason
                        .GENERATION_CHANGED);
        PlaybackExperimentPolicy.State policy =
                PlaybackExperimentSetting.getState();
        PlaybackTrace.log(
                "playback-experiment",
                playbackTrace.current(),
                "generation=%d change=%s strategy=%s frameAb=%s profileAb=%s action=invalidate-internal-experiments",
                update.generation(),
                update.change(),
                policy.strategyId(),
                policy.allows(PlaybackExperimentPolicy.Action
                        .EXO_FRAME_SCHEDULING_AB),
                policy.allows(PlaybackExperimentPolicy.Action
                        .SHARED_PROFILE_AB_VALIDATION));
    }

    private void onPlaybackTimeout() {
        playbackErrorHandler.handlePlaybackTimeout();
    }

    public Player getPlayer() {
        return player;
    }

    public ExoAssSession getAssSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getAssSession() : null;
    }

    public com.fongmi.android.tv.player.exo.subtitle.ExoSubtitleSession getSubtitleSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getSubtitleSession() : null;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return engine.getCurrentMediaEditions();
    }

    public List<MediaChapter> getCurrentMediaChapters() {
        return engine != null ? engine.getCurrentMediaChapters() : Collections.emptyList();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public String getPlaybackTraceId() {
        return playbackTrace.current();
    }

    public PlaybackAutoContext getPlaybackAutoContext() {
        return playbackAutoContextStore.snapshot();
    }

    public void publishPlaybackRenderTarget(PlaybackAutoContext.RenderTarget renderTarget) {
        playbackMediaFactsCoordinator.publishRenderTarget(
                playbackAutoSession, renderTarget, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDisplayFacts(
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode) {
        playbackMediaFactsCoordinator.publishDisplayFacts(
                playbackAutoSession, currentMode, requestedMode, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDecision(PlaybackTelemetry.DecisionEvent event) {
        playbackTelemetrySession.getCoordinator().publishDecision(
                playbackAutoSession, event, SystemClock.elapsedRealtime());
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return userPlaybackSpeed;
    }

    public float getEffectiveSpeed() {
        return player == null ? userPlaybackSpeed : player.getPlaybackParameters().speed;
    }

    public String getNetworkProtectionText() {
        return exoNetworkGuardSession.getText();
    }

    public long getNetworkProtectionMediaBitrate() {
        return exoNetworkGuardSession.getMediaBitrate();
    }

    public long getNetworkProtectionStableThroughput() {
        return exoNetworkGuardSession.getStableThroughput();
    }

    public long getNetworkProtectionConsumption() {
        return exoNetworkGuardSession.getConsumption(getEffectiveSpeed());
    }

    public float getNetworkProtectionSupportedSpeed() {
        return exoNetworkGuardSession.getSupportedSpeed();
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public PlaySpec getSpec() {
        return spec;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public long getBufferedDuration() {
        return Math.max(0, getEffectiveBufferedPosition() - getPosition());
    }

    public int getBufferedPercentage() {
        if (!isExo()) return player.getBufferedPercentage();
        long duration = player.getDuration();
        if (duration == 0) return 100;
        if (duration < 0) return 0;
        return Math.max(0, Math.min(100, androidx.media3.common.util.Util.percentInt(
                getEffectiveBufferedPosition(), duration)));
    }

    private long getEffectiveBufferedPosition() {
        long nativeBuffered = Math.max(0, player.getBufferedPosition());
        if (!isExo()) return nativeBuffered;
        String mediaKey = PlaybackDiskBufferStore.mediaKey(player.getCurrentMediaItem());
        return PlaybackDiskBufferStore.process().effectiveEnd(
                mediaKey, nativeBuffered, player.getDuration(), DISK_RANGE_GAP_TOLERANCE_MS);
    }

    public boolean isLoading() {
        return player.isLoading();
    }

    public String getSizeText() {
        int width = getVideoWidth();
        int height = getVideoHeight();
        if (width <= 0 || height <= 0) {
            Format format = getVideoFormat();
            if (format != null) {
                if (width <= 0) width = format.width;
                if (height <= 0) height = format.height;
            }
        }
        return width <= 0 || height <= 0 ? "" : width + " x " + height;
    }

    public Format getVideoFormat() {
        return engine.getVideoFormat();
    }

    public PlayerCacheState getCacheState() {
        return engine == null ? PlayerCacheState.empty() : engine.getCacheState();
    }

    public String getRenderDiagnostics() {
        return engine == null ? "" : engine.getRenderDiagnostics();
    }

    public String getRuntimeDiagnostics() {
        return engine == null ? "" : engine.getRuntimeDiagnostics();
    }

    public String getGpuLoadDiagnostics() {
        return engine == null ? "" : engine.getGpuLoadDiagnostics();
    }

    public void setGpuLoadDiagnosticsEnabled(boolean enabled) {
        if (engine != null) engine.setGpuLoadDiagnosticsEnabled(enabled);
    }

    public PlayerEngine.VideoPlaybackDetails getVideoPlaybackDetails() {
        return engine == null
                ? PlayerEngine.VideoPlaybackDetails.empty()
                : engine.getVideoPlaybackDetails();
    }

    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        return engine == null
                ? AudioPlaybackDiagnostics.Snapshot.empty()
                : engine.getAudioPlaybackDiagnostics();
    }

    public long getDroppedFrames() {
        return engine == null ? 0 : engine.getDroppedFrames();
    }

    public int getRebufferCount() {
        long discCount = player instanceof MpvPlayer mpv ? mpv.getDiscRebufferCount() : 0;
        return (int) Math.min(Integer.MAX_VALUE, playbackBufferingTracker.getRebufferCount() + discCount);
    }

    public long getRebufferTotalMs() {
        return getRebufferTotalMs(SystemClock.elapsedRealtime());
    }

    private long getRebufferTotalMs(long nowMs) {
        long discMs = player instanceof MpvPlayer mpv ? mpv.getDiscRebufferTotalMs(nowMs) : 0;
        return playbackBufferingTracker.getRebufferTotalMs(nowMs) + discMs;
    }

    public boolean supportsSubtitleStyle() {
        return engine != null && engine.supportsSubtitleStyle();
    }

    public boolean supportsSecondarySubtitle() {
        return engine != null && engine.supportsSecondarySubtitle();
    }

    public boolean isSecondarySubtitleSelected(Format format) {
        return engine != null && engine.isSecondarySubtitleSelected(format);
    }

    public boolean isPrimarySubtitleSelected(Format format) {
        return engine != null && engine.isPrimarySubtitleSelected(format);
    }

    public String getAudioPassThroughText() {
        if (!PlayerSetting.isAudioPassThrough()) return "关";
        if (!isMpv()) return "开";
        String codecs = engine == null ? "" : engine.getAudioSpdifCodecs();
        return TextUtils.isEmpty(codecs) ? "开/PCM" : "开/" + codecs;
    }

    public void setSubtitleStyle(float textSize, float position) {
        if (engine != null) engine.setSubtitleStyle(textSize, position);
    }

    public String getSpeedText() {
        return SPEED_FORMAT.format(getSpeed());
    }

    public String getDecodeText() {
        return engine.getDecodeText();
    }

    public boolean isHardDecode() {
        return engine.isHard();
    }

    public String getPlayerText() {
        return ResUtil.getStringArray(R.array.select_player_kernel)[playerType];
    }

    public int getPlayerType() {
        return playerType;
    }

    public boolean isMpv() {
        return playerType == PlayerSetting.MPV;
    }

    public boolean sendMpvCustomButton(String id, boolean longPress) {
        if (!isMpv() || TextUtils.isEmpty(id) || !(engine instanceof MpvPlayerEngine mpv)) return false;
        return mpv.sendScriptMessage(MpvConfigStore.CUSTOM_BUTTON_MESSAGE, id, longPress ? "long" : "short");
    }

    public boolean isMpvSurfaceDirect() {
        return engine instanceof MpvPlayerEngine mpv && mpv.isSurfaceDirect();
    }

    /**
     * Keep the native player's shutter visible while automatic MPV output is
     * still being selected. This prevents a failed direct DV probe from
     * exposing a stale poster or the last frame before the GPU rebuild.
     */
    public boolean shouldKeepVideoShutterClosed() {
        return isMpv()
                && MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO
                && !mpvOutputCoordinator.isEvaluated()
                && !mpvOutputCoordinator.isFrameReady();
    }

    public boolean isExo() {
        return playerType == PlayerSetting.EXO;
    }

    public boolean isNativePlayer() {
        return !isExo();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        setMediaItem();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public void setTitle(MediaEdition edition) {
        if (edition == null) return;
        if (isMpv() && engine.selectEdition(edition)) return;
        if (spec != null) spec.setUrl(spec.getUri().buildUpon().fragment("edition=" + edition.index).build().toString());
        if (engine.selectEdition(edition)) return;
        setMediaItem();
        seekTo(0);
    }

    public void selectChapter(MediaChapter chapter) {
        if (chapter == null || engine == null) return;
        engine.selectChapter(chapter);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        engine.setMetadata(data);
    }

    public void setPlaybackForeground(boolean foreground) {
        exoDecoderResourceSession.setPlaybackForeground(foreground);
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        if (Math.abs(speed - userPlaybackSpeed) >= 0.001f) {
            invalidatePlaybackProfileAssessments(
                    PlaybackProfileAbCoordinator.InvalidationReason.USER_SPEED);
        }
        userPlaybackSpeed = speed;
        exoNetworkGuardSession.reset("user-speed");
        if (Math.abs(speed - 1f) < 0.001f) exoNetworkGuardSession.schedule(0);
        return getSpeedText();
    }


    public String addSpeed() {
        return setSpeed(nextPresetSpeed());
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    private float nextPresetSpeed() {
        float speed = getSpeed();
        for (float preset : SPEED_PRESETS) if (speed < preset - 0.01f) return preset;
        return SPEED_PRESETS[0];
    }

    public void setTrack(List<Track> tracks) {
        if (engine instanceof MpvPlayerEngine mpv && hasRequestedSubtitle(tracks)) {
            mpv.retainSubtitleSurfaceForCurrentItem();
        }
        if (!tracks.isEmpty()) engine.setTrack(tracks);
    }

    public void setSecondarySubtitleTrack(Track track) {
        if (track != null && !track.isDisabled()
                && engine instanceof MpvPlayerEngine mpv) {
            mpv.retainSubtitleSurfaceForCurrentItem();
        }
        if (engine != null) engine.setSecondarySubtitleTrack(track);
    }

    public void play() {
        playbackAudioSession.start(true);
        player.play();
    }

    public void pause() {
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason.PAUSED);
        player.pause();
        playbackAudioSession.stop();
    }

    public void stop() {
        playbackAudioSession.stop();
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        playbackAudioSession.stop();
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        engine.setRepeatOne(repeat);
    }

    public void seekTo(long time) {
        long now = SystemClock.elapsedRealtime();
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason.USER_SEEK);
        exoRtspLiveLagSession.onUserSeek(playbackAutoSession, now);
        exoNetworkGuardSession.reset("user-seek");
        if (isExo()) {
            PlaybackAnalyticsListener.onUserSeekRequested(
                    player.getCurrentPosition(),
                    time,
                    player.getPlaybackState(),
                    player.getBufferedPosition(),
                    player.getTotalBufferedDuration(),
                    player.isLoading(),
                    player.isPlaying());
        }
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET)) return player.getTextOffsetMs();
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET)) return player.getAudioOffsetMs();
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        boolean activePlayback = player != null
                && player.getPlaybackState() == Player.STATE_READY
                && player.getPlayWhenReady();
        if (activePlayback) exoNetworkGuardSession.schedule(0);
        else exoNetworkGuardSession.reset("reset");
        retry = 0;
        playbackErrorHandler.resetLocalProxyRetry();
        hardDecodeSwitchRetryArmed = false;
        clearPendingSwitchRestore();
    }

    public void clear() {
        prepareSeq++;
        exoDecoderResourceSession.clear(true);
        exoNetworkGuardSession.reset("clear");
        mpvOutputCoordinator.resetRuntime();
        spec = null;
        clearPendingSwitchRestore();
        playbackTelemetrySession.endSession("clear");
        playbackBufferingTracker.reset();
        clearPlaybackAutoContext();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }

    public void resetTrack() {
        engine.resetTrack();
    }

    public void restoreVideoTrack() {
        if (engine != null) engine.restoreVideoTrack();
    }

    public void toggleDecode() {
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        beginPlaybackTrace("switch-decode");
        engine.setDecode(next);
        if (engine instanceof ExoPlayerEngine exo) {
            exo.prepareFrameSchedulingForNextPlayback();
        }
        rebuildPlayer(resetVideoSurface);
        setMediaItem();
    }

    public void switchDecode(PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginPlaybackTrace("switch-decode-fresh");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        playbackAudioSession.stop();
        engine.release();
        spec = freshSpec;
        bindPlaybackTrace();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh decode=%d position=%d spec=%s", next, position, debugSpec());
        callback.onPlayerRebuild(player, resetVideoSurface);
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchDecode(Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginPlaybackTrace("switch-decode-result");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        playbackAudioSession.stop();
        stopParse();
        engine.release();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh parse decode=%d position=%d useParse=%s spec=%s", next, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh result decode=%d position=%d spec=%s", next, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    public void togglePlayer() {
        switchPlayer(PlayerSetting.nextPlayer(playerType));
    }

    public void switchPlayer(int type) {
        switchPlayer(type, true);
    }

    public void switchPlayer(int type, PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginPlaybackTrace("switch-player-fresh");
        type = PlayerSetting.sanitizePlayer(type);
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        playbackAudioSession.stop();
        engine.release();
        playerType = type;
        PlayerSetting.putPlayer(type);
        spec = freshSpec;
        bindPlaybackTrace();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh type=%d position=%d spec=%s", type, position, debugSpec());
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        callback.onPlayerRebuild(player, resetVideoSurface);
        pendingInitialStartPositionMs = position > 0 ? position : C.TIME_UNSET;
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchPlayer(int type, Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginPlaybackTrace("switch-player-result");
        type = PlayerSetting.sanitizePlayer(type);
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        playbackAudioSession.stop();
        stopParse();
        engine.release();
        playerType = type;
        PlayerSetting.putPlayer(type);
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh parse type=%d position=%d useParse=%s spec=%s", type, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh result type=%d position=%d spec=%s", type, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    private void switchPlayer(int type, boolean persist) {
        if (engine == null || player == null) return;
        type = PlayerSetting.sanitizePlayer(type);
        if (type == playerType) return;
        beginPlaybackTrace("switch-player");
        long position = getPosition();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        playbackAudioSession.stop();
        engine.release();
        playerType = type;
        if (persist) {
            PlayerSetting.putPlayer(type);
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player type=%d persist=%s position=%d spec=%s", type, persist, position, debugSpec());
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        // A Surface used by a CPU renderer stays connected after decoder release.
        // A different player needs a new Surface before its GPU/codec can connect.
        callback.onPlayerRebuild(player, true);
        if (spec == null || spec.getUrl() == null) return;
        this.playWhenReady = wasPlayWhenReady;
        if (reparseForPlayerSwitch(position, speed, repeat)) return;
        pendingInitialStartPositionMs = position > 0 ? position : C.TIME_UNSET;
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void rebuildPlayer() {
        rebuildPlayer(false);
    }

    private void rebuildPlayer(boolean resetVideoSurface) {
        playbackAudioSession.stop();
        player = engine.rebuild(listener);
        callback.onPlayerRebuild(player, resetVideoSurface);
    }

    public void applyPerformanceSettings() {
        if (isExo()) {
            exoNetworkGuardSession.reset("performance-settings-changed");
            exoNetworkGuardSession.schedule(0);
            if (engine instanceof ExoPlayerEngine exo
                    && exo.requiresDv7Hdr10FallbackRebuild()) {
                rebuildAndRestartExo("dv7-fallback-setting-changed");
            }
            return;
        }
        if (!isMpv() || spec == null || TextUtils.isEmpty(spec.getUrl()) || !(engine instanceof MpvPlayerEngine mpv)) return;
        mpvOutputCoordinator.resetEvaluationState();
        mpvOutputCoordinator.setSurfaceFallbackTried(false);
        mpv.setSurfaceDirectOverride(null);
        mpv.setVulkanRenderOverride(null);
        mpv.resetDv7HandlingForNewItem();
        mpv.resetDv8HandlingForNewItem();
        mpvOutputCoordinator.prepareFelOutput(mpv);
        rebuildAndRestartMpv(null, "performance-settings-changed");
    }

    private boolean rebuildAndRestartExo(String reason) {
        if (!isExo() || spec == null || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        App.removeCallbacks(runnable);
        videoSize = null;
        initTrack = false;
        rebuildPlayer();
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE,
                "player=" + playerType + " decode=" + engine.getDecode()
                        + " exo=" + reason);
        engine.start(spec.checkUa(), position, wasPlayWhenReady);
        playbackAudioSession.start(wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private boolean rebuildAndRestartMpv(Boolean surfaceDirectOverride, String reason) {
        if (!isMpv() || spec == null || TextUtils.isEmpty(spec.getUrl()) || !(engine instanceof MpvPlayerEngine mpv)) return false;
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        mpvOutputCoordinator.resetEvaluationState();
        App.removeCallbacks(runnable);
        Boolean effectiveSurfaceDirectOverride = surfaceDirectOverride;
        if (effectiveSurfaceDirectOverride == null
                && mpvOutputCoordinator.isSurfaceFallbackTried()
                && MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO) {
            effectiveSurfaceDirectOverride = false;
        }
        mpv.setSurfaceDirectOverride(effectiveSurfaceDirectOverride);
        videoSize = null;
        initTrack = false;
        rebuildPlayer();
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        mpvPlaybackController.applyMpvAutoInitialControl();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE, "player=" + playerType + " decode=" + engine.getDecode() + " mpv-output=" + reason);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("mpv-output", "rebuild reason=%s directOverride=%s position=%d play=%s speed=%s repeat=%s spec=%s", reason, effectiveSurfaceDirectOverride, position, wasPlayWhenReady, speed, repeat, debugSpec());
        engine.start(spec.checkUa(), position, wasPlayWhenReady);
        mpvOutputCoordinator.scheduleAutoOutputEvaluation();
        playbackAudioSession.start(wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private boolean hasRequestedSubtitle(List<Track> tracks) {
        return findRequestedSubtitle(tracks) != null;
    }

    private Track findRequestedSubtitle(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return null;
        for (Track track : tracks) {
            if (track.getType() == C.TRACK_TYPE_TEXT
                    && track.isSelected() && !track.isDisabled()) return track;
        }
        return null;
    }

    private void restoreTrackSelection(List<Track> tracks) {
        if (tracks != null && !tracks.isEmpty()) engine.setTrack(tracks);
    }

    private PlayerEngine buildEngine(int type, int decode) {
        return switch (type) {
            case PlayerSetting.MPV -> new MpvPlayerEngine(decode, listener, mpvOutputCoordinator::onVideoSizeProbed);
            default -> new ExoPlayerEngine(decode, listener);
        };
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, true);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady) {
        start(spec, timeout, playWhenReady, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady, long positionMs) {
        playbackTelemetrySession.endSession("replace-start");
        clearPendingSwitchRestore();
        this.spec = spec;
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        mpvOutputCoordinator.prepareForNewItem();
        beginPlaybackTrace("start", false);
        this.playWhenReady = playWhenReady;
        retry = 0;
        playbackErrorHandler.resetLocalProxyRetry();
        hardDecodeSwitchRetryArmed = false;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, true);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, boolean playWhenReady) {
        parse(key, result, useParse, metadata, playWhenReady, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata,
                      boolean playWhenReady, long positionMs) {
        playbackTelemetrySession.endSession("replace-parse");
        stopParse();
        clearPendingSwitchRestore();
        spec = PlaySpec.fromParse(result, key, metadata, useParse);
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        mpvOutputCoordinator.prepareForNewItem();
        beginPlaybackTrace("parse", false);
        this.playWhenReady = playWhenReady;
        retry = 0;
        playbackErrorHandler.resetLocalProxyRetry();
        hardDecodeSwitchRetryArmed = false;
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private boolean reparseForPlayerSwitch(long position, float speed, boolean repeat) {
        if (spec == null || !spec.canReparse() || !spec.isParseSource()) return false;
        if (!TextUtils.isEmpty(spec.getUrl())) return false;
        Result result = spec.getParseResult();
        boolean useParse = spec.isParseUseParse();
        MediaMetadata metadata = spec.getMetadata();
        String key = spec.getKey();
        pendingSwitchRestore = true;
        pendingSwitchPositionMs = position;
        pendingSwitchSpeed = speed;
        pendingSwitchRepeat = repeat;
        stopParse();
        if (spec.isParseSource()) {
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player reparse type=%d position=%d useParse=%s spec=%s", playerType, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            refreshDirectForPlayerSwitch(result, key, metadata);
        }
        return true;
    }

    private void refreshDirectForPlayerSwitch(Result result, String key, MediaMetadata metadata) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refresh direct type=%d keyLen=%d flag=%s url=%s", playerType, safeLength(key), result.getFlag(), summarizeUrl(result.getUrl().v()));
        Task.execute(() -> {
            try {
                Result refreshed = SiteApi.playerContent(key, result.getFlag(), result.getUrl().v(), playerType);
                App.post(() -> startRefreshedSwitchResult(refreshed, key, metadata));
            } catch (Throwable e) {
                App.post(() -> {
                    clearPendingSwitchRestore();
                    callback.onError(e.getMessage());
                });
            }
        });
    }

    private void startRefreshedSwitchResult(Result result, String key, MediaMetadata metadata) {
        if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
            clearPendingSwitchRestore();
            callback.onError(result == null ? ResUtil.getString(R.string.error_play_url) : result.hasMsg() ? result.getMsg() : ResUtil.getString(R.string.error_play_url));
            return;
        }
        if (result.needParse()) {
            spec = PlaySpec.fromParse(result, key, metadata, false);
            bindPlaybackTrace();
            parseJob = ParseJob.create(this).start(result, false);
            return;
        }
        spec = PlaySpec.from(result, key, metadata);
        bindPlaybackTrace();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refreshed direct spec=%s", debugSpec());
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    private void restoreAfterSwitchReparse() {
        if (!pendingSwitchRestore) return;
        long position = pendingSwitchPositionMs;
        float speed = pendingSwitchSpeed;
        boolean repeat = pendingSwitchRepeat;
        clearPendingSwitchRestore();
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void clearPendingSwitchRestore() {
        pendingSwitchRestore = false;
        pendingSwitchPositionMs = C.TIME_UNSET;
        pendingSwitchSpeed = 1f;
        pendingSwitchRepeat = false;
    }

    public void setMediaItem() {
        playWhenReady = player == null || player.getPlayWhenReady();
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        int seq = ++prepareSeq;
        if (rejectMpvDrmMedia()) return;
        if (LocalProxyDebug.shouldAwaitReady(spec.getUrl())) {
            awaitLocalProxyAndSetMediaItem(seq, timeout);
            return;
        }
        setMediaItemNow(timeout, true);
    }

    private void awaitLocalProxyAndSetMediaItem(int seq, long timeout) {
        PlaySpec target = spec;
        String url = target.getUrl();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await start seq=%d timeout=%d spec=%s", seq, timeout, debugSpec());
        Task.execute(() -> {
            boolean ready = LocalProxyDebug.awaitReady(url, LOCAL_PROXY_READY_TIMEOUT_MS);
            App.post(() -> {
                if (seq != prepareSeq || spec != target || engine == null) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await skip seq=%d current=%d ready=%s", seq, prepareSeq, ready);
                    return;
                }
                if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await done seq=%d ready=%s spec=%s", seq, ready, debugSpec());
                setMediaItemNow(timeout, true);
            });
        });
    }

    private void setMediaItemNow(long timeout, boolean notifyPrepare) {
        if (spec == null || spec.getUrl() == null || engine == null) return;
        prepareExoFrameSchedulingForNewPlayback();
        spec.setPlaybackTraceId(playbackTrace.ensure());
        spec.refreshPlaybackRoute();
        publishPlaybackAutoContext(false);
        mpvPlaybackController.applyMpvAutoInitialControl();
        logPlaybackRoute();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "setMediaItem timeout=%d notify=%s spec=%s", timeout, notifyPrepare, debugSpec());
        exoNetworkGuardSession.reset("new-media");
        App.removeCallbacks(runnable);
        initTrack = false;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE, "player=" + playerType + " decode=" + engine.getDecode());
        long initialPositionMs = pendingInitialStartPositionMs;
        pendingInitialStartPositionMs = C.TIME_UNSET;
        engine.start(spec.checkUa(), initialPositionMs, playWhenReady);
        playbackTelemetrySession.publish();
        playbackTelemetrySession.schedule();
        mpvOutputCoordinator.scheduleAutoOutputEvaluation();
        playbackAudioSession.start(playWhenReady);
        App.post(runnable, timeout);
        if (notifyPrepare) callback.onPrepare();
    }

    private void prepareExoFrameSchedulingForNewPlayback() {
        if (!(engine instanceof ExoPlayerEngine exo)) return;
        if (!exo.prepareFrameSchedulingForNextPlayback()) return;
        rebuildPlayer(false);
    }

    private void applySubtitleStyle() {
        if (engine != null) engine.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition());
    }

    private boolean rejectMpvDrmMedia() {
        if (!isMpv() || spec == null || spec.getDrm() == null) return false;
        App.removeCallbacks(runnable);
        clearPendingSwitchRestore();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "reject drm for mpv spec=%s drm=%s", debugSpec(), spec.getDrm().getType());
        callback.onError(ResUtil.getString(R.string.error_play_mpv_drm_unsupported));
        return true;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        playbackTrace.mark(PlaybackTrace.Stage.PARSE_COMPLETE, "headers=" + (headers == null ? 0 : headers.size()));
        PlaybackTrace.log("player", playbackTrace.current(), "parseSuccess from=%s url=%s headers=%s", from, summarizeUrl(url), headers == null ? 0 : headers.size());
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    @Override
    public void onParseError() {
        clearPendingSwitchRestore();
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    private String debugSpec() {
        if (spec == null) return "null";
        return "trace=" + playbackTrace.current() +
                ", keyLen=" + safeLength(spec.getKey()) +
                ", url=" + summarizeUrl(spec.getUrl()) +
                ", format=" + spec.getFormat() +
                ", headers=" + (spec.getHeaders() == null ? 0 : spec.getHeaders().size()) +
                ", subs=" + (spec.getSubs() == null ? 0 : spec.getSubs().size());
    }

    private void beginPlaybackTrace(String reason) {
        beginPlaybackTrace(reason, true);
    }

    private void beginPlaybackTrace(String reason, boolean finishCurrentSession) {
        if (finishCurrentSession) {
            playbackTelemetrySession.endSession("replace-" + reason);
        }
        playbackBufferingTracker.reset();
        exoDecoderResourceSession.clear(true);
        playbackTrace.begin();
        long now = SystemClock.elapsedRealtime();
        playbackAutoSession = playbackAutoContextStore.beginSession(playbackTrace.current(), now);
        exoRtspLiveLagSession.beginSession(playbackAutoSession);
        mpvPlaybackController.beginSession(playbackAutoSession, now);
        playbackTrackSequence = 1;
        playbackMediaFactsCoordinator.beginSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().beginSession(playbackAutoSession);
        PlaybackSystemConditionMonitor.process().beginSession(playbackAutoSession);
        playbackTelemetrySession.beginSession(now);
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
        bindPlaybackTrace();
        playbackTrace.mark(PlaybackTrace.Stage.REQUEST, "reason=" + reason + " player=" + playerType + " decode=" + (engine == null ? -1 : engine.getDecode()));
        playbackTelemetrySession.publish();
        playbackTelemetrySession.schedule();
    }

    private void bindPlaybackTrace() {
        if (spec != null) spec.setPlaybackTraceId(playbackTrace.current());
    }


    private void clearPlaybackAutoContext() {
        mpvPlaybackController.endSession(playbackAutoSession);
        PlaybackSystemConditionMonitor.process().endSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().endSession(playbackAutoSession);
        playbackMediaFactsCoordinator.endSession(playbackAutoSession);
        playbackAutoContextStore.clear(playbackAutoSession);
        playbackAutoSession = PlaybackAutoContext.SessionToken.none();
        playbackTrackSequence = 0;
    }

    private static PlaybackAutoContext.Kernel playbackAutoKernel(int playerType) {
        return switch (PlayerSetting.sanitizePlayer(playerType)) {
            case PlayerSetting.IJK -> PlaybackAutoContext.Kernel.IJK;
            case PlayerSetting.MPV -> PlaybackAutoContext.Kernel.MPV;
            default -> PlaybackAutoContext.Kernel.EXO;
        };
    }

    private void logPlaybackRoute() {
        if (spec == null) return;
        String traceId = playbackTrace.current();
        if (traceId.equals(lastLoggedRouteTraceId)) return;
        PlaybackRoute.Resolution resolution = spec.getPlaybackRoute();
        PlaybackTrace.log("playback-route", traceId, "%s", resolution.logSummary());
        DiagnosticControls.forTrace(traceId, "input.route", "resolved-playback-route", e -> e
                .observed("inputRoute", resolution.route().name()).observed("source", resolution.owner().name())
                .observed("reason", resolution.evidence().name()).observed("input", resolution.logSummary())
                .observed("metricScope", "resolved local route; external proxy upstream is not observed"));
        lastLoggedRouteTraceId = traceId;
    }

    private static String summarizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return "scheme=" + (TextUtils.isEmpty(scheme) ? "unknown" : scheme)
                + " len=" + url.length();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private void markStartupCompletion(boolean ready, Tracks tracks) {
        if (tracks == null) return;
        boolean hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO);
        boolean hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO);
        boolean videoSubmitted = engine instanceof MpvPlayerEngine mpv
                && mpv.hasSubmittedVideoFrame();
        PlaybackStartupPolicy.Completion completion = PlaybackStartupPolicy.resolve(ready, videoSubmitted, hasVideo, hasAudio);
        if (completion == PlaybackStartupPolicy.Completion.FIRST_FRAME) {
            if (playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)) return;
            playbackTrace.mark(PlaybackTrace.Stage.FIRST_FRAME, "source=mpv-vo-submitted player=" + playerType);
        } else if (completion == PlaybackStartupPolicy.Completion.AUDIO_PLAYABLE) {
            playbackTrace.mark(PlaybackTrace.Stage.AUDIO_PLAYABLE, "source=ready player=" + playerType);
        }
    }

    private void completeMpvDirectFirstFrame(int state) {
        mpvOutputCoordinator.completeDirectFirstFrame(state);
    }

    private void recordBufferingState(int state) {
        if (player == null) return;
        if (isExo()
                && state == Player.STATE_BUFFERING
                && !playbackBufferingTracker.isBuffering()
                && PlaybackAnalyticsListener.isSeekRecoveryActive()) {
            PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                    "event=excluded phase=seek outcome=user-action");
            return;
        }
        if (mpvPlaybackController.isManagedReload()
                && state == Player.STATE_BUFFERING
                && !playbackBufferingTracker.isBuffering()) {
            PlaybackTrace.log("mpv-hls-variant",
                    playbackTrace.current(),
                    "action=managed-reload-buffering result=excluded-from-rebuffer");
            return;
        }
        boolean startupComplete = playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME) || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        PlaybackBufferingTracker.Event event = playbackBufferingTracker.update(
                state == Player.STATE_BUFFERING,
                startupComplete,
                SystemClock.elapsedRealtime(),
                state,
                currentPositionSnapshot(),
                forwardBufferedSnapshot(),
                loadingSnapshot());
        if (event == null) return;
        PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                "event=%s phase=%s outcome=%s duration=%dms count=%d total=%dms position=%d forward=%d state=%s loading=%s",
                event.type().label(), event.phase().label(), bufferingOutcome(event), event.durationMs(), event.rebufferCount(), event.rebufferTotalMs(),
                event.positionMs(), event.forwardBufferedMs(), stateName(event.playbackState()), event.loading());
    }

    private long currentPositionSnapshot() {
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long forwardBufferedSnapshot() {
        try {
            return Math.max(0, player.getTotalBufferedDuration());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean loadingSnapshot() {
        try {
            return player.isLoading();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String bufferingOutcome(PlaybackBufferingTracker.Event event) {
        if (event.type() == PlaybackBufferingTracker.Type.START) return "-";
        return switch (event.playbackState()) {
            case Player.STATE_READY -> "ready";
            case Player.STATE_ENDED -> "ended";
            case Player.STATE_IDLE -> "idle";
            default -> "left-buffering";
        };
    }

    private static String trackSummary(Tracks tracks) {
        return "video=" + tracks.containsType(C.TRACK_TYPE_VIDEO) +
                " audio=" + tracks.containsType(C.TRACK_TYPE_AUDIO) +
                " text=" + tracks.containsType(C.TRACK_TYPE_TEXT) +
                " groups=" + tracks.getGroups().size();
    }

    private static String causeChain(Throwable error) {
        if (error == null) return "null";
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getName());
            current = current.getCause();
        }
        return builder.toString();
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onReload(String msg);

        default void onPlayerRenderRequired() {
        }

        default void onPlayerOutputPending() {
        }

        default void onPlayerOutputReady() {
        }

        default void onExoFirstFrame() {
        }

        void onPlayerRebuild(Player newPlayer, boolean resetVideoSurface);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            exoNetworkGuardSession.onIsPlayingChanged(isPlaying);
            playbackTelemetrySession.publish();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "state=%s spec=%s", stateName(state), debugSpec());
            publishPlaybackAutoContext(state != Player.STATE_IDLE);
            if (state == Player.STATE_READY) {
                playbackErrorHandler.resetLocalProxyRetry();
                completeMpvDirectFirstFrame(state);
                mpvPlaybackController.onPlaybackReady(SystemClock.elapsedRealtime());
                playbackTrace.mark(PlaybackTrace.Stage.READY, "player=" + playerType);
                markStartupCompletion(true, getCurrentTracks());
                hardDecodeSwitchRetryArmed = false;
                exoNetworkGuardSession.onPlaybackStateChanged(state);
            } else {
                exoNetworkGuardSession.onPlaybackStateChanged(state);
            }
            recordBufferingState(state);
            playbackTelemetrySession.publish();
            if (state == Player.STATE_ENDED) {
                finishPlaybackProfileAbSession(
                        "ended", SystemClock.elapsedRealtime());
            } else {
                playbackTelemetrySession.schedule();
            }
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            if (isExo()) exoNetworkGuardSession.schedule(0);
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            exoRtspLiveLagSession.onPositionDiscontinuity(playbackAutoSession);
            exoNetworkGuardSession.reset("discontinuity-" + reason);
            exoNetworkGuardSession.schedule(ExoNetworkGuardController.OBSERVE_INTERVAL_MS);
            mpvPlaybackController.onPositionDiscontinuity(reason, oldPosition, newPosition);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
            publishPlaybackAutoContext(true);
            playbackTelemetrySession.publish();
            mpvOutputCoordinator.scheduleAutoOutputEvaluation();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (playbackTrackSequence < Long.MAX_VALUE) playbackTrackSequence++;
            publishPlaybackAutoContext(false);
            if (isExo()) exoNetworkGuardSession.schedule(0);
            if (!tracks.isEmpty() && !initTrack) {
                playbackTrace.mark(PlaybackTrace.Stage.TRACKS, trackSummary(tracks));
                restoreTrackSelection(Track.find(getKey()));
                if (engine instanceof MpvPlayerEngine mpv) {
                    mpv.completeInitialSubtitleTrackRestore();
                }
                callback.onTracksChanged();
                initTrack = true;
            }
            markStartupCompletion(player != null && player.getPlaybackState() == Player.STATE_READY, tracks);
            playbackTelemetrySession.publish();
            mpvOutputCoordinator.scheduleAutoOutputEvaluation();
        }

        @Override
        public void onRenderedFirstFrame() {
            // A rendered video frame proves that the media and video decoder are
            // working even if a slow audio track keeps Exo in BUFFERING briefly.
            // Do not let the generic startup timer turn that valid playback into
            // a false connection-timeout error.
            if (isExo()) App.removeCallbacks(runnable);
            playbackTrace.mark(PlaybackTrace.Stage.FIRST_FRAME,
                    "source=" + (isMpv() ? "mpv-vo-submitted" : "media3") + " player=" + playerType);
            if (isMpv()) {
                mpvOutputCoordinator.setFrameReady(true);
                callback.onPlayerOutputReady();
            }
            publishPlaybackAutoContext(true);
            playbackTelemetrySession.publish();
            if (isExo()) callback.onExoFirstFrame();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onTitlesChanged();
        }

        @Override
        public void onMediaChaptersChanged(@NonNull List<MediaChapter> chapters) {
            callback.onTitlesChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            playbackErrorHandler.handlePlayerError(e);
        }
    };

    private PlaybackRoute.Resolution getEffectivePlaybackRoute() {
        PlaybackRoute.Resolution route = engine == null ? null : engine.getEffectivePlaybackRoute();
        if (route != null && route.route() != PlaybackRoute.OTHER) return route;
        return spec == null ? PlaybackRoute.resolve(null) : spec.getPlaybackRoute();
    }

    private boolean handleExoDecoderResourcesReclaimed(PlaybackException error) {
        return exoDecoderResourceSession.handleExoDecoderResourcesReclaimed(error);
    }



    private final class MpvHost implements MpvPlaybackController.Host {
        @Override
        public MpvPlayerEngine getMpvEngine() {
            return engine instanceof MpvPlayerEngine mpv ? mpv : null;
        }

        @Override
        public boolean isMpv() {
            return PlayerManager.this.isMpv();
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public PlaybackAutoContextStore getAutoContextStore() {
            return playbackAutoContextStore;
        }

        @Override
        public PlaybackTelemetryCoordinator getTelemetryCoordinator() {
            return playbackTelemetrySession.getCoordinator();
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public PlaySpec getSpec() {
            return spec;
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public float getSpeed() {
            return PlayerManager.this.getSpeed();
        }

        @Override
        public int getRebufferCount() {
            return PlayerManager.this.getRebufferCount();
        }

        @Override
        public boolean isRepeatOne() {
            return PlayerManager.this.isRepeatOne();
        }

        @Override
        public boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action) {
            return experimentAllowed(action);
        }

        @Override
        public boolean restartForMpvHlsVariant(long position, boolean wasPlayWhenReady, float speed, boolean repeat) {
            if (spec == null || engine == null || player == null) return false;
            prepareSeq++;
            App.removeCallbacks(runnable);
            initTrack = false;
            playWhenReady = wasPlayWhenReady;
            engine.restart(spec.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            return true;
        }
    }

    private final class ExoNetworkGuardHost implements ExoNetworkGuardSession.Host {
        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public boolean isExo() {
            return PlayerManager.this.isExo();
        }

        @Override
        public boolean isVod() {
            return PlayerManager.this.isVod();
        }

        @Override
        public float getUserPlaybackSpeed() {
            return userPlaybackSpeed;
        }

        @Override
        public AudioPlaybackDiagnostics.OutputMode getAudioOutputMode() {
            return getAudioPlaybackDiagnostics().outputMode();
        }

        @Override
        public PlaybackRoute.Resolution getEffectivePlaybackRoute() {
            return PlayerManager.this.getEffectivePlaybackRoute();
        }

        @Override
        public androidx.media3.common.Format getVideoFormat() {
            return PlayerManager.this.getVideoFormat();
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action) {
            return experimentAllowed(action);
        }

        @Override
        public PlaybackExperimentCoordinator.Token captureExperimentToken(PlaybackExperimentPolicy.Action action) {
            return playbackExperimentCoordinator.capture(action);
        }

        @Override
        public boolean isCurrentExperimentToken(PlaybackExperimentCoordinator.Token token) {
            return playbackExperimentCoordinator.isCurrent(token);
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public void invalidatePlaybackProfileAssessments(PlaybackProfileAbCoordinator.InvalidationReason reason) {
            playbackTelemetrySession.invalidate(reason);
        }

        @Override
        public void publishTelemetryDecision(PlaybackAutoContext.SessionToken session, PlaybackTelemetry.DecisionEvent event, long nowMs) {
            playbackTelemetrySession.getCoordinator().publishDecision(session, event, nowMs);
        }
    }


    private final class ExoRtspLiveLagHost implements ExoRtspLiveLagSession.Host {
        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public PlayerEngine getEngine() {
            return engine;
        }

        @Override
        public boolean isExo() {
            return PlayerManager.this.isExo();
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public PlaybackResourceClassifier.Classification currentResourceClassification() {
            return PlayerManager.this.currentResourceClassification();
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action) {
            return experimentAllowed(action);
        }

        @Override
        public boolean isStartupComplete() {
            return playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                    || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        }

        @Override
        public boolean restartExoRtspLiveSession() {
            return PlayerManager.this.restartExoRtspLiveSession();
        }

        @Override
        public void publishTelemetryDecision(PlaybackAutoContext.SessionToken session, PlaybackTelemetry.DecisionEvent event, long nowMs) {
            playbackTelemetrySession.getCoordinator().publishDecision(session, event, nowMs);
        }
    }



    private void invalidatePlaybackProfileAssessments(PlaybackProfileAbCoordinator.InvalidationReason reason) {
        playbackTelemetrySession.invalidate(reason);
    }

    private void finishPlaybackProfileAbSession(String reason, long nowElapsedMs) {
        playbackTelemetrySession.finishProfileAbSession(reason, nowElapsedMs);
    }

    private PlaybackResourceClassifier.Classification currentResourceClassification() {
        PlaybackResourceClassifier.Classification request = PlaybackResourceClassifier.classifyRequest(
                spec == null ? null : spec.getUrl(),
                spec == null ? null : spec.getFormat(),
                spec == null ? null : spec.getFormat());
        PlaybackResourceClassifier.Classification observed =
                engine == null ? null : engine.getResourceClassification();
        return PlaybackResourceClassifier.merge(request, observed);
    }

    private void publishPlaybackAutoContext(boolean acceptDecoder) {
        if (spec == null || engine == null || !playbackAutoSession.active()) return;
        PlaybackResourceClassifier.Classification classification = currentResourceClassification();
        PlaybackRoute.Resolution observedRoute = engine.getEffectivePlaybackRoute();
        if (observedRoute == null || observedRoute.route() == PlaybackRoute.OTHER) observedRoute = spec.getPlaybackRoute();
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel = PlaybackAutoContext.Fact.forSession(
                PlaybackTelemetrySession.playbackAutoKernel(playerType), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decode = PlaybackAutoContext.Fact.forSession(
                engine.isHard() ? PlaybackAutoContext.DecodeMode.HARDWARE : PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.PathFacts path = classification.toPathFacts(observedRoute, now);
        PlaybackAutoContext.ResourceFacts resource = classification.toResourceFacts(now);
        if (!playbackAutoContextStore.publishPlaybackFacts(playbackAutoSession, kernel, decode, resource, path, now)) return;
        try {
            playbackMediaFactsCoordinator.publishEngineFacts(
                    playbackAutoSession,
                    playbackTrackSequence,
                    engine.getPlaybackFactsSnapshot(),
                    acceptDecoder,
                    now);
        } catch (Throwable error) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(),
                    "media facts unavailable type=%s action=keep-partial", error.getClass().getSimpleName());
        }
        PlaybackAutoContext snapshot = playbackAutoContextStore.snapshot();
        if (playbackAutoSession.equals(snapshot.session())) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(), "%s", snapshot.logSummary());
            PlaybackTrace.log("playback-auto-resource", playbackTrace.current(), "%s", classification.logSummary());
        }
    }

    private boolean restartExoRtspLiveSession() {
        if (!(engine instanceof ExoPlayerEngine)
                || player == null
                || spec == null
                || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        prepareSeq++;
        App.removeCallbacks(runnable);
        exoNetworkGuardSession.reset("rtsp-live-rebuild");
        initTrack = false;
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        PlaybackTrace.log("exo-rtsp-live", playbackTrace.current(),
                "action=rebuild-session play=%s speed=%.3f repeat=%s",
                wasPlayWhenReady, speed, repeat);
        engine.restart(spec.checkUa(), C.TIME_UNSET, wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        callback.onPrepare();
        return true;
    }

    private final class PlaybackTelemetryHost implements PlaybackTelemetrySession.Host {
        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public PlayerEngine getEngine() {
            return engine;
        }

        @Override
        public int getPlayerType() {
            return playerType;
        }

        @Override
        public boolean hasSpec() {
            return spec != null;
        }

        @Override
        public boolean isExo() {
            return PlayerManager.this.isExo();
        }

        @Override
        public boolean isMpv() {
            return PlayerManager.this.isMpv();
        }

        @Override
        public float getUserPlaybackSpeed() {
            return userPlaybackSpeed;
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public PlaybackAutoContextStore getAutoContextStore() {
            return playbackAutoContextStore;
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public Format getVideoFormat() {
            return PlayerManager.this.getVideoFormat();
        }

        @Override
        public int getRebufferCount() {
            return PlayerManager.this.getRebufferCount();
        }

        @Override
        public long getRebufferTotalMs(long now) {
            return PlayerManager.this.getRebufferTotalMs(now);
        }

        @Override
        public void onMpvEvaluateRuntime(PlaybackTelemetry.RuntimeObservation observation, boolean evaluateMpvHlsVariant, long now) {
            mpvPlaybackController.evaluateRuntime(observation, evaluateMpvHlsVariant, now);
        }

        @Override
        public void onExoRtspEvaluate(PlaybackTelemetry.RuntimeObservation observation, long now) {
            exoRtspLiveLagSession.evaluate(observation, now);
        }
    }


    private final class PlaybackErrorHost implements PlaybackErrorHandler.Host {
        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public PlayerEngine getEngine() {
            return engine;
        }

        @Override
        public PlaySpec getSpec() {
            return spec;
        }

        @Override
        public int getPlayerType() {
            return playerType;
        }

        @Override
        public int getRetry() {
            return retry;
        }

        @Override
        public long getPosition() {
            return PlayerManager.this.getPosition();
        }

        @Override
        public float getSpeed() {
            return PlayerManager.this.getSpeed();
        }

        @Override
        public boolean isRepeatOne() {
            return PlayerManager.this.isRepeatOne();
        }

        @Override
        public boolean isHardDecodeSwitchRetryArmed() {
            return hardDecodeSwitchRetryArmed;
        }

        @Override
        public void setHardDecodeSwitchRetryArmed(boolean armed) {
            hardDecodeSwitchRetryArmed = armed;
        }

        @Override
        public boolean isMpvSurfaceDirect() {
            return PlayerManager.this.isMpvSurfaceDirect();
        }

        @Override
        public boolean isDv7NativeAttemptRequested() {
            return mpvOutputCoordinator.isDv7NativeAttemptRequested();
        }

        @Override
        public boolean isMpvSurfaceFallbackTried() {
            return mpvOutputCoordinator.isSurfaceFallbackTried();
        }

        @Override
        public void setMpvSurfaceFallbackTried(boolean tried) {
            mpvOutputCoordinator.setSurfaceFallbackTried(tried);
        }

        @Override
        public void setMpvAutoOutputEvaluated(boolean evaluated) {
            mpvOutputCoordinator.setEvaluated(evaluated);
        }

        @Override
        public void incrementMpvOutputEvaluationSeq() {
            mpvOutputCoordinator.resetEvaluationState();
        }

        @Override
        public boolean isMpvVulkanFallbackTried() {
            return mpvOutputCoordinator.isVulkanFallbackTried();
        }

        @Override
        public void setMpvVulkanFallbackTried(boolean tried) {
            mpvOutputCoordinator.setVulkanFallbackTried(tried);
        }

        @Override
        public boolean isMpvAutoVulkanPinnedForItem() {
            return false;
        }

        @Override
        public void setMpvAutoVulkanPinnedForItem(boolean pinned) {
        }

        @Override
        public void setMpvAutoVulkanDisabledForItem(boolean disabled) {
        }

        @Override
        public int getVideoWidth() {
            return PlayerManager.this.getVideoWidth();
        }

        @Override
        public int getVideoHeight() {
            return PlayerManager.this.getVideoHeight();
        }

        @Override
        public PlaybackRoute.Resolution getEffectivePlaybackRoute() {
            return PlayerManager.this.getEffectivePlaybackRoute();
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action) {
            return experimentAllowed(action);
        }

        @Override
        public boolean handleExoDecoderResourcesReclaimed(PlaybackException e) {
            return PlayerManager.this.handleExoDecoderResourcesReclaimed(e);
        }

        @Override
        public void onRtspPlaybackError(PlaybackAutoContext.SessionToken session) {
            exoRtspLiveLagSession.onPlaybackError(session);
        }

        @Override
        public void publishTelemetryError() {
            playbackTelemetrySession.publish(PlaybackAutoContext.PlaybackPhase.ERROR, false);
        }

        @Override
        public boolean recoverMpvHlsVariantError() {
            return mpvPlaybackController.recoverMpvHlsVariantError();
        }

        @Override
        public void finishPlaybackProfileAbSession(String reason, long nowElapsedMs) {
            playbackTelemetrySession.finishProfileAbSession(reason, nowElapsedMs);
        }

        @Override
        public void onReloadCallback(String message) {
            callback.onReload(message);
        }

        @Override
        public void onErrorCallback(String message) {
            callback.onError(message);
        }

        @Override
        public void onPrepareCallback() {
            callback.onPrepare();
        }

        @Override
        public void setSpeed(float speed) {
            PlayerManager.this.setSpeed(speed);
        }

        @Override
        public void setRepeatOne(boolean repeat) {
            PlayerManager.this.setRepeatOne(repeat);
        }

        @Override
        public void setMediaItem() {
            PlayerManager.this.setMediaItem();
        }

        @Override
        public void applySubtitleStyle() {
            PlayerManager.this.applySubtitleStyle();
        }

        @Override
        public void rebuildPlayer(boolean reuseSurface) {
            PlayerManager.this.rebuildPlayer(reuseSurface);
        }

        @Override
        public void scheduleMpvAutoOutputEvaluation() {
            mpvOutputCoordinator.scheduleAutoOutputEvaluation();
        }

        @Override
        public int nextPrepareSeq() {
            return ++prepareSeq;
        }

        @Override
        public int getPrepareSeq() {
            return prepareSeq;
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            PlayerManager.this.playWhenReady = playWhenReady;
        }

        @Override
        public void setInitTrack(boolean initTrack) {
            PlayerManager.this.initTrack = initTrack;
        }

        @Override
        public void resetPlaybackTimeout() {
            App.removeCallbacks(runnable);
        }

        @Override
        public void schedulePlaybackTimeout(long timeoutMs) {
            App.post(runnable, timeoutMs);
        }

        @Override
        public boolean rebuildAndRestartMpv(Boolean surfaceDirect, String reason) {
            return PlayerManager.this.rebuildAndRestartMpv(surfaceDirect, reason);
        }

        @Override
        public PlayerEngine buildEngine(int playerType, int decode) {
            return PlayerManager.this.buildEngine(playerType, decode);
        }

        @Override
        public void switchPlayer(int type, boolean persist) {
            PlayerManager.this.switchPlayer(type, persist);
        }

        @Override
        public void setEngineAndPlayer(PlayerEngine engine, Player player, boolean notifyRebuild) {
            PlayerManager.this.engine = engine;
            PlayerManager.this.player = player;
            if (notifyRebuild) callback.onPlayerRebuild(player, true);
        }

        @Override
        public String formatCauseChain(Throwable error) {
            return causeChain(error);
        }

        @Override
        public String getDebugSpec() {
            return debugSpec();
        }
    }

    private final class PlaybackAudioHost implements PlaybackAudioSession.Host {
        @Override
        public boolean isNativePlayer() {
            return PlayerManager.this.isNativePlayer();
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public int getPlayerType() {
            return playerType;
        }

        @Override
        public String getPlaybackTraceId() {
            return PlayerManager.this.getPlaybackTraceId();
        }
    }

    private final class ExoDecoderResourceHost implements ExoDecoderResourceSession.Host {
        @Override
        public boolean isExoEngine() {
            return engine instanceof ExoPlayerEngine;
        }

        @Override
        public ExoPlayerEngine getExoEngine() {
            return engine instanceof ExoPlayerEngine exo ? exo : null;
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public PlaySpec getSpec() {
            return spec;
        }

        @Override
        public long getPosition() {
            return PlayerManager.this.getPosition();
        }

        @Override
        public float getSpeed() {
            return PlayerManager.this.getSpeed();
        }

        @Override
        public boolean isRepeatOne() {
            return PlayerManager.this.isRepeatOne();
        }

        @Override
        public long getTextOffsetMs() {
            return PlayerManager.this.getTextOffsetMs();
        }

        @Override
        public long getAudioOffsetMs() {
            return PlayerManager.this.getAudioOffsetMs();
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public int getPrepareSeq() {
            return prepareSeq;
        }

        @Override
        public int nextPrepareSeq() {
            return ++prepareSeq;
        }

        @Override
        public void resetNetworkGuard(String reason) {
            exoNetworkGuardSession.reset(reason);
        }

        @Override
        public void rebuildPlayer(boolean resetVideoSurface) {
            PlayerManager.this.rebuildPlayer(resetVideoSurface);
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            PlayerManager.this.playWhenReady = playWhenReady;
        }

        @Override
        public void setInitTrack(boolean initTrack) {
            PlayerManager.this.initTrack = initTrack;
        }

        @Override
        public void applySubtitleStyle() {
            PlayerManager.this.applySubtitleStyle();
        }

        @Override
        public void setSpeed(float speed) {
            PlayerManager.this.setSpeed(speed);
        }

        @Override
        public void setRepeatOne(boolean repeat) {
            PlayerManager.this.setRepeatOne(repeat);
        }

        @Override
        public void setTextOffsetMs(long offsetMs) {
            PlayerManager.this.setTextOffsetMs(offsetMs);
        }

        @Override
        public void setAudioOffsetMs(long offsetMs) {
            PlayerManager.this.setAudioOffsetMs(offsetMs);
        }

        @Override
        public void resetPlaybackTimeout() {
            App.removeCallbacks(runnable);
        }

        @Override
        public void schedulePlaybackTimeout(long timeoutMs) {
            App.post(runnable, timeoutMs);
        }

        @Override
        public void onPrepareCallback() {
            callback.onPrepare();
        }

        @Override
        public void disarmHardDecodeSwitchRetry() {
            hardDecodeSwitchRetryArmed = false;
        }
    }

    private final class MpvOutputHost implements MpvOutputCoordinator.Host {
        @Override
        public MpvPlayerEngine getMpvEngine() {
            return engine instanceof MpvPlayerEngine mpv ? mpv : null;
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public PlaySpec getSpec() {
            return spec;
        }

        @Override
        public int getVideoWidth() {
            return PlayerManager.this.getVideoWidth();
        }

        @Override
        public int getVideoHeight() {
            return PlayerManager.this.getVideoHeight();
        }

        @Override
        public Tracks getCurrentTracks() {
            return engine == null ? null : engine.getCurrentTracks();
        }

        @Override
        public Format getVideoFormat() {
            return engine == null ? null : engine.getVideoFormat();
        }

        @Override
        public PlayerEngine.VideoPlaybackDetails getVideoPlaybackDetails() {
            return engine == null ? null : engine.getVideoPlaybackDetails();
        }

        @Override
        public boolean isHardDecode() {
            return engine != null && engine.isHard();
        }

        @Override
        public boolean isManagedReload() {
            return mpvPlaybackController.isManagedReload();
        }

        @Override
        public boolean isSurfaceDirect() {
            return isMpvSurfaceDirect();
        }

        @Override
        public String getKey() {
            return PlayerManager.this.getKey();
        }

        @Override
        public PlaybackTrace getPlaybackTrace() {
            return playbackTrace;
        }

        @Override
        public PlaybackAutoContext.SessionToken getPlaybackAutoSession() {
            return playbackAutoSession;
        }

        @Override
        public PlaybackTelemetryCoordinator getTelemetryCoordinator() {
            return playbackTelemetrySession.getCoordinator();
        }

        @Override
        public boolean rebuildAndRestartMpv(Boolean surfaceDirect, String reason) {
            return PlayerManager.this.rebuildAndRestartMpv(surfaceDirect, reason);
        }

        @Override
        public void rebuildPlayer() {
            PlayerManager.this.rebuildPlayer();
        }

        @Override
        public void onPlayerOutputPending() {
            callback.onPlayerOutputPending();
        }

        @Override
        public void onPlayerOutputReady() {
            callback.onPlayerOutputReady();
        }
    }

}
