package androidx.media3.mpvplayer;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.PreloadPausePolicy;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.Map;

/**
 * Coordinates demuxer disk caching, preload cache policies, runtime cache-time options,
 * auto baseline states, and cache telemetry snapshots for MPV.
 */
public final class MpvCacheCoordinator {

    public interface Host {
        MpvPlayerConfig getConfig();
        boolean isInitialized();
        boolean isReleased();
        boolean isFileLoaded();
        boolean isPlaying();
        boolean isPlayWhenReady();
        boolean isLoading();
        long getCachedPositionMs();
        long getCachedDurationMs();
        String getPlaybackTraceId();
        PlaybackResourceClassifier.Classification getResourceClassification();
        boolean isLikelyHls();
        boolean isLikelyDash();
        boolean shouldCollectDebugDetails();
        boolean setRuntimeStringChecked(String name, String value);
        String stringProperty(String name, String fallback);
        long longProperty(String name, long fallback);
        Long nullableLongProperty(String name);
        long doublePropertyMs(String name, long fallbackMs);
        boolean booleanProperty(String name, boolean fallback);
        void requestHlsPreload(long positionMs);
    }

    private final Host host;
    private final MpvCacheObserverState cacheObserverState = new MpvCacheObserverState();
    private final MpvCacheTimeState cacheTimeState;
    private final MpvAutoCacheBaselineState autoCacheBaselineState = new MpvAutoCacheBaselineState();
    private final MpvAutoHlsBitrateState autoHlsBitrateState = new MpvAutoHlsBitrateState();

    private final File preloadCacheDir;
    private final long preloadCacheCapacityBytes;

    public File getPreloadCacheDir() {
        return preloadCacheDir;
    }

    public long getPreloadCacheCapacityBytes() {
        return preloadCacheCapacityBytes;
    }

    public synchronized void onPlaybackDiscontinuity(long nowMs) {
        cacheObserverState.onPlaybackDiscontinuity(nowMs);
    }

    public synchronized void onFileLoaded(long nowMs) {
        cacheObserverState.onFileLoaded(nowMs);
    }

    public synchronized void onNativeContextReleased() {
        autoHlsBitrateState.onNativeContextReleased();
    }

