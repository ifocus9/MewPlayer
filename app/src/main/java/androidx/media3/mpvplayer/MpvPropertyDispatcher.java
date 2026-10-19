package androidx.media3.mpvplayer;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;


import org.json.JSONException;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.Map;

/**
 * Dispatches raw native property change events from MPVLib to individual subsystems,
 * and tracks audio/video playback metrics, frame rates, and decoding diagnostics.
 */
public final class MpvPropertyDispatcher {

    public interface Host {
        boolean hasMediaItem();
        boolean isFileLoaded();
        boolean isStopping();
        boolean isEofReached();
        boolean isPlaybackRestarted();
        boolean isPlayWhenReady();
        boolean isInitialTrackSelectionGateActive();
        int getPlaybackState();
        void setPlaybackState(int state);
        boolean isLoading();
        void setLoading(boolean loading);
        void setEofReached(boolean eofReached);
        void setIdleActive(boolean idleActive);
        void markPlaybackEnded(String reason);
        void invalidateState();
        boolean shouldPauseNativePlayback();
        void safeSetPropertyBoolean(String name, boolean value);
        String getPlaybackTraceId();

        long getCachedPositionMs();
        void setCachedPositionMs(long positionMs);
        long getCachedDurationMs();
        void setCachedDurationMs(long durationMs);
        long stabilizedPositionMs(long rawPositionMs);
        long doubleSecondsToMs(@Nullable Object value, long fallback);

        void updateHlsAdBoundary(long positionMs);
        void maybeSkipHlsAd();
        boolean handleHlsAdBoundaryEof();

        void recordCacheObservedProperty(String property, @Nullable Object value);
        void recordCacheMetric(String property, @Nullable Object value, long nowMs);
        void handleDemuxerCacheProperty(String property, @Nullable Object value);

        boolean isDiscNavigationActive();
        void onDiscMenuActiveChanged(boolean active);
        void refreshChapters();
        void handleChapterListProperty(Object value);

        void updateVideoSize(String reason);
        VideoSize getVideoSize();
        void scheduleTrackRefresh(String reason);
        float videoFrameRate();
        void onContentFrameRateChanged(float frameRate);
        boolean reportFirstSubmittedVideoFrame();
        void onFelBindProbeObserved(String text);
        void onMsgLevelChanged(String level);
        void setCustomButtonStates(String states);
    }

    private final Host host;

    // Video metadata & timing cache
    private final Map<String, Integer> cachedVideoIntProperties = new HashMap<>();
    private double cachedContainerFps;
    private double cachedEstimatedVfFps;
    private float cachedContentFrameRate;

    // Video rendering & hardware decoder metrics
    private String cachedCurrentVo = "";
    private boolean observedCurrentVo;
    private String cachedHwdecCurrent = "";
    private boolean observedHwdecCurrent;
    private String cachedCurrentGpuContext = "";
    private String cachedGpuApi = "";
    private boolean androidFelActive;
    private boolean videoFrameSubmitted;

    // Audio format & decoder metrics
    private String cachedCurrentAo = "";
    private String cachedAudioDevice = "";
    private String cachedAudioFormat = "";
    private int cachedAudioChannels;
    private int cachedAudioSampleRate;
    private String cachedAudioOutFormat = "";
    private int cachedAudioOutChannels;
    private int cachedAudioOutSampleRate;
    private String cachedAudioDecoder = "";
    private String cachedAudioCodecProfile = "";

    // Sync & frame drop telemetry
    private double cachedAvSyncSeconds;
    private double cachedDisplayFps;
    private double cachedEstimatedDisplayFps;
    private long cachedDecoderDroppedFrames;
    private long cachedOutputDroppedFrames;
    private boolean observedDroppedFrames;
    private long cachedMistimedFrames;
    private long cachedDelayedFrames;
    private boolean cachedDisplaySyncActive;

    public MpvPropertyDispatcher(Host host) {
        this.host = host;
    }

