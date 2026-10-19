package androidx.media3.mpvplayer;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackTrace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coordinates HLS proxy server, HLS ad timeline skipping, native play-range clipping,
 * and HLS variant/preload runtime snapshots for MPV playback.
 */
public final class MpvHlsCoordinator {

    public interface Host {
        boolean isInitialized();
        boolean isFileLoaded();
        boolean isReleased();
        boolean isStopping();
        boolean isPlayWhenReady();
        boolean isPlaybackRestarted();
        boolean isRepeatOne();
        boolean isLikelyHls();
        long getCachedPositionMs();
        long getCachedDurationMs();
        long getCachedSelectedHlsBitrate();
        String getPlaybackTraceId();
        boolean shouldCollectDebugDetails();
        boolean hasSeekTarget();
        long getCachedCacheSpeedBytesPerSecond();
        boolean isFreshCacheSpeedSample(long nowMs);
        boolean isCachedCacheUnderrun();
        long getCachedCacheUnderrunCount();
        MpvAutoHlsBitrateState.Snapshot getAutoHlsBitrateSnapshot();

        String stringProperty(String name, String fallback);
        int mpvSetPropertyString(String name, String value);
        void safeSetPropertyString(String name, String value);
        double doubleProperty(String name, double fallback);
        long doubleSecondsToMs(@Nullable Object value, long fallback);

        void seekToPosition(long positionMs, boolean fromAdblock);
        void markPlaybackEnded(String reason);
        void clearEofReached();
    }

    private static final double SECONDS_TO_MS = 1000.0;

    private final Host host;
    private final MpvHlsProxy hlsProxy;
    private final HlsAdTimeline.SkipState hlsAdSkipState = new HlsAdTimeline.SkipState();
    private final MpvHlsAdBoundaryState hlsAdBoundary = new MpvHlsAdBoundaryState();

    private String hlsAdOriginalEnd;
    private String hlsAdOriginalKeepOpen;
    private String hlsAdOriginalKeepOpenPause;
    private String hlsAdAppliedEnd;
    private boolean hlsAdBoundaryUnavailable;

    public MpvHlsCoordinator(Host host) {
        this.host = host;
        this.hlsProxy = new MpvHlsProxy();
    }

    public synchronized void setPlaybackPaused(boolean paused) {
        hlsProxy.setPlaybackPaused(paused);
    }

    public synchronized void release() {
        hlsProxy.release();
    }

    public synchronized void clear() {
        hlsProxy.clear();
    }

    public synchronized void cancelAutomaticPreloadForDiscontinuity() {
        hlsProxy.cancelAutomaticPreloadForDiscontinuity();
    }

    public synchronized boolean updateAutomaticPreloadControl(
            boolean automatic,
            boolean resourceAllowed,
            boolean trafficAllowed) {
        return hlsProxy.updateAutomaticPreloadControl(automatic, resourceAllowed, trafficAllowed);
    }

    public synchronized void requestAutomaticHlsPreload(long positionMs) {
        hlsProxy.requestAutomaticPreload(positionMs, selectedHlsBitsPerSecond());
    }

    public synchronized void requestHlsPreload(long positionMs) {
        if (!host.isLikelyHls()) return;
        if (host.isPlayWhenReady()) {
            hlsProxy.preloadAround(positionMs);
        } else {
            hlsProxy.preloadWhilePaused(positionMs, selectedHlsBitsPerSecond());
        }
    }

    public synchronized long selectedHlsBitsPerSecond() {
        MpvHlsProxy.HlsVariant selected = MpvHlsProxy.resolveSelectedVariant(
                hlsProxy.variantSnapshot().variants(), host.getCachedSelectedHlsBitrate());
        return selected == null ? 0 : selected.selectionBitsPerSecond();
    }

    public synchronized PlaybackResourceClassifier.Classification resourceClassification() {
        return hlsProxy.resourceClassification();
    }

    public synchronized String diagnostics() {
        return hlsProxy.diagnostics();
    }

    public synchronized String proxy(String originalUri, Map<String, String> headers, @Nullable String mediaKey) throws IOException {
        return hlsProxy.proxy(originalUri, headers, mediaKey);
    }

    public synchronized long resolveHlsAdSeekTarget(long positionMs) {
        return host.isLikelyHls()
                ? hlsProxy.adTimeline(host.getCachedSelectedHlsBitrate()).skipTargetMs(positionMs)
                : positionMs;
    }