    public synchronized void recordObservedProperty(String property, @Nullable Object value) {
        boolean firstCacheTimeReadback = cacheTimeState.recordObserved(property, value);
        if (firstCacheTimeReadback) {
            MpvCacheTimeState.Snapshot cacheTime = cacheTimeState.snapshot();
            if (cacheTime.observedOptions() == 3) {
                PlaybackTrace.log("mpv-cache-time", host.getPlaybackTraceId(),
                        "master=%s reason=%s cache=%d readahead=%d hysteresis=%d observed=%d result=native-readback",
                        cacheTime.master().label(), cacheTime.reason().label(),
                        cacheTime.cacheSeconds(), cacheTime.readaheadSeconds(),
                        cacheTime.hysteresisSeconds(), cacheTime.observedOptions());
            }
        }
        boolean firstHlsReadback = autoHlsBitrateState.recordObserved(property, value);
        if (firstHlsReadback) {
            MpvAutoHlsBitrateState.Snapshot hls = autoHlsBitrateState.snapshot();
            PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTraceId(),
                    "action=native-readback observed=%d count=%d",
                    hls.observedBitsPerSecond(), hls.observedCount());
        }
    }

    public synchronized void recordCacheMetric(String property, @Nullable Object value, long nowMs) {
        boolean firstObservedCacheMetric = cacheObserverState.record(property, value, nowMs) && cacheObserverState.observedCount() == 1;
        if (firstObservedCacheMetric) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "cache source=observer-first property=%s", property);
        }
    }

    private long effectiveDemuxerMaxBytes;
    private long preloadCacheBaselineBytes;
    private long preloadCacheTargetBytes;
    private int preloadCacheBaselineSeconds;
    private int preloadCacheTargetSeconds;
    private boolean preloadCacheOverlayApplied;

    private long cachedCacheDurationMs;
    private long cachedCacheEndMs;
    private long cachedCacheReaderPositionMs;
    private long cachedCacheForwardBytes;
    private long cachedCacheTotalBytes;
    private long cachedCacheFileBytes;
    private long cachedCacheSpeedBytesPerSecond;
    private long cachedCacheSpeedSampleAtMs = -1;
    private int cachedCacheBufferingState;
    private boolean cachedCacheIdle;
    private boolean cachedCacheUnderrun;
    private long cachedCacheUnderrunCount;
    private boolean cachedCacheBof;
    private boolean cachedCacheEof;

    public MpvCacheCoordinator(Host host) {
        this.host = host;
        MpvPlayerConfig config = host.getConfig();
        MpvCacheTimePolicy.Decision initialCacheTimeDecision = MpvCacheTimePolicy.resolve(
                config.performanceOptionsPriority(),
                config.automaticCacheTime(),
                config.cache(),
                config.cacheSeconds(),
                config.demuxerReadaheadSeconds(),
                config.rebufferMs(),
                PlaybackAutoContext.Protocol.UNKNOWN,
                PlaybackAutoContext.PathKind.UNKNOWN);
        this.cacheTimeState = new MpvCacheTimeState(
                initialCacheTimeDecision,
                config.cacheSeconds(),
                config.demuxerReadaheadSeconds(),
                config.demuxerHysteresisSeconds());
        this.preloadCacheDir = new File(config.cacheDir(), "mpv-demuxer-cache");
        this.preloadCacheCapacityBytes = resolvePreloadCacheCapacity(preloadCacheDir);
        this.effectiveDemuxerMaxBytes = config.demuxerMaxBytes();
    }

    public synchronized void applyPreInitCacheOptions() {
        MpvPlayerConfig config = host.getConfig();
        MpvCacheTimePolicy.Decision initialCacheTimeDecision = MpvCacheTimePolicy.resolve(
                config.performanceOptionsPriority(),
                config.automaticCacheTime(),
                config.cache(),
                config.cacheSeconds(),
                config.demuxerReadaheadSeconds(),
                config.rebufferMs(),
                PlaybackAutoContext.Protocol.UNKNOWN,
                PlaybackAutoContext.PathKind.UNKNOWN);
        cacheTimeState.reset(
                initialCacheTimeDecision,
                config.cacheSeconds(),
                config.demuxerReadaheadSeconds(),
                config.demuxerHysteresisSeconds());
    }

    public synchronized void resetCacheState() {
        cacheObserverState.reset();
        cachedCacheDurationMs = 0;
        cachedCacheEndMs = 0;
        cachedCacheReaderPositionMs = 0;
        cachedCacheForwardBytes = 0;
        cachedCacheTotalBytes = 0;
        cachedCacheFileBytes = 0;
        cachedCacheSpeedBytesPerSecond = 0;
        cachedCacheSpeedSampleAtMs = -1;
        cachedCacheBufferingState = 0;
        cachedCacheIdle = false;
        cachedCacheUnderrun = false;
        cachedCacheUnderrunCount = 0;
        cachedCacheBof = false;
        cachedCacheEof = false;
    }

    public synchronized void resetCacheTimelineForSeek(long targetPositionMs) {
        long target = Math.max(0, targetPositionMs);
        cachedCacheDurationMs = 0;
        cachedCacheEndMs = target;
        cachedCacheReaderPositionMs = target;
        cachedCacheForwardBytes = 0;
        cachedCacheTotalBytes = 0;
        cachedCacheFileBytes = 0;
        cachedCacheBufferingState = 0;
        cachedCacheBof = false;
        cachedCacheEof = false;
    }

    public synchronized void refreshCacheState() {
        if (!host.isInitialized()) return;
        long nowMs = SystemClock.elapsedRealtime();
        boolean speedActive = cachedCacheSpeedBytesPerSecond > 0 && isFreshCacheSpeedSample(nowMs);
        boolean cacheActive = host.isLoading() || !cachedCacheIdle || speedActive;
        boolean timelineQueried = cacheObserverState.shouldQueryPausedTimeline(
                host.isFileLoaded(), !host.isPlayWhenReady(), host.getConfig().cache(), nowMs);
        if (timelineQueried) {
            refreshCacheTimeline();
            cacheObserverState.onPausedTimelineQuery(nowMs);
        }
        if (!cacheObserverState.shouldQueryFallback(
                host.isFileLoaded(), cacheActive, host.isPlaying(), nowMs)) return;
        if (!timelineQueried && (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.DURATION, cacheActive, nowMs)
                || cacheObserverState.needsFallback(MpvCacheObserverState.Metric.END, cacheActive, nowMs))) {
            refreshCacheTimeline();
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.READER_POSITION, cacheActive, nowMs)) {
            cachedCacheReaderPositionMs = Math.max(0, host.doublePropertyMs("demuxer-cache-state/reader-pts", cachedCacheReaderPositionMs));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.SPEED, cacheActive, nowMs)) {
            Long speed = host.nullableLongProperty("demuxer-cache-state/raw-input-rate");
            if (speed == null) speed = host.nullableLongProperty("cache-speed");
            if (speed != null) {
                cachedCacheSpeedBytesPerSecond = Math.max(0, speed);
                cachedCacheSpeedSampleAtMs = nowMs;
            }
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.FORWARD_BYTES, cacheActive, nowMs)) {
            cachedCacheForwardBytes = Math.max(0, host.longProperty("demuxer-cache-state/fw-bytes", cachedCacheForwardBytes));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.TOTAL_BYTES, cacheActive, nowMs)) {
            cachedCacheTotalBytes = Math.max(0, host.longProperty("demuxer-cache-state/total-bytes", cachedCacheTotalBytes));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.FILE_BYTES, cacheActive, nowMs)) {
            cachedCacheFileBytes = Math.max(0, host.longProperty("demuxer-cache-state/file-cache-bytes", cachedCacheFileBytes));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.BUFFERING_STATE, cacheActive, nowMs)) {
            cachedCacheBufferingState = Math.max(0, Math.min(100, (int) host.longProperty("cache-buffering-state", cachedCacheBufferingState)));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.IDLE, cacheActive, nowMs)) {
            cachedCacheIdle = host.booleanProperty("demuxer-cache-state/idle", host.booleanProperty("demuxer-cache-idle", cachedCacheIdle));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.UNDERRUN, cacheActive, nowMs)) {
            recordCacheUnderrun(host.booleanProperty("demuxer-cache-state/underrun", cachedCacheUnderrun));
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.BOF, cacheActive, nowMs)) {
            cachedCacheBof = host.booleanProperty("demuxer-cache-state/bof-cached", cachedCacheBof);
        }
        if (cacheObserverState.needsFallback(MpvCacheObserverState.Metric.EOF, cacheActive, nowMs)) {
            cachedCacheEof = host.booleanProperty("demuxer-cache-state/eof-cached", cachedCacheEof);
        }
        cacheObserverState.onFallbackQuery(nowMs);
    }

    public synchronized void refreshCacheTimeline() {
        cachedCacheDurationMs = Math.max(0, host.doublePropertyMs(
                "demuxer-cache-state/cache-duration",
                host.doublePropertyMs("demuxer-cache-duration", cachedCacheDurationMs)));
        cachedCacheEndMs = Math.max(0, host.doublePropertyMs(
                "demuxer-cache-state/cache-end",
                host.doublePropertyMs("demuxer-cache-time", cachedCacheEndMs)));
    }

    public synchronized void handlePropertyChange(String property, @Nullable Object value) {
        switch (property) {
            case "demuxer-cache-duration", "demuxer-cache-state/cache-duration" ->
                    cachedCacheDurationMs = Math.max(0, doubleSecondsToMs(value, cachedCacheDurationMs));
            case "demuxer-cache-time", "demuxer-cache-state/cache-end" ->
                    cachedCacheEndMs = Math.max(0, doubleSecondsToMs(value, cachedCacheEndMs));
            case "demuxer-cache-state/reader-pts" ->
                    cachedCacheReaderPositionMs = Math.max(0, doubleSecondsToMs(value, cachedCacheReaderPositionMs));
            case "cache-speed", "demuxer-cache-state/raw-input-rate" -> {
                cachedCacheSpeedBytesPerSecond = Math.max(0, longValue(value, cachedCacheSpeedBytesPerSecond));
                if (value instanceof Number) {
                    cachedCacheSpeedSampleAtMs = SystemClock.elapsedRealtime();
                }
            }
            case "cache-buffering-state" ->
                    cachedCacheBufferingState = Math.max(0, (int) Math.min(100, longValue(value, cachedCacheBufferingState)));
            case "demuxer-cache-state/fw-bytes" ->
                    cachedCacheForwardBytes = Math.max(0, longValue(value, cachedCacheForwardBytes));
            case "demuxer-cache-state/total-bytes" ->
                    cachedCacheTotalBytes = Math.max(0, longValue(value, cachedCacheTotalBytes));
            case "demuxer-cache-state/file-cache-bytes" ->
                    cachedCacheFileBytes = Math.max(0, longValue(value, cachedCacheFileBytes));
            case "demuxer-cache-idle", "demuxer-cache-state/idle" ->
                    cachedCacheIdle = Boolean.TRUE.equals(value);
            case "demuxer-cache-state/underrun" ->
                    recordCacheUnderrun(Boolean.TRUE.equals(value));
            case "demuxer-cache-state/bof-cached" ->
                    cachedCacheBof = Boolean.TRUE.equals(value);
            case "demuxer-cache-state/eof-cached" ->
                    cachedCacheEof = Boolean.TRUE.equals(value);
            default -> {}
        }
    }

    public synchronized void recordCacheUnderrun(boolean underrun) {
        if (underrun && !cachedCacheUnderrun && cachedCacheUnderrunCount < Long.MAX_VALUE) {
            cachedCacheUnderrunCount++;
        }
        cachedCacheUnderrun = underrun;
    }

    public synchronized boolean isFreshCacheSpeedSample(long nowMs) {
        long now = Math.max(0, nowMs);
        return cachedCacheSpeedSampleAtMs >= 0
                && now >= cachedCacheSpeedSampleAtMs
                && now - cachedCacheSpeedSampleAtMs <= MpvCacheObserverState.DYNAMIC_OBSERVER_STALE_MS;
    }

    public synchronized PlayerCacheState buildCacheState() {
        MpvCacheTimeState.Snapshot cacheTime = cacheTimeState.snapshot();
        return new PlayerCacheState(
                true,
                host.getConfig().cache(),
                cachedCacheIdle,
                cachedCacheUnderrun,
                cachedCacheBof,
                cachedCacheEof,
                cachedCacheBufferingState,
                cachedCacheDurationMs,
                cachedCacheEndMs,
                cachedCacheReaderPositionMs,
                cachedCacheForwardBytes,
                cachedCacheTotalBytes,
                cachedCacheFileBytes,
                cachedCacheSpeedBytesPerSecond,
                effectiveDemuxerMaxBytes,
                host.getConfig().demuxerMaxBackBytes(),
                cacheTime.master().label(),
                cacheTime.reason().label(),
                cacheTime.cacheSeconds(),
                cacheTime.readaheadSeconds(),
                cacheTime.hysteresisSeconds(),
                cacheTime.observedOptions());
    }

    public synchronized MpvPlayer.AutoCacheBaselineResult applyAutoCacheBaseline(long forwardBytes, long backBytes) {
        if (!autoCacheBaselineState.stage(host.getConfig().performanceOptionsPriority(), forwardBytes, backBytes)) {
            return MpvPlayer.AutoCacheBaselineResult.REJECTED;
        }
        if (!host.isInitialized()) {
            PlaybackTrace.log("mpv-auto", host.getPlaybackTraceId(),
                    "action=initial-cache result=staged forwardBytes=%d backBytes=%d",
                    forwardBytes, backBytes);
            return MpvPlayer.AutoCacheBaselineResult.STAGED;
        }
        boolean applied = applyAutoCacheBaselineToNative();
        PlaybackTrace.log("mpv-auto", host.getPlaybackTraceId(),
                "action=initial-cache result=%s forwardBytes=%d backBytes=%d",
                applied ? "applied" : "failed", forwardBytes, backBytes);
        return applied ? MpvPlayer.AutoCacheBaselineResult.APPLIED : MpvPlayer.AutoCacheBaselineResult.FAILED;
    }

    public synchronized void clearAutoCacheBaseline() {
        autoCacheBaselineState.clear();
    }

    public synchronized boolean applyAutoCacheBaselineToNative() {
        Map<String, String> options = autoCacheBaselineState.snapshot();
        if (options.isEmpty() || !host.isInitialized()) return false;
        boolean applied = true;
        for (Map.Entry<String, String> entry : options.entrySet()) {
            boolean accepted = host.setRuntimeStringChecked(entry.getKey(), entry.getValue());
            if (accepted) recordEffectiveCacheOption(entry.getKey(), entry.getValue());
            applied &= accepted;
        }
        return applied;
    }

    public synchronized MpvPlayer.AutoHlsBitrateResult applyAutoHlsBitrate(String option) {
        if (!autoHlsBitrateState.stage(
                host.getConfig().performanceOptionsPriority(),
                host.getConfig().automaticHlsVariant(),
                option)) {
            return MpvPlayer.AutoHlsBitrateResult.REJECTED;
        }
        if (!host.isInitialized()) {
            PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTraceId(),
                    "action=apply-option result=staged target=%s",
                    autoHlsBitrateState.snapshot().stagedOption());
            return MpvPlayer.AutoHlsBitrateResult.STAGED;
        }
        boolean applied = applyAutoHlsBitrateToNative();
        PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTraceId(),
                "action=apply-option result=%s target=%s",
                applied ? "applied" : "failed",
                autoHlsBitrateState.snapshot().stagedOption());
        return applied ? MpvPlayer.AutoHlsBitrateResult.APPLIED : MpvPlayer.AutoHlsBitrateResult.FAILED;
    }

    public synchronized void clearAutoHlsBitrate() {
        autoHlsBitrateState.clear();
    }

    public synchronized boolean applyAutoHlsBitrateToNative() {
        MpvAutoHlsBitrateState.Snapshot snapshot = autoHlsBitrateState.snapshot();
        if (!snapshot.staged() || !host.isInitialized()) return false;
        boolean applied = host.setRuntimeStringChecked("hls-bitrate", snapshot.stagedOption());
        if (applied) autoHlsBitrateState.recordAccepted(snapshot.stagedOption());
        return applied;
    }

    public synchronized int applyPerformanceOptionOverlay() {
        Map<String, String> overlay = MpvOptionPriorityPolicy.resolvePerformanceOverlay(host.getConfig());
        for (Map.Entry<String, String> entry : overlay.entrySet()) {
            if (host.setRuntimeStringChecked(entry.getKey(), entry.getValue())) {
                cacheTimeState.recordAccepted(entry.getKey(), entry.getValue());
                recordEffectiveCacheOption(entry.getKey(), entry.getValue());
            }
        }
        return overlay.size();
    }

    public synchronized void applyPostInitCacheOptions(int overlayCount) {
        boolean autoCacheApplied = applyAutoCacheBaselineToNative();
        boolean autoHlsApplied = applyAutoHlsBitrateToNative();
        if (!autoCacheBaselineState.snapshot().isEmpty()) {
            PlaybackTrace.log("mpv-auto", host.getPlaybackTraceId(),
                    "action=initial-cache result=%s phase=post-init",
                    autoCacheApplied ? "applied" : "failed");
        }
        if (autoHlsBitrateState.snapshot().staged()) {
            PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTraceId(),
                    "action=apply-option result=%s phase=post-init target=%s",
                    autoHlsApplied ? "applied" : "failed",
                    autoHlsBitrateState.snapshot().stagedOption());
        }
        if (host.shouldCollectDebugDetails()) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                    "option priority=%s overlayCount=%d effective cache maxBytes=%s backBytes=%s timeMaster=%s cacheSecs=%s readaheadSecs=%s hysteresisSecs=%s initial=%s rebufferWait=%s",
                    MpvOptionPriorityPolicy.priorityName(host.getConfig().performanceOptionsPriority()),
                    overlayCount,
                    host.stringProperty("demuxer-max-bytes", "?"),
                    host.stringProperty("demuxer-max-back-bytes", "?"),
                    cacheTimeState.snapshot().master().label(),
                    host.stringProperty("cache-secs", "?"),
                    host.stringProperty("demuxer-readahead-secs", "?"),
                    host.stringProperty("demuxer-hysteresis-secs", "?"),
                    host.stringProperty("cache-pause-initial", "?"),
                    host.stringProperty("cache-pause-wait", "?"));
        }
    }

    public synchronized void recordEffectiveCacheOption(String name, String value) {
        if (!"demuxer-max-bytes".equals(name)) return;
        try {
            effectiveDemuxerMaxBytes = Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
        }
    }

    public synchronized void applyPreloadDiskCacheMode() {
        PlaybackResourceClassifier.Classification classification = host.getResourceClassification();
        PlaybackAutoContext.Protocol protocol = classification == null
                ? PlaybackAutoContext.Protocol.UNKNOWN : classification.protocol();
        PlaybackAutoContext.PathKind playerPath = classification == null
                ? PlaybackAutoContext.PathKind.UNKNOWN : classification.playerPath();
        boolean progressive = !host.isLikelyHls() && !host.isLikelyDash()
                && MpvPreloadCachePolicy.supportsForwardPreload(protocol, playerPath);
        boolean enable = host.getConfig().performanceOptionsPriority()
                && PreloadSetting.isPreload(PlayerSetting.MPV)
                && preloadCacheCapacityBytes > 0
                && progressive;
        boolean directoryApplied = !enable || host.setRuntimeStringChecked(
                "demuxer-cache-dir", preloadCacheDir.getAbsolutePath());
        boolean applied = host.setRuntimeStringChecked(
                "cache-on-disk", enable && directoryApplied ? "yes" : "no");
        PlaybackTrace.log("mpv-preload-cache", host.getPlaybackTraceId(),
                "action=disk-mode requested=%s result=%s capacityBytes=%d protocol=%s path=%s hls=%s dash=%s",
                enable, applied ? "applied" : "failed",
                preloadCacheCapacityBytes, protocol.label(), playerPath.label(),
                host.isLikelyHls(), host.isLikelyDash());
    }

    public synchronized void applyCacheTimePolicy() {
        PlaybackResourceClassifier.Classification classification = host.getResourceClassification();
        PlaybackAutoContext.Protocol protocol = host.isLikelyHls()
                ? PlaybackAutoContext.Protocol.HLS
                : host.isLikelyDash()
                ? PlaybackAutoContext.Protocol.DASH
                : classification == null
                ? PlaybackAutoContext.Protocol.UNKNOWN
                : classification.protocol();
        PlaybackAutoContext.PathKind playerPath = classification == null
                ? PlaybackAutoContext.PathKind.UNKNOWN : classification.playerPath();
        MpvCacheTimePolicy.Decision decision = MpvCacheTimePolicy.resolve(
                host.getConfig().performanceOptionsPriority(),
                host.getConfig().automaticCacheTime(),
                host.getConfig().cache(),
                host.getConfig().cacheSeconds(),
                host.getConfig().demuxerReadaheadSeconds(),
                host.getConfig().rebufferMs(),
                protocol,
                playerPath);
        cacheTimeState.select(decision);
        boolean applied = decision.runtimeManaged();
        for (Map.Entry<String, String> entry : decision.runtimeOptions().entrySet()) {
            boolean accepted = host.setRuntimeStringChecked(entry.getKey(), entry.getValue());
            if (accepted) cacheTimeState.recordAccepted(entry.getKey(), entry.getValue());
            applied &= accepted;
        }
        MpvCacheTimeState.Snapshot snapshot = cacheTimeState.snapshot();
        PlaybackTrace.log("mpv-cache-time", host.getPlaybackTraceId(),
                "master=%s reason=%s protocol=%s path=%s cache=%d readahead=%d hysteresis=%d rebufferWait=%d observed=%d result=%s",
                decision.master().label(), decision.reason().label(),
                decision.protocol().label(), decision.playerPath().label(),
                snapshot.cacheSeconds(), snapshot.readaheadSeconds(),
                snapshot.hysteresisSeconds(), decision.rebufferWaitSeconds(),
                snapshot.observedOptions(),
                !decision.runtimeManaged() ? "mpv-conf" : applied ? "applied" : "failed");
    }

    public synchronized void updatePreloadCacheOverlay() {
        int baselineSeconds = preloadCacheOverlayApplied
                ? preloadCacheBaselineSeconds : cacheTimeState.snapshot().cacheSeconds();
        long baselineBytes = preloadCacheOverlayApplied
                ? preloadCacheBaselineBytes : effectiveDemuxerMaxBytes;
        PlaybackResourceClassifier.Classification classification = host.getResourceClassification();
        PlaybackAutoContext.Protocol protocol = classification == null
                ? PlaybackAutoContext.Protocol.UNKNOWN : classification.protocol();
        PlaybackAutoContext.StreamKind streamKind = classification == null
                ? PlaybackAutoContext.StreamKind.UNKNOWN : classification.streamKind();
        PlaybackAutoContext.PathKind playerPath = classification == null
                ? PlaybackAutoContext.PathKind.UNKNOWN : classification.playerPath();
        boolean pauseAllowed = PreloadPausePolicy.evaluate(
                host.isPlayWhenReady(),
                PreloadSetting.getPausePreloadPolicy(PlayerSetting.MPV),
                PlaybackSystemConditionMonitor.process().currentNetworkSnapshot()).allowed();
        long capacityBytes = host.getConfig().automaticCacheTime()
                ? baselineBytes : preloadCacheCapacityBytes;
        MpvPreloadCachePolicy.Decision decision =
                MpvPreloadCachePolicy.resolve(
                        new MpvPreloadCachePolicy.Request(
                                !host.isPlayWhenReady(),
                                PreloadSetting.isPreload(PlayerSetting.MPV),
                                pauseAllowed,
                                host.getConfig().performanceOptionsPriority(),
                                host.getConfig().cache(),
                                protocol,
                                streamKind,
                                playerPath,
                                baselineSeconds,
                                baselineBytes,
                                PreloadSetting.getPreloadAheadSeconds(PlayerSetting.MPV),
                                capacityBytes,
                                host.getCachedPositionMs(),
                                host.getCachedDurationMs()));
        if (!decision.apply()) {
            restorePreloadCacheOverlay();
            return;
        }
        if (!host.isInitialized() || !host.isFileLoaded()) return;
        if (!preloadCacheOverlayApplied) {
            preloadCacheBaselineSeconds = baselineSeconds;
            preloadCacheBaselineBytes = baselineBytes;
            preloadCacheOverlayApplied = true;
        }
        int targetSeconds = Math.max(preloadCacheTargetSeconds, decision.targetSeconds());
        long targetBytes = Math.max(preloadCacheTargetBytes, decision.targetBytes());
        boolean secondsCurrent = cacheTimeState.snapshot().cacheSeconds() == targetSeconds;
        boolean bytesCurrent = effectiveDemuxerMaxBytes == targetBytes;
        if (secondsCurrent && bytesCurrent) {
            preloadCacheTargetSeconds = targetSeconds;
            preloadCacheTargetBytes = targetBytes;
            return;
        }
        boolean bytesAccepted = bytesCurrent
                || host.setRuntimeStringChecked("demuxer-max-bytes", String.valueOf(targetBytes));
        if (bytesAccepted) effectiveDemuxerMaxBytes = targetBytes;
        boolean secondsAccepted = secondsCurrent
                || host.setRuntimeStringChecked("cache-secs", String.valueOf(targetSeconds));
        if (secondsAccepted) {
            cacheTimeState.recordAccepted("cache-secs", String.valueOf(targetSeconds));
        }
        if (!bytesAccepted || !secondsAccepted) {
            rollbackPreloadCacheOverlay(secondsAccepted && !secondsCurrent, bytesAccepted && !bytesCurrent);
            PlaybackTrace.log("mpv-preload-cache", host.getPlaybackTraceId(),
                    "action=extend result=failed mode=%s protocol=%s stream=%s path=%s",
                    host.isPlayWhenReady() ? "playing" : "paused",
                    protocol.label(), streamKind.label(), playerPath.label());
            return;
        }
        preloadCacheTargetSeconds = targetSeconds;
        preloadCacheTargetBytes = targetBytes;
        PlaybackTrace.log("mpv-preload-cache", host.getPlaybackTraceId(),
                "action=extend result=applied mode=%s baselineSeconds=%d targetSeconds=%d baselineBytes=%d targetBytes=%d disk=%s protocol=%s stream=%s path=%s",
                host.isPlayWhenReady() ? "playing" : "paused",
                preloadCacheBaselineSeconds, targetSeconds,
                preloadCacheBaselineBytes, targetBytes,
                preloadCacheCapacityBytes > 0,
                protocol.label(), streamKind.label(), playerPath.label());
    }

    private void rollbackPreloadCacheOverlay(boolean restoreSeconds, boolean restoreBytes) {
        boolean secondsRestored = !restoreSeconds
                || host.setRuntimeStringChecked("cache-secs", String.valueOf(preloadCacheBaselineSeconds));
        if (secondsRestored && restoreSeconds) {
            cacheTimeState.recordAccepted("cache-secs", String.valueOf(preloadCacheBaselineSeconds));
        }
        boolean bytesRestored = !restoreBytes
                || host.setRuntimeStringChecked("demuxer-max-bytes", String.valueOf(preloadCacheBaselineBytes));
        if (bytesRestored && restoreBytes) {
            effectiveDemuxerMaxBytes = preloadCacheBaselineBytes;
        }
        if (secondsRestored && bytesRestored) clearPreloadCacheOverlay();
    }

    public synchronized void restorePreloadCacheOverlay() {
        if (!preloadCacheOverlayApplied) return;
        if (!host.isInitialized()) {
            clearPreloadCacheOverlay();
            return;
        }
        boolean secondsRestored = host.setRuntimeStringChecked(
                "cache-secs", String.valueOf(preloadCacheBaselineSeconds));
        if (secondsRestored) {
            cacheTimeState.recordAccepted("cache-secs", String.valueOf(preloadCacheBaselineSeconds));
        }
        boolean bytesRestored = host.setRuntimeStringChecked(
                "demuxer-max-bytes", String.valueOf(preloadCacheBaselineBytes));
        if (bytesRestored) effectiveDemuxerMaxBytes = preloadCacheBaselineBytes;
        if (!secondsRestored || !bytesRestored) return;
        PlaybackTrace.log("mpv-preload-cache", host.getPlaybackTraceId(),
                "action=restore baselineSeconds=%d baselineBytes=%d previousTargetSeconds=%d previousTargetBytes=%d",
                preloadCacheBaselineSeconds, preloadCacheBaselineBytes,
                preloadCacheTargetSeconds, preloadCacheTargetBytes);
        clearPreloadCacheOverlay();
    }

    public synchronized void clearPreloadCacheOverlay() {
        preloadCacheOverlayApplied = false;
        preloadCacheBaselineSeconds = 0;
        preloadCacheBaselineBytes = 0;
        preloadCacheTargetSeconds = 0;
        preloadCacheTargetBytes = 0;
    }

    public synchronized long getCachedCacheDurationMs() {
        return cachedCacheDurationMs;
    }

    public synchronized long getCachedCacheEndMs() {
        return cachedCacheEndMs;
    }

    public synchronized long getCachedCacheReaderPositionMs() {
        return cachedCacheReaderPositionMs;
    }

    public synchronized long getCachedCacheForwardBytes() {
        return cachedCacheForwardBytes;
    }

    public synchronized long getCachedCacheTotalBytes() {
        return cachedCacheTotalBytes;
    }

    public synchronized long getCachedCacheFileBytes() {
        return cachedCacheFileBytes;
    }

    public synchronized long getCachedCacheSpeedBytesPerSecond() {
        return cachedCacheSpeedBytesPerSecond;
    }

    public synchronized int getCachedCacheBufferingState() {
        return cachedCacheBufferingState;
    }

    public synchronized boolean isCachedCacheIdle() {
        return cachedCacheIdle;
    }

    public synchronized boolean isCachedCacheUnderrun() {
        return cachedCacheUnderrun;
    }

    public synchronized long getCachedCacheUnderrunCount() {
        return cachedCacheUnderrunCount;
    }

    public synchronized boolean isCachedCacheBof() {
        return cachedCacheBof;
    }

    public synchronized boolean isCachedCacheEof() {
        return cachedCacheEof;
    }

    public synchronized MpvAutoHlsBitrateState.Snapshot getAutoHlsBitrateSnapshot() {
        return autoHlsBitrateState.snapshot();
    }

    public synchronized long getEffectiveDemuxerMaxBytes() {
        return effectiveDemuxerMaxBytes;
    }

    public synchronized MpvCacheTimeState.Snapshot getCacheTimeSnapshot() {
        return cacheTimeState.snapshot();
    }

    private static long resolvePreloadCacheCapacity(File directory) {
        if (!PreloadSetting.isPreload(PlayerSetting.MPV)) return 0;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return 0;
            long existingBytes = FileUtil.getDirectorySize(directory);
            FileUtil.StorageSpace storage = FileUtil.getStorageSpace(directory);
            DiskCacheCapacityPolicy.Decision decision =
                    DiskCacheCapacityPolicy.resolve(
                            storage.available(),
                            PreloadSetting.getPreloadSizeBytes(PlayerSetting.MPV),
                            existingBytes,
                            storage.availableBytes(),
                            storage.totalBytes());
            long capacityBytes = decision.state() == DiskCacheCapacityPolicy.State.UNAVAILABLE
                    ? 0 : decision.newWriteBudgetBytes();
            SpiderDebug.log("mpv-preload-cache",
                    "action=capacity state=%s configuredBytes=%d existingBytes=%d availableBytes=%d reserveBytes=%d capacityBytes=%d",
                    decision.state(), decision.configuredCapacityBytes(),
                    decision.existingCacheBytes(), decision.availableStorageBytes(),
                    decision.reserveBytes(), capacityBytes);
            return Math.max(0, capacityBytes);
        } catch (Throwable error) {
            SpiderDebug.log("mpv-preload-cache",
                    "action=capacity state=unavailable error=%s",
                    error.getClass().getSimpleName());
            return 0;
        }
    }

    private static long doubleSecondsToMs(Object value, long fallbackMs) {
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            if (Double.isNaN(seconds) || Double.isInfinite(seconds)) return fallbackMs;
            return Math.max(0, Math.round(seconds * 1000.0));
        }
        if (value instanceof String string) {
            try {
                double seconds = Double.parseDouble(string.trim());
                if (Double.isNaN(seconds) || Double.isInfinite(seconds)) return fallbackMs;
                return Math.max(0, Math.round(seconds * 1000.0));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallbackMs;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
