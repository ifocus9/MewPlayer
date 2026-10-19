package androidx.media3.mpvplayer;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.github.catvod.crawler.SpiderDebug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalesces high-frequency MPV property update events (16ms throttle)
 * to avoid main-thread dispatch saturation.
 */
public final class MpvPropertyCoalescer {

    public static final long PROPERTY_EVENT_COALESCE_MS = 16;

    public record PropertyUpdate(long generation, @Nullable Object value) {
    }

    public interface Host {
        long getPropertyGeneration();
        MpvPropertySnapshot getPropertySnapshot();
        MpvDiagnosticCollector getDiagnostics();
        boolean isReleased();
        void postToMain(Runnable r);
        void postDelayedToMain(Runnable r, long delayMs);
        void removeMainCallbacks(Runnable r);
        void onPropertyDispatched(String property, @Nullable Object value);
    }

    private final Host host;
    private final Object propertyEventLock = new Object();
    private final Map<String, PropertyUpdate> coalescedPropertyEvents = new LinkedHashMap<>();
    private final Runnable drainRunnable = this::drain;
    private boolean drainScheduled;

    public MpvPropertyCoalescer(Host host) {
        this.host = host;
    }

    public void dispatch(String property, @Nullable Object value) {
        PropertyUpdate update = new PropertyUpdate(host.getPropertyGeneration(), value);
        if (SpiderDebug.isEnabled()) {
            host.getPropertySnapshot().diagnosticUpdate(
                    update.generation(), property, value, SystemClock.elapsedRealtime(),
                    com.github.catvod.crawler.DebugLogStore.captureGeneration());
            host.getDiagnostics().propertyChanged(property);
        }
        if (!isCoalescedProperty(property)) {
            host.postToMain(() -> applyPropertyUpdate(property, update));
            return;
        }
        boolean scheduleDrain = false;
        synchronized (propertyEventLock) {
            coalescedPropertyEvents.put(property, update);
            if (!drainScheduled) {
                drainScheduled = true;
                scheduleDrain = true;
            }
        }
        if (scheduleDrain) {
            host.postDelayedToMain(drainRunnable, PROPERTY_EVENT_COALESCE_MS);
        }
    }

    public void drain() {
        Map<String, PropertyUpdate> snapshot;
        synchronized (propertyEventLock) {
            snapshot = new LinkedHashMap<>(coalescedPropertyEvents);
            coalescedPropertyEvents.clear();
        }
        for (Map.Entry<String, PropertyUpdate> entry : snapshot.entrySet()) {
            applyPropertyUpdate(entry.getKey(), entry.getValue());
        }
        synchronized (propertyEventLock) {
            if (coalescedPropertyEvents.isEmpty()) {
                drainScheduled = false;
            } else {
                host.postDelayedToMain(drainRunnable, PROPERTY_EVENT_COALESCE_MS);
            }
        }
    }

    public void clear() {
        host.removeMainCallbacks(drainRunnable);
        synchronized (propertyEventLock) {
            coalescedPropertyEvents.clear();
            drainScheduled = false;
        }
    }

    private void applyPropertyUpdate(String property, PropertyUpdate update) {
        if (host.isReleased() || !host.getPropertySnapshot().update(update.generation(), property, update.value())) {
            return;
        }
        host.onPropertyDispatched(property, update.value());
    }

    public static boolean isCoalescedProperty(String property) {
        return switch (property) {
            case "time-pos", "time-pos/full",
                    "demuxer-cache-duration", "demuxer-cache-state/cache-duration",
                    "demuxer-cache-time", "demuxer-cache-state/cache-end",
                    "demuxer-cache-state/reader-pts",
                    "cache-speed", "demuxer-cache-state/raw-input-rate",
                    "cache-buffering-state", "demuxer-cache-state/fw-bytes",
                    "demuxer-cache-state/total-bytes", "demuxer-cache-state/file-cache-bytes",
                    "avsync", "display-fps", "estimated-display-fps",
                    "decoder-frame-drop-count", "frame-drop-count",
                    "mistimed-frame-count", "vo-delayed-frame-count",
                    "width", "height", "video-params/w", "video-params/h",
                    "video-params/dw", "video-params/dh", "video-out-params/w",
                    "video-out-params/h", "video-out-params/dw", "video-out-params/dh",
                    "current-tracks/video/demux-w", "current-tracks/video/demux-h",
                    "container-fps", "estimated-vf-fps" -> true;
            default -> false;
        };
    }
}