    public synchronized void maybeSkipHlsAd() {
        if (!host.isLikelyHls() || !host.isFileLoaded() || !host.isPlaybackRestarted()
                || !host.isPlayWhenReady() || host.isReleased() || host.isStopping()
                || !host.isInitialized()) return;
        HlsAdTimeline timeline = hlsProxy.adTimeline(host.getCachedSelectedHlsBitrate());
        long targetMs = hlsAdSkipState.nextTargetMs(timeline, host.getCachedPositionMs());
        if (targetMs < 0) return;
        PlaybackTrace.log("mpv-adblock", host.getPlaybackTraceId(),
                "skip from=%d to=%d ranges=%d timeline=source detector=exo-hls",
                host.getCachedPositionMs(), targetMs, timeline.ranges().size());
        host.seekToPosition(targetMs, true);
    }

    public synchronized void updateHlsAdBoundary(long positionMs) {
        if (!host.isInitialized() || !host.isFileLoaded() || host.isReleased() || host.isStopping()) return;
        HlsAdTimeline.Range next = host.isLikelyHls()
                ? hlsProxy.adTimeline(host.getCachedSelectedHlsBitrate()).nextRange(positionMs) : null;
        if (next == null) {
            if (hlsAdBoundary.pending() && hlsAdOriginalEnd != null) {
                // Lift the clip before seeking into the final programme part,
                // but keep native looping/EOF handling held until that seek lands.
                if (!hlsAdOriginalEnd.equals(hlsAdAppliedEnd)) {
                    host.safeSetPropertyString("file-local-options/end", hlsAdOriginalEnd);
                    hlsAdAppliedEnd = host.stringProperty("options/end", hlsAdOriginalEnd);
                }
                hlsAdBoundary.arm(null);
                return;
            }
            restoreHlsAdBoundary();
            return;
        }
        if (hlsAdBoundaryUnavailable || next.equals(hlsAdBoundary.range())) return;
        try {
            if (hlsAdOriginalEnd == null) {
                String end = host.stringProperty("options/end", "none");
                String length = host.stringProperty("options/length", "none");
                String loopA = host.stringProperty("options/ab-loop-a", "no");
                String loopB = host.stringProperty("options/ab-loop-b", "no");
                // Explicit user clipping/AB looping owns those boundaries.
                if (!"none".equals(end) || !"none".equals(length)
                        || !"no".equals(loopA) && !"no".equals(loopB)) {
                    hlsAdBoundaryUnavailable = true;
                    PlaybackTrace.log("mpv-adblock", host.getPlaybackTraceId(),
                            "clip unavailable reason=user-play-range");
                    return;
                }
                hlsAdOriginalEnd = end;
                hlsAdOriginalKeepOpen = host.stringProperty("options/keep-open", "no");
                hlsAdOriginalKeepOpenPause = host.stringProperty("options/keep-open-pause", "yes");
                if (host.mpvSetPropertyString("file-local-options/keep-open", "always") < 0
                        || host.mpvSetPropertyString("file-local-options/keep-open-pause", "no") < 0
                        || host.mpvSetPropertyString("file-local-options/loop-file", "no") < 0) {
                    throw new IllegalStateException("clip lifecycle options rejected");
                }
            }
            String end = String.format(Locale.US, "%.3f", next.startMs() / SECONDS_TO_MS);
            if (host.mpvSetPropertyString("file-local-options/end", end) < 0) {
                throw new IllegalStateException("clip end rejected");
            }
            hlsAdAppliedEnd = host.stringProperty("options/end", end);
            hlsAdBoundary.arm(next);
            PlaybackTrace.log("mpv-adblock", host.getPlaybackTraceId(),
                    "clip armed start=%d end=%d mode=native-output-boundary",
                    next.startMs(), next.endMs());
        } catch (Throwable e) {
            restoreHlsAdBoundary();
            hlsAdBoundaryUnavailable = true;
            PlaybackTrace.log("mpv-adblock", host.getPlaybackTraceId(),
                    "clip unavailable reason=%s", e.getClass().getSimpleName());
        }
    }

    public synchronized boolean handleHlsAdBoundaryEof() {
        if (hlsAdBoundary.pending()) return true;
        if (hlsAdBoundary.range() == null || !host.isLikelyHls()
                || !Setting.isAdblock()) return false;
        long position = host.doubleSecondsToMs(host.doubleProperty("playback-time",
                host.getCachedPositionMs() / SECONDS_TO_MS), host.getCachedPositionMs());
        long target = hlsAdBoundary.consumeEof(position, host.hasSeekTarget());
        if (target < 0) return false;
        PlaybackTrace.log("mpv-adblock", host.getPlaybackTraceId(),
                "clip reached position=%d target=%d mode=native-output-boundary", position, target);
        host.clearEofReached();
        long cachedDurationMs = host.getCachedDurationMs();
        if (cachedDurationMs > 0 && target >= cachedDurationMs - 1) {
            restoreHlsAdBoundary();
            hlsAdBoundary.clear();
            if (host.isRepeatOne()) host.seekToPosition(0, false);
            else host.markPlaybackEnded("adblock:terminal-boundary");
            return true;
        }
        host.seekToPosition(target, true);
        return true;
    }