    public synchronized void resetVideoMetadataCache() {
        cachedVideoIntProperties.clear();
        cachedContainerFps = 0;
        cachedEstimatedVfFps = 0;
        cachedContentFrameRate = 0;
    }

    public synchronized void resetPlaybackMetrics() {
        androidFelActive = false;
        videoFrameSubmitted = false;
    }

    public synchronized void setAndroidFelActive(boolean active) {
        this.androidFelActive = active;
    }

    public synchronized boolean isAndroidFelActive() {
        return androidFelActive;
    }

    public synchronized boolean isVideoFrameSubmitted() {
        return videoFrameSubmitted;
    }

    public synchronized int cachedVideoIntProperty(String property, int fallback) {
        Integer value = cachedVideoIntProperties.get(property);
        return value == null ? fallback : value;
    }

    public synchronized double getCachedContainerFps() {
        return cachedContainerFps;
    }

    public synchronized double getCachedEstimatedVfFps() {
        return cachedEstimatedVfFps;
    }

    public synchronized float getCachedContentFrameRate() {
        return cachedContentFrameRate;
    }

    public synchronized String getCachedCurrentVo() {
        return cachedCurrentVo;
    }

    public synchronized boolean isObservedCurrentVo() {
        return observedCurrentVo;
    }

    public synchronized String getCachedHwdecCurrent() {
        return cachedHwdecCurrent;
    }

    public synchronized boolean isObservedHwdecCurrent() {
        return observedHwdecCurrent;
    }

    public synchronized String getCachedCurrentGpuContext() {
        return cachedCurrentGpuContext;
    }

    public synchronized String getCachedGpuApi() {
        return cachedGpuApi;
    }

    public synchronized String getCachedCurrentAo() {
        return cachedCurrentAo;
    }

    public synchronized String getCachedAudioDevice() {
        return cachedAudioDevice;
    }

    public synchronized String getCachedAudioFormat() {
        return cachedAudioFormat;
    }

    public synchronized int getCachedAudioChannels() {
        return cachedAudioChannels;
    }

    public synchronized int getCachedAudioSampleRate() {
        return cachedAudioSampleRate;
    }

    public synchronized String getCachedAudioOutFormat() {
        return cachedAudioOutFormat;
    }

    public synchronized int getCachedAudioOutChannels() {
        return cachedAudioOutChannels;
    }

    public synchronized int getCachedAudioOutSampleRate() {
        return cachedAudioOutSampleRate;
    }

    public synchronized String getCachedAudioDecoder() {
        return cachedAudioDecoder;
    }

    public synchronized String getCachedAudioCodecProfile() {
        return cachedAudioCodecProfile;
    }

    public synchronized double getCachedAvSyncSeconds() {
        return cachedAvSyncSeconds;
    }

    public synchronized double getCachedDisplayFps() {
        return cachedDisplayFps;
    }

    public synchronized double getCachedEstimatedDisplayFps() {
        return cachedEstimatedDisplayFps;
    }

    public synchronized long getCachedDecoderDroppedFrames() {
        return cachedDecoderDroppedFrames;
    }

    public synchronized long getCachedOutputDroppedFrames() {
        return cachedOutputDroppedFrames;
    }

    public synchronized boolean isObservedDroppedFrames() {
        return observedDroppedFrames;
    }

    public synchronized long getCachedMistimedFrames() {
        return cachedMistimedFrames;
    }

    public synchronized long getCachedDelayedFrames() {
        return cachedDelayedFrames;
    }

    public synchronized boolean isCachedDisplaySyncActive() {
        return cachedDisplaySyncActive;
    }

    public synchronized void updateFelRenderMetrics(String vo, boolean observedVo, String gpuContext) {
        this.cachedCurrentVo = vo;
        this.observedCurrentVo = observedVo;
        this.cachedCurrentGpuContext = gpuContext;
    }

