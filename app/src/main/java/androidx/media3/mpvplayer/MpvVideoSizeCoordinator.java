package androidx.media3.mpvplayer;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.player.PlaybackTrace;

import java.util.function.BiFunction;

/**
 * Coordinates video size candidate evaluation, fallback resolution,
 * and diagnostic candidate logging for MpvPlayer.
 */
public final class MpvVideoSizeCoordinator {

    private static final String SIZE_TAG = "mpv-size";

    public interface Host {
        int cachedVideoIntProperty(String property, int fallback);
        boolean isFileLoaded();
        boolean isPlaybackRestarted();
        int getVideoReconfigCount();
        int getSurfaceWidth();
        int getSurfaceHeight();
        String getPlaybackTraceId();
        boolean shouldCollectDebugDetails();
    }

    private final Host host;
    private String lastVideoSizeCandidateLog = "";

    public record SizeCandidate(int width, int height, String source) {
    }

    public MpvVideoSizeCoordinator(Host host) {
        this.host = host;
    }

    @Nullable
    public VideoSize resolveVideoSize(VideoSize currentSize, String reason, Tracks currentTracks) {
        SizeCandidate candidate = videoSizeCandidate(currentSize, currentTracks);
        logVideoSizeCandidates(reason, candidate, currentTracks);
        if (candidate == null || candidate.width() <= 0 || candidate.height() <= 0) return null;
        if (currentSize.width == candidate.width() && currentSize.height == candidate.height()) return null;
        Log.d(SIZE_TAG, "mpv videoSize=" + candidate.width() + "x" + candidate.height()
                + " source=" + candidate.source() + " reason=" + reason
                + " surface=" + host.getSurfaceWidth() + "x" + host.getSurfaceHeight());
        return new VideoSize(candidate.width(), candidate.height());
    }

    @Nullable
    public SizeCandidate videoSizeCandidate(VideoSize currentSize, Tracks currentTracks) {
        SizeCandidate candidate = candidateFromProperties("current-tracks/video/demux-w", "current-tracks/video/demux-h", "current-track");
        if (candidate != null) return candidate;
        candidate = candidateFromSelectedVideoTrack(currentTracks);
        if (candidate != null) return candidate;
        candidate = candidateFromProperties("video-params/dw", "video-params/dh", "video-params-display");
        if (candidate != null) return candidate;
        candidate = candidateFromProperties("video-params/w", "video-params/h", "video-params");
        if (candidate != null) return candidate;
        candidate = candidateFromProperties("width", "height", "width-height");
        if (candidate != null) return candidate;
        if (!canUseVideoOutSizeFallback(currentSize)) return null;
        candidate = candidateFromProperties("video-out-params/dw", "video-out-params/dh", "video-out-display");
        if (candidate != null) return candidate;
        return candidateFromProperties("video-out-params/w", "video-out-params/h", "video-out");
    }

    private boolean canUseVideoOutSizeFallback(VideoSize currentSize) {
        return currentSize.width <= 0 && currentSize.height <= 0
                && (host.isFileLoaded() || host.isPlaybackRestarted());
    }

    @Nullable
    private SizeCandidate candidateFromProperties(String widthProperty, String heightProperty, String source) {
        int width = host.cachedVideoIntProperty(widthProperty, 0);
        int height = host.cachedVideoIntProperty(heightProperty, 0);
        return width > 0 && height > 0 ? new SizeCandidate(width, height, source) : null;
    }

    @Nullable
    public SizeCandidate candidateFromSelectedVideoTrack(Tracks currentTracks) {
        if (currentTracks == null) return null;
        SizeCandidate firstVideo = null;
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.length <= 0) continue;
            Format format = group.getTrackFormat(0);
            if (!MimeTypes.isVideo(format.sampleMimeType)) continue;
            int width = format.width;
            int height = format.height;
            if (width <= 0 || height <= 0) continue;
            SizeCandidate candidate = new SizeCandidate(width, height, "tracks-cache");
            if (firstVideo == null) firstVideo = candidate;
            if (group.isTrackSelected(0)) return candidate;
        }
        return firstVideo;
    }

    private void logVideoSizeCandidates(String reason, @Nullable SizeCandidate candidate, Tracks currentTracks) {
        if (!host.shouldCollectDebugDetails()) return;
        String text = "mpv size candidates reason=" + reason
                + " selected=" + candidateText(candidate)
                + " stable=" + size("current", "current-tracks/video/demux-w", "current-tracks/video/demux-h")
                + " track=" + candidateText(candidateFromSelectedVideoTrack(currentTracks))
                + " paramsDisplay=" + size("vp-d", "video-params/dw", "video-params/dh")
                + " params=" + size("vp", "video-params/w", "video-params/h")
                + " legacy=" + size("wh", "width", "height")
                + " outDisplay=" + size("vo-d", "video-out-params/dw", "video-out-params/dh")
                + " out=" + size("vo", "video-out-params/w", "video-out-params/h")
                + " fileLoaded=" + host.isFileLoaded()
                + " restarted=" + host.isPlaybackRestarted()
                + " reconfig=" + host.getVideoReconfigCount()
                + " surface=" + host.getSurfaceWidth() + "x" + host.getSurfaceHeight();
        if (text.equals(lastVideoSizeCandidateLog)) return;
        lastVideoSizeCandidateLog = text;
        Log.d(SIZE_TAG, text);
        PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "%s", text);
    }

    private String candidateText(@Nullable SizeCandidate candidate) {
        return candidate == null ? "none" : candidate.source() + ":" + candidate.width() + "x" + candidate.height();
    }

    private String size(String label, String widthProperty, String heightProperty) {
        return label + ":" + host.cachedVideoIntProperty(widthProperty, 0) + "x"
                + host.cachedVideoIntProperty(heightProperty, 0);
    }
}