    public synchronized void restoreHlsAdBoundary() {
        if (hlsAdOriginalEnd != null && host.isInitialized()) {
            if (hlsAdAppliedEnd != null
                    && hlsAdAppliedEnd.equals(host.stringProperty("options/end", "none"))) {
                host.safeSetPropertyString("file-local-options/end", hlsAdOriginalEnd);
            }
            if ("always".equals(host.stringProperty("options/keep-open", "no"))) {
                host.safeSetPropertyString("file-local-options/keep-open", hlsAdOriginalKeepOpen);
            }
            if ("no".equals(host.stringProperty("options/keep-open-pause", "yes"))) {
                host.safeSetPropertyString("file-local-options/keep-open-pause", hlsAdOriginalKeepOpenPause);
            }
            host.safeSetPropertyString("file-local-options/loop-file", host.isRepeatOne() ? "inf" : "no");
        }
        hlsAdOriginalEnd = null;
        hlsAdOriginalKeepOpen = null;
        hlsAdOriginalKeepOpenPause = null;
        hlsAdAppliedEnd = null;
        hlsAdBoundary.arm(null);
    }

    public synchronized boolean isHlsAdClippingActive() {
        return hlsAdOriginalEnd != null;
    }

    public synchronized void resetHlsAdBoundary() {
        restoreHlsAdBoundary();
        hlsAdBoundary.clear();
        hlsAdBoundaryUnavailable = false;
    }

    public synchronized void onPlaybackRestarted(long positionMs) {
        hlsAdBoundary.playbackRestarted(positionMs);
    }

    public synchronized MpvPlayer.AutoHlsRuntimeSnapshot getAutoHlsRuntimeSnapshot() {
        MpvHlsProxy.HlsVariantSnapshot proxy = hlsProxy.variantSnapshot();
        List<MpvPlayer.HlsVariant> variants = new ArrayList<>(proxy.variants().size());
        for (MpvHlsProxy.HlsVariant variant : proxy.variants()) {
            variants.add(toPublicVariant(variant));
        }
        MpvHlsProxy.HlsVariant selected = MpvHlsProxy.resolveSelectedVariant(
                proxy.variants(), host.getCachedSelectedHlsBitrate());
        MpvAutoHlsBitrateState.Snapshot option = host.getAutoHlsBitrateSnapshot();
        return new MpvPlayer.AutoHlsRuntimeSnapshot(
                host.isLikelyHls(),
                variants,
                selected == null ? null : toPublicVariant(selected),
                saturatingMultiply(host.getCachedCacheSpeedBytesPerSecond(), 8L),
                host.isFreshCacheSpeedSample(SystemClock.elapsedRealtime()),
                host.isCachedCacheUnderrun(),
                host.getCachedCacheUnderrunCount(),
                option.stagedOption(),
                option.acceptedOption(),
                option.observedBitsPerSecond(),
                option.observedCount());
    }

    public synchronized MpvPlayer.AutoHlsPreloadRuntimeSnapshot getAutoHlsPreloadRuntimeSnapshot() {
        MpvHlsProxy.PreloadRuntimeSnapshot proxy =
                hlsProxy.preloadRuntimeSnapshot(SystemClock.elapsedRealtime());
        return new MpvPlayer.AutoHlsPreloadRuntimeSnapshot(
                proxy.preloadConfigured(),
                proxy.vod(),
                proxy.upstreamBitsPerSecond(),
                proxy.throughputKnown(),
                proxy.throughputFresh(),
                proxy.throughputSampleAtElapsedMs(),
                proxy.throughputAgeMs(),
                proxy.acceptedThroughputSamples(),
                proxy.rejectedThroughputSamples(),
                proxy.lastThroughputRejectReason(),
                proxy.foregroundRequests(),
                proxy.cacheEnabled(),
                proxy.cacheStorageKnown(),
                proxy.cacheBudgetAvailable(),
                proxy.cacheCircuitOpen(),
                proxy.cachePhysicalBytes(),
                proxy.cacheReservedBytes(),
                proxy.cacheNewWriteBudgetBytes(),
                proxy.cacheEffectiveCapacityBytes(),
                proxy.preloadTasks());
    }

    public static boolean shouldProxyHls(String uri, boolean likelyHls) {
        if (!likelyHls || TextUtils.isEmpty(uri)) return false;
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        return !"/mpv/index.m3u8".equals(parsed.getPath()) && !"/mpv/item".equals(parsed.getPath());
    }

    private static MpvPlayer.HlsVariant toPublicVariant(MpvHlsProxy.HlsVariant variant) {
        return new MpvPlayer.HlsVariant(
                variant.bandwidthBitsPerSecond(),
                variant.averageBandwidthBitsPerSecond(),
                variant.width(),
                variant.height());
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) return 0;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