    public synchronized void refreshRenderState(String vo, String gpuContext, String gpuApi) {
        if (!TextUtils.isEmpty(vo)) this.cachedCurrentVo = vo;
        if (!TextUtils.isEmpty(gpuContext)) this.cachedCurrentGpuContext = gpuContext;
        if (!TextUtils.isEmpty(gpuApi)) this.cachedGpuApi = gpuApi;
    }

    public synchronized void refreshRuntimeDiagnostics(
            String ao, String audioDevice, String hwdec,
            double avSync, double displayFps, double estDisplayFps,
            long decDrops, long outDrops, long mistimed, long delayed,
            boolean displaySyncActive) {
        if (!TextUtils.isEmpty(ao)) this.cachedCurrentAo = ao;
        if (!TextUtils.isEmpty(audioDevice)) this.cachedAudioDevice = audioDevice;
        if (!TextUtils.isEmpty(hwdec)) this.cachedHwdecCurrent = hwdec;
        this.cachedAvSyncSeconds = avSync;
        this.cachedDisplayFps = displayFps;
        this.cachedEstimatedDisplayFps = estDisplayFps;
        this.cachedDecoderDroppedFrames = decDrops;
        this.cachedOutputDroppedFrames = outDrops;
        this.cachedMistimedFrames = mistimed;
        this.cachedDelayedFrames = delayed;
        this.cachedDisplaySyncActive = displaySyncActive;
    }

    public synchronized void resetRuntimeDiagnostics() {
        observedCurrentVo = false;
        observedHwdecCurrent = false;
        cachedCurrentVo = null;
        androidFelActive = false;
        videoFrameSubmitted = false;
        cachedCurrentGpuContext = null;
        cachedGpuApi = null;
        cachedCurrentAo = null;
        cachedAudioDevice = null;
        cachedAudioFormat = null;
        cachedAudioOutFormat = null;
        cachedAudioDecoder = null;
        cachedAudioCodecProfile = null;
        cachedAudioChannels = 0;
        cachedAudioSampleRate = 0;
        cachedAudioOutChannels = 0;
        cachedAudioOutSampleRate = 0;
        cachedHwdecCurrent = null;
        cachedAvSyncSeconds = 0;
        cachedDisplayFps = 0;
        cachedEstimatedDisplayFps = 0;
        cachedContainerFps = 0;
        cachedEstimatedVfFps = 0;
        cachedContentFrameRate = 0;
        cachedDecoderDroppedFrames = 0;
        cachedOutputDroppedFrames = 0;
        observedDroppedFrames = false;
        cachedMistimedFrames = 0;
        cachedDelayedFrames = 0;
        cachedDisplaySyncActive = false;
    }

    public synchronized String formatAvSync() {
        long ms = Math.round(cachedAvSyncSeconds * 1000.0);
        return "A-V " + ms + "ms";
    }

    public synchronized String formatDisplayFps() {
        if (cachedDisplayFps <= 0 && cachedEstimatedDisplayFps <= 0) return "";
        if (cachedDisplayFps > 0 && cachedEstimatedDisplayFps > 0 && Math.abs(cachedDisplayFps - cachedEstimatedDisplayFps) >= 0.01) {
            return "刷新 " + formatHz(cachedDisplayFps) + "/估" + formatHz(cachedEstimatedDisplayFps);
        }
        return "刷新 " + formatHz(cachedDisplayFps > 0 ? cachedDisplayFps : cachedEstimatedDisplayFps);
    }

    public synchronized String formatDroppedFrames() {
        long total = cachedDecoderDroppedFrames + cachedOutputDroppedFrames;
        if (total <= 0 && cachedMistimedFrames <= 0 && cachedDelayedFrames <= 0) return "";
        return joinParts(
                total > 0 ? "掉帧 dec " + cachedDecoderDroppedFrames + "/out " + cachedOutputDroppedFrames : "",
                cachedMistimedFrames > 0 ? "mistimed " + cachedMistimedFrames : "",
                cachedDelayedFrames > 0 ? "delayed " + cachedDelayedFrames : "");
    }

    public synchronized String formatDisplaySync() {
        return cachedDisplaySyncActive ? "display-sync 开" : "";
    }

    private static String formatHz(double value) {
        if (value <= 0) return "-";
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.01) return String.valueOf((long) rounded) + "Hz";
        return String.format(java.util.Locale.US, "%.2fHz", value);
    }

    private static String joinParts(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) return "";
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(value);
        }
        return builder.toString();
    }

    public synchronized void dispatch(String property, @Nullable Object value) {
        if ("options/msg-level".equals(property) && value instanceof String text) {
            host.onMsgLevelChanged(text);
        }
        if (MpvConfigStore.CUSTOM_BUTTON_STATE_PROPERTY.equals(property)) {
            String states = "";
            if (value instanceof String json) {
                try {
                    Object decoded = new JSONTokener(json).nextValue();
                    if (decoded instanceof String ids) states = ids;
                } catch (JSONException ignored) {
                }
            }
            host.setCustomButtonStates(states);
            return;
        }
        host.recordCacheObservedProperty(property, value);
        if (!host.hasMediaItem()) {
            host.setPlaybackState(Player.STATE_IDLE);
            host.setLoading(false);
            return;
        }
        host.recordCacheMetric(property, value, SystemClock.elapsedRealtime());
        cacheObservedVideoProperty(property, value);
        boolean stateChanged = false;
        switch (property) {
            case "time-pos", "time-pos/full" -> {
                long pos = host.stabilizedPositionMs(host.doubleSecondsToMs(value, host.getCachedPositionMs()));
                host.setCachedPositionMs(pos);
                host.updateHlsAdBoundary(pos);
                host.maybeSkipHlsAd();
            }
            case "duration", "duration/full" -> {
                long durationMs = host.doubleSecondsToMs(value, host.getCachedDurationMs());
                if (durationMs != host.getCachedDurationMs()) {
                    host.setCachedDurationMs(durationMs);
                    stateChanged = true;
                }
            }
            case "demuxer-cache-duration", "demuxer-cache-state/cache-duration",
                    "demuxer-cache-time", "demuxer-cache-state/cache-end",
                    "demuxer-cache-state/reader-pts",
                    "cache-speed", "demuxer-cache-state/raw-input-rate",
                    "cache-buffering-state",
                    "demuxer-cache-state/fw-bytes",
                    "demuxer-cache-state/total-bytes",
                    "demuxer-cache-state/file-cache-bytes",
                    "demuxer-cache-idle", "demuxer-cache-state/idle",
                    "demuxer-cache-state/underrun",
                    "demuxer-cache-state/bof-cached",
                    "demuxer-cache-state/eof-cached" -> host.handleDemuxerCacheProperty(property, value);
            case "pause" -> {
                if (value instanceof Boolean paused) {
                    boolean activeMedia = host.isFileLoaded()
                            && !host.isStopping()
                            && !host.isEofReached()
                            && host.getPlaybackState() != Player.STATE_IDLE
                            && host.getPlaybackState() != Player.STATE_ENDED;
                    boolean effectivePlayWhenReady = host.isPlayWhenReady()
                            && !host.isInitialTrackSelectionGateActive();
                    MpvPauseIntentPolicy.Action action = MpvPauseIntentPolicy.resolve(
                            effectivePlayWhenReady, paused, activeMedia);
                    if (action == MpvPauseIntentPolicy.Action.REASSERT_REQUESTED_STATE) {
                        boolean requestedPaused = host.shouldPauseNativePlayback();
                        PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                                "pause intent reconcile observed=%s requested=%s state=%d",
                                paused, requestedPaused, host.getPlaybackState());
                        host.safeSetPropertyBoolean("pause", requestedPaused);
                    }
                }
            }
            case "paused-for-cache" -> {
                boolean nextLoading = Boolean.TRUE.equals(value);
                int nextPlaybackState = host.getPlaybackState();
                if (nextLoading) nextPlaybackState = Player.STATE_BUFFERING;
                else if (host.getPlaybackState() == Player.STATE_BUFFERING && host.isFileLoaded() && host.isPlaybackRestarted()) {
                    nextPlaybackState = Player.STATE_READY;
                }
                stateChanged = host.isLoading() != nextLoading || host.getPlaybackState() != nextPlaybackState;
                host.setLoading(nextLoading);
                host.setPlaybackState(nextPlaybackState);
            }
            case "eof-reached" -> {
                if (Boolean.TRUE.equals(value) && host.handleHlsAdBoundaryEof()) break;
                boolean wasEnded = host.getPlaybackState() == Player.STATE_ENDED;
                boolean terminalEof = MpvDiscMenuPolicy.isTerminalEof(
                        Boolean.TRUE.equals(value), host.isDiscNavigationActive());
                host.setEofReached(terminalEof);
                if (terminalEof) host.markPlaybackEnded("property:eof-reached");
                stateChanged = !wasEnded && host.getPlaybackState() == Player.STATE_ENDED;
            }
            case "idle-active" -> {
                boolean wasEnded = host.getPlaybackState() == Player.STATE_ENDED;
                boolean idle = Boolean.TRUE.equals(value);
                host.setIdleActive(idle);
                if (idle && host.isFileLoaded() && !host.isStopping()) {
                    host.markPlaybackEnded("property:idle-active");
                }
                stateChanged = !wasEnded && host.getPlaybackState() == Player.STATE_ENDED;
            }
            case "width", "height", "video-params/w", "video-params/h", "video-params/dw", "video-params/dh",
                    "video-out-params/w", "video-out-params/h", "video-out-params/dw", "video-out-params/dh",
                    "current-tracks/video/demux-w", "current-tracks/video/demux-h" -> {
                VideoSize previousSize = host.getVideoSize();
                host.updateVideoSize("property:" + property);
                VideoSize currentSize = host.getVideoSize();
                stateChanged = previousSize.width != currentSize.width || previousSize.height != currentSize.height;
                host.scheduleTrackRefresh(property);
            }
            case "container-fps", "estimated-vf-fps" -> {
                cachedContentFrameRate = host.videoFrameRate();
                host.onContentFrameRateChanged(cachedContentFrameRate);
                host.scheduleTrackRefresh(property);
            }
            case "video-params/primaries", "video-params/gamma", "video-params/colorlevels", "video-params/colormatrix" ->
                    host.scheduleTrackRefresh(property);
            case "current-vo" -> {
                observedCurrentVo = value instanceof String;
                cachedCurrentVo = value instanceof String text ? text : cachedCurrentVo;
            }
            case "android-dovi-fel-active" -> androidFelActive = Boolean.TRUE.equals(value);
            case "android-fel-bind-probe" ->
                    host.onFelBindProbeObserved(value instanceof String text ? text : "unavailable");
            case "video-frame-submitted" -> {
                videoFrameSubmitted = Boolean.TRUE.equals(value);
                stateChanged = host.reportFirstSubmittedVideoFrame();
            }
            case "current-gpu-context" -> cachedCurrentGpuContext = stringValue(value, cachedCurrentGpuContext);
            case "gpu-api" -> cachedGpuApi = stringValue(value, cachedGpuApi);
            case "current-ao" -> cachedCurrentAo = stringValue(value, cachedCurrentAo);
            case "audio-device" -> cachedAudioDevice = stringValue(value, cachedAudioDevice);
            case "audio-params/format" -> cachedAudioFormat = stringValue(value, "");
            case "audio-params/channel-count" -> cachedAudioChannels = Math.max(0,
                    (int) longValue(value, cachedAudioChannels));
            case "audio-params/samplerate" -> cachedAudioSampleRate = Math.max(0,
                    (int) longValue(value, cachedAudioSampleRate));
            case "audio-out-params/format" -> cachedAudioOutFormat = stringValue(value, "");
            case "audio-out-params/channel-count" -> cachedAudioOutChannels = Math.max(0,
                    (int) longValue(value, cachedAudioOutChannels));
            case "audio-out-params/samplerate" -> cachedAudioOutSampleRate = Math.max(0,
                    (int) longValue(value, cachedAudioOutSampleRate));
            case "current-tracks/audio/decoder" -> cachedAudioDecoder = stringValue(value, "");
            case "current-tracks/audio/codec-profile" -> cachedAudioCodecProfile = stringValue(value, "");
            case "hwdec-current" -> {
                observedHwdecCurrent = value instanceof String;
                cachedHwdecCurrent = value instanceof String text ? text : cachedHwdecCurrent;
            }
            case "avsync" -> cachedAvSyncSeconds = doubleValue(value, cachedAvSyncSeconds);
            case "display-fps" -> cachedDisplayFps = doubleValue(value, cachedDisplayFps);
            case "estimated-display-fps" -> cachedEstimatedDisplayFps = doubleValue(value, cachedEstimatedDisplayFps);
            case "decoder-frame-drop-count" -> {
                observedDroppedFrames = value instanceof Number;
                cachedDecoderDroppedFrames = Math.max(0, longValue(value, cachedDecoderDroppedFrames));
            }
            case "frame-drop-count" -> {
                observedDroppedFrames = value instanceof Number;
                cachedOutputDroppedFrames = Math.max(0, longValue(value, cachedOutputDroppedFrames));
            }
            case "mistimed-frame-count" -> cachedMistimedFrames = Math.max(0, longValue(value, cachedMistimedFrames));
            case "vo-delayed-frame-count" -> cachedDelayedFrames = Math.max(0, longValue(value, cachedDelayedFrames));
            case "display-sync-active" -> cachedDisplaySyncActive = Boolean.TRUE.equals(value);
            case "track-list" -> {
                VideoSize previousSize = host.getVideoSize();
                host.updateVideoSize("property:" + property);
                VideoSize currentSize = host.getVideoSize();
                stateChanged = previousSize.width != currentSize.width || previousSize.height != currentSize.height;
                host.scheduleTrackRefresh(property);
            }
            case "vid", "aid", "sid", "secondary-sid", "sub-visibility",
                    "current-tracks/video/id", "current-tracks/audio/id", "current-tracks/sub/id", "current-tracks/sub2/id" ->
                    host.scheduleTrackRefresh(property);
            case "chapter" -> host.refreshChapters();
            case "chapter-list" -> host.handleChapterListProperty(value);
            case "disc-menu-active" -> host.onDiscMenuActiveChanged(Boolean.TRUE.equals(value));
            default -> {}
        }
        if (stateChanged) host.invalidateState();
    }

    private void cacheObservedVideoProperty(String property, @Nullable Object value) {
        switch (property) {
            case "width", "height", "video-params/w", "video-params/h",
                    "video-params/dw", "video-params/dh", "video-out-params/w",
                    "video-out-params/h", "video-out-params/dw", "video-out-params/dh",
                    "current-tracks/video/demux-w", "current-tracks/video/demux-h" -> {
                if (value instanceof Number number) {
                    cachedVideoIntProperties.put(property, number.intValue());
                }
            }
            case "container-fps" -> cachedContainerFps = doubleValue(value, cachedContainerFps);
            case "estimated-vf-fps" -> cachedEstimatedVfFps = doubleValue(value, cachedEstimatedVfFps);
            default -> {}
        }
    }

    private static String stringValue(@Nullable Object value, String fallback) {
        return value instanceof String text && !TextUtils.isEmpty(text) ? text : fallback;
    }

    private static double doubleValue(@Nullable Object value, double fallback) {
        if (!(value instanceof Number number)) return fallback;
        double result = number.doubleValue();
        return Double.isNaN(result) || Double.isInfinite(result) ? fallback : result;
    }

    private static long longValue(@Nullable Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        return fallback;
    }
}
