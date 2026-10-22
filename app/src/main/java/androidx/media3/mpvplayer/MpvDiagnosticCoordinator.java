package androidx.media3.mpvplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;

/**
 * Coordinates diagnostic logging, runtime telemetry, ANR monitoring,
 * failure signal classification, and error message generation for MpvPlayer.
 */
public final class MpvDiagnosticCoordinator {

    private static final String TAG = "TV-mpv";
    private static final int RECENT_LOG_LIMIT = 30;
    private static final long MAIN_THREAD_STALL_THRESHOLD_MS = 250;
    private static final long MAIN_THREAD_STALL_LOG_INTERVAL_MS = 2000;

    public interface Host {
        Context getContext();
        MpvPlayerConfig getConfig();
        String getPlaybackTraceId();
        boolean isInitialized();
        boolean isReleased();
        long getPropertyGeneration();
        PlaybackException getPlayerError();
        MpvPropertyDispatcher getPropertyDispatcher();
        MpvLifecycleCoordinator getLifecycleCoordinator();
        MpvHlsCoordinator getHlsCoordinator();
        VideoSize getVideoSize();
        long getCachedPositionMs();
        long getCachedDurationMs();
        Tracks getCurrentTracks();
        float getVideoFrameRate();
        boolean setRuntimeStringChecked(String name, String value);
        String stringProperty(String property, String fallback);
        boolean booleanProperty(String property, boolean fallback);
        double doubleProperty(String property, double fallback);
        long longProperty(String property, long fallback);
        MpvTrackCoordinator getTrackCoordinator();
        String propertyStringOrInt(String property);
        void setDiagnosticTracking(boolean enabled, boolean deep, int categories);
        void fail(Throwable e, int errorCode);
        void maybeRetryDtsHdAsCore(String line);
        void postToMain(Runnable r);
    }

    private final Host host;
    private final File subtitleDiagnosticFile;
    private final List<String> recentLogs = new ArrayList<>();
    private String lastFailureLog;
    private boolean sawNoAvData;
    private boolean sawInvalidData;
    private boolean sawPngVideo;
    private boolean sawNetworkError;
    private boolean sawDecodeError;
    private boolean sawVideoOutputError;
    private boolean sawDrmError;

    private String diagnosticBaseMsgLevel;
    private String diagnosticAppliedMsgLevel;
    private boolean diagnosticLogLevelApplied;
    private boolean diagnosticDepthApplied;
    private int diagnosticCategoriesApplied = -1;
    private long lastNativeLogQueueWarningAtMs;

    public MpvDiagnosticCoordinator(Host host) {
        this.host = host;
        File externalFiles = host.getContext().getExternalFilesDir(null);
        subtitleDiagnosticFile = externalFiles == null ? null : new File(externalFiles, "mpv-subtitle-debug.log");
        if (subtitleDiagnosticFile != null && subtitleDiagnosticFile.length() > 2 * 1024 * 1024) {
            subtitleDiagnosticFile.delete();
        }
    }

    public synchronized void appendSubtitleDiagnostic(String text) {
        if (subtitleDiagnosticFile == null) return;
        File parent = subtitleDiagnosticFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        String line = System.currentTimeMillis() + " " + text + "\n";
        try (OutputStream out = new FileOutputStream(subtitleDiagnosticFile, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    public void logSubtitleState(String reason) {
        if (!host.isInitialized()) return;
        String text = host.stringProperty("sub-text", "");
        String state = reason
                + " positionMs=" + host.getCachedPositionMs()
                + " sid=" + host.propertyStringOrInt("sid")
                + " currentSub=" + host.propertyStringOrInt("current-tracks/sub/id")
                + " visible=" + host.booleanProperty("sub-visibility", true)
                + " subStart=" + host.doubleProperty("sub-start", Double.NaN)
                + " subEnd=" + host.doubleProperty("sub-end", Double.NaN)
                + " textLength=" + text.length()
                + " text=" + (text.length() > 80 ? text.substring(0, 80) : text);
        Log.d(TAG, "subtitle-state " + state);
        appendSubtitleDiagnostic("state " + state);
    }

    public synchronized void clearRecentLogs() {
        recentLogs.clear();
    }

    public synchronized List<String> getRecentLogs() {
        return new ArrayList<>(recentLogs);
    }

    public synchronized void rememberLog(String line) {
        if (recentLogs.size() >= RECENT_LOG_LIMIT) recentLogs.remove(0);
        recentLogs.add(line);
    }

    public synchronized boolean recentLogsContain(String... needles) {
        for (String log : recentLogs) {
            String lower = log == null ? "" : log.toLowerCase(Locale.US);
            for (String needle : needles) {
                if (lower.contains(needle)) return true;
            }
        }
        return false;
    }

    public synchronized void markFailureSignal(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        if (lower.contains("no audio or video data played")) sawNoAvData = true;
        if (lower.contains("invalid data found when processing input")) sawInvalidData = true;
        if (lower.contains("video: png")) sawPngVideo = true;
        if (isNetworkFailureLog(lower)) sawNetworkError = true;
        if (isDecodeFailureLog(lower)) sawDecodeError = true;
        if (isVideoOutputFailureLog(lower)) sawVideoOutputError = true;
        if (isDrmFailureLog(lower)) sawDrmError = true;
        if (sawNoAvData || sawInvalidData || sawPngVideo || sawNetworkError || sawDecodeError || sawVideoOutputError || sawDrmError || lower.contains("failed") || lower.contains("error")) {
            lastFailureLog = line;
        }
    }

    public synchronized void resetFailureSignals() {
        sawNoAvData = false;
        sawInvalidData = false;
        sawPngVideo = false;
        sawNetworkError = false;
        sawDecodeError = false;
        sawVideoOutputError = false;
        sawDrmError = false;
        lastFailureLog = null;
        host.getLifecycleCoordinator().resetEndFileState();
    }

    public synchronized String getLastFailureLog() {
        return lastFailureLog;
    }

    public synchronized boolean isSawNoAvData() {
        return sawNoAvData;
    }

    public synchronized boolean isSawInvalidData() {
        return sawInvalidData;
    }

    public synchronized boolean isSawPngVideo() {
        return sawPngVideo;
    }

    public synchronized boolean isSawNetworkError() {
        return sawNetworkError;
    }

    public synchronized boolean isSawDecodeError() {
        return sawDecodeError;
    }

    public synchronized boolean isSawVideoOutputError() {
        return sawVideoOutputError;
    }

    public synchronized boolean isSawDrmError() {
        return sawDrmError;
    }

    public boolean isNetworkFailureLog(String lower) {
        return lower.contains("http error")
                || lower.contains("server returned")
                || lower.contains("connection timed out")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("network is unreachable")
                || lower.contains("name resolution")
                || lower.contains("cannot resolve")
                || lower.contains("timed out")
                || ((lower.contains("tls") || lower.contains("ssl")) && (lower.contains("failed") || lower.contains("error") || lower.contains("certificate")))
                || lower.contains("error reading")
                || lower.contains("failed to open http")
                || lower.contains("failed to open https");
    }

    public boolean isDecodeFailureLog(String lower) {
        return lower.contains("could not open codec")
                || lower.contains("failed to initialize decoder")
                || lower.contains("failed to init decoder")
                || lower.contains("decoder init failed")
                || lower.contains("error while decoding")
                || lower.contains("error decoding")
                || lower.contains("decoding failed")
                || lower.contains("no decoders available")
                || lower.contains("hardware decoding failed");
    }

    public boolean isVideoOutputFailureLog(String lower) {
        return lower.contains("video output failed")
                || lower.contains("failed to create android surface")
                || lower.contains("could not create egl")
                || (lower.contains("egl") && lower.contains("failed"))
                || (lower.contains("vo/") && lower.contains("failed"));
    }

    public boolean isDrmFailureLog(String lower) {
        return (lower.contains("widevine") && (lower.contains("unsupported") || lower.contains("not supported") || lower.contains("failed") || lower.contains("error")))
                || (lower.contains("encrypted") && (lower.contains("unsupported") || lower.contains("not supported") || lower.contains("failed")))
                || (lower.contains("drm") && (lower.contains("unsupported") || lower.contains("not supported") || lower.contains("failed") || lower.contains("error")));
    }

    public boolean shouldDebugLogMpvLine(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("invalid")
                || lower.contains("no audio")
                || lower.contains("video:")
                || lower.contains("audio:")
                || lower.contains("found 'hls'")
                || lower.contains("iso detected")
                || lower.contains("iso image")
                || lower.contains("blu-ray")
                || lower.contains("aacs")
                || lower.contains("bd+")
                || lower.contains("bdnav: cfg_title")
                || lower.contains("bdnav: hdmv entered")
                || lower.contains("bdnav: menu start")
                || lower.contains("discnav action=")
                || lower.contains("discnav pump")
                || lower.contains("discnav explicit menu override")
                || lower.contains("bdnav-debug")
                || lower.contains("opening")
                || lower.contains("lavf")
                || lower.contains("demux")
                || lower.contains("codec")
                || lower.contains("track")
                || lower.contains("aimagereader")
                || lower.contains("vulkan")
                || lower.contains("fence")
                || lower.contains("bufferqueue")
                || lower.contains("surface pool")
                || lower.contains("conversion pool")
                || lower.contains("mediacodec output");
    }

    public synchronized String recentLogSuffix() {
        if (!TextUtils.isEmpty(lastFailureLog)) return ": " + lastFailureLog;
        if (recentLogs.isEmpty()) return "";
        return ": " + recentLogs.get(recentLogs.size() - 1);
    }

    public synchronized boolean isFailedLoadedMedia() {
        MpvLifecycleCoordinator lifecycle = host.getLifecycleCoordinator();
        if (!lifecycle.isFileLoaded()) return false;
        if (sawNoAvData || sawInvalidData || sawPngVideo || sawNetworkError || sawDecodeError || sawVideoOutputError || sawDrmError) return true;
        if (recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png", "could not open codec", "failed to initialize decoder", "video output failed")) return true;
        VideoSize videoSize = host.getVideoSize();
        long positionMs = host.getCachedPositionMs();
        long durationMs = host.getCachedDurationMs() > 0 ? host.getCachedDurationMs() : C.TIME_UNSET;
        return lifecycle.isPlaybackRestarted() && videoSize.width <= 0 && videoSize.height <= 0 && positionMs <= 0 && durationMs == C.TIME_UNSET;
    }

    public synchronized String failedLoadedMediaMessage() {
        MpvLifecycleCoordinator lifecycle = host.getLifecycleCoordinator();
        VideoSize videoSize = host.getVideoSize();
        long positionMs = host.getCachedPositionMs();
        if (lifecycle.isCurrentLikelyHls() && (sawNoAvData
                || sawInvalidData
                || sawPngVideo
                || recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png")
                || lifecycle.isPlaybackRestarted() && videoSize.width <= 0 && videoSize.height <= 0 && positionMs <= 0)) {
            return MpvPlayer.ERROR_HLS_PLAYBACK_FAILED + detailSuffix("hls input failed");
        }
        if (sawDrmError) return MpvPlayer.ERROR_DRM_UNSUPPORTED + detailSuffix("drm/encrypted media");
        if (sawVideoOutputError) return MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED + detailSuffix("video output failed");
        if (sawNetworkError) return MpvPlayer.ERROR_NETWORK_FAILED + detailSuffix("network/io failure");
        if (sawNoAvData || recentLogsContain("no audio or video data played")) return MpvPlayer.ERROR_NO_AV_DATA + detailSuffix("no audio or video data played");
        if (sawInvalidData || sawPngVideo || recentLogsContain("invalid data found when processing input", "video: png")) return MpvPlayer.ERROR_INVALID_MEDIA_DATA + detailSuffix("invalid media data");
        return MpvPlayer.ERROR_DECODE_FAILED + detailSuffix("no playable audio/video output");
    }

    public synchronized IOException classifyLoadError(@Nullable Throwable cause, @Nullable String detail) {
        String code;
        if (sawDrmError) code = MpvPlayer.ERROR_DRM_UNSUPPORTED;
        else if (sawVideoOutputError) code = MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED;
        else if (sawNetworkError) code = MpvPlayer.ERROR_NETWORK_FAILED;
        else if (sawInvalidData || sawPngVideo) code = MpvPlayer.ERROR_INVALID_MEDIA_DATA;
        else if (sawNoAvData) code = MpvPlayer.ERROR_NO_AV_DATA;
        else code = MpvPlayer.ERROR_LOAD_FAILED;
        return cause == null ? mpvError(code, detail) : mpvError(code, detail, cause);
    }

    public IOException nativeEndFileError(int reason, int error, @Nullable String errorText) {
        return mpvError(nativeEndFileErrorCode(error), nativeEndFileDetail(reason, error, errorText));
    }

    public synchronized String nativeEndFileErrorCode(int error) {
        if (sawDrmError) return MpvPlayer.ERROR_DRM_UNSUPPORTED;
        if (sawVideoOutputError || error == MPVLib.MpvError.MPV_ERROR_VO_INIT_FAILED) return MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED;
        if (sawNetworkError) return MpvPlayer.ERROR_NETWORK_FAILED;
        if (host.getLifecycleCoordinator().isCurrentLikelyHls() && (sawNoAvData
                || sawInvalidData
                || sawPngVideo
                || error == MPVLib.MpvError.MPV_ERROR_NOTHING_TO_PLAY
                || error == MPVLib.MpvError.MPV_ERROR_UNKNOWN_FORMAT
                || error == MPVLib.MpvError.MPV_ERROR_UNSUPPORTED)) {
            return MpvPlayer.ERROR_HLS_PLAYBACK_FAILED;
        }
        if (sawNoAvData || error == MPVLib.MpvError.MPV_ERROR_NOTHING_TO_PLAY) return MpvPlayer.ERROR_NO_AV_DATA;
        if (sawInvalidData
                || sawPngVideo
                || error == MPVLib.MpvError.MPV_ERROR_UNKNOWN_FORMAT
                || error == MPVLib.MpvError.MPV_ERROR_UNSUPPORTED) {
            return MpvPlayer.ERROR_INVALID_MEDIA_DATA;
        }
        if (sawDecodeError) return MpvPlayer.ERROR_DECODE_FAILED;
        return error == MPVLib.MpvError.MPV_ERROR_LOADING_FAILED ? MpvPlayer.ERROR_LOAD_FAILED : MpvPlayer.ERROR_DECODE_FAILED;
    }

    public int nativeEndFilePlaybackExceptionCode(int error) {
        if (error == MPVLib.MpvError.MPV_ERROR_LOADING_FAILED) return PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
        if (error == MPVLib.MpvError.MPV_ERROR_VO_INIT_FAILED) return PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED;
        return PlaybackException.ERROR_CODE_DECODING_FAILED;
    }

    public String nativeEndFileDetail(int reason, int error, @Nullable String errorText) {
        StringBuilder builder = new StringBuilder();
        builder.append("native end-file reason=").append(endFileReasonName(reason)).append('(').append(reason).append(')');
        builder.append(" error=").append(mpvErrorName(error)).append('(').append(error).append(')');
        if (!TextUtils.isEmpty(errorText)) builder.append(' ').append(errorText);
        return builder.toString();
    }

    public String endFileReasonName(int reason) {
        return switch (reason) {
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF -> "eof";
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_STOP -> "stop";
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_QUIT -> "quit";
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR -> "error";
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_REDIRECT -> "redirect";
            case MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_UNKNOWN -> "unknown";
            default -> "unknown";
        };
    }

    public String mpvErrorName(int error) {
        return switch (error) {
            case MPVLib.MpvError.MPV_ERROR_SUCCESS -> "success";
            case MPVLib.MpvError.MPV_ERROR_LOADING_FAILED -> "loading_failed";
            case MPVLib.MpvError.MPV_ERROR_AO_INIT_FAILED -> "ao_init_failed";
            case MPVLib.MpvError.MPV_ERROR_VO_INIT_FAILED -> "vo_init_failed";
            case MPVLib.MpvError.MPV_ERROR_NOTHING_TO_PLAY -> "nothing_to_play";
            case MPVLib.MpvError.MPV_ERROR_UNKNOWN_FORMAT -> "unknown_format";
            case MPVLib.MpvError.MPV_ERROR_UNSUPPORTED -> "unsupported";
            case MPVLib.MpvError.MPV_ERROR_GENERIC -> "generic";
            default -> "unknown";
        };
    }

    public IOException mpvError(String code, @Nullable String detail) {
        return new IOException(code + detailSuffix(detail));
    }

    public IOException mpvError(String code, @Nullable String detail, Throwable cause) {
        return new IOException(code + detailSuffix(detail), cause);
    }

    public String detailSuffix(@Nullable String detail) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(detail)) builder.append(": ").append(detail);
        String recent = recentLogSuffix();
        if (!TextUtils.isEmpty(recent)) builder.append(recent);
        return builder.toString();
    }

    public synchronized void onBaseMsgLevelOptionSet(String value) {
        this.diagnosticBaseMsgLevel = value;
    }

    public synchronized void onMsgLevelChanged(String level, MpvDiagnosticCollector diagnostics) {
        if (!level.equals(diagnosticAppliedMsgLevel)) {
            diagnosticBaseMsgLevel = level;
            diagnosticLogLevelApplied = false;
        }
        syncDiagnosticLogLevel(diagnostics);
    }

    public synchronized void syncDiagnosticLogLevel(MpvDiagnosticCollector diagnostics) {
        if (!com.github.catvod.crawler.DebugLogStore.isEnabled()) diagnostics.felBindProbe().cancel();
        if (!host.isInitialized() || host.isReleased()) return;
        boolean enabled = SpiderDebug.isEnabled();
        boolean deep = enabled && diagnostics.depthActive();
        int categories = com.github.catvod.crawler.DebugLogStore.categories();
        if (enabled == diagnosticLogLevelApplied && (!enabled || deep == diagnosticDepthApplied && categories == diagnosticCategoriesApplied)
                || diagnosticBaseMsgLevel == null) return;
        String value = enabled ? MpvDiagnosticsPolicy.diagnosticLogLevel(diagnosticBaseMsgLevel, categories, deep) : diagnosticBaseMsgLevel;
        String previous = diagnosticAppliedMsgLevel;
        diagnosticAppliedMsgLevel = value;
        if (host.setRuntimeStringChecked("msg-level", value)) {
            diagnosticLogLevelApplied = enabled;
            diagnosticDepthApplied = deep;
            diagnosticCategoriesApplied = categories;
            host.setDiagnosticTracking(enabled, deep, categories);
        } else {
            diagnosticAppliedMsgLevel = previous;
        }
    }

    public boolean shouldCollectDebugDetails() {
        return MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.DEBUG_LOG, SpiderDebug.isEnabled());
    }

    public void logSourceDiagnostics(MediaItem item, String uri, Map<String, String> headers) {
        if (!shouldCollectDebugDetails()) return;
        String scheme = "";
        boolean loopback = false;
        boolean pathIso = false;
        try {
            Uri parsed = Uri.parse(uri);
            scheme = String.valueOf(parsed.getScheme());
            String hostName = String.valueOf(parsed.getHost());
            loopback = "127.0.0.1".equals(hostName) || "localhost".equalsIgnoreCase(hostName) || "::1".equals(hostName);
            String path = parsed.getPath();
            pathIso = path != null && path.toLowerCase(Locale.US).endsWith(".iso");
        } catch (Throwable ignored) {
        }
        String mime = item != null && item.localConfiguration != null ? item.localConfiguration.mimeType : null;
        String mediaId = item == null ? null : item.mediaId;
        CharSequence title = item == null ? null : item.mediaMetadata.title;
        PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "source diagnostic scheme=" + scheme
                + " loopback=" + loopback
                + " urlLen=" + (uri == null ? 0 : uri.length())
                + " pathIso=" + pathIso
                + " mime=" + mime
                + " mediaIdLen=" + (mediaId == null ? 0 : mediaId.length())
                + " mediaIdIso=" + (mediaId != null && mediaId.toLowerCase(Locale.US).contains(".iso"))
                + " titleIso=" + (title != null && title.toString().toLowerCase(Locale.US).contains(".iso"))
                + " headers=" + (headers == null ? 0 : headers.size()));
    }

    public String diagnosticSummary(VideoSize videoSize, long cachedPositionMs, long cachedDurationMs, Tracks tracks) {
        MpvLifecycleCoordinator lifecycle = host.getLifecycleCoordinator();
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        List<String> parts = new ArrayList<>();
        parts.add("trace=" + host.getPlaybackTraceId());
        parts.add("source=" + MpvDiagnosticsPolicy.sourceSummary(lifecycle.getCurrentPlayableUri()));
        parts.add("hls=" + lifecycle.isCurrentLikelyHls());
        parts.add("dash=" + lifecycle.isCurrentLikelyDash());
        parts.add("loaded=" + lifecycle.isFileLoaded());
        parts.add("started=" + lifecycle.isLoadStarted());
        parts.add("restart=" + lifecycle.isPlaybackRestarted());
        parts.add("size=" + videoSize.width + "x" + videoSize.height);
        parts.add("position=" + cachedPositionMs);
        parts.add("duration=" + cachedDurationMs);
        parts.add("tracks=" + (tracks != null ? tracks.getGroups().size() : 0));
        parts.add("audio-decoder=" + propertyDispatcher.getCachedAudioDecoder());
        parts.add("hwdec=" + propertyDispatcher.getCachedHwdecCurrent());
        parts.add("vo=" + propertyDispatcher.getCachedCurrentVo());
        parts.add("shader=-");
        parts.add("end-file=" + endFileReasonName(lifecycle.getLastEndFileReason()) + "/" + mpvErrorName(lifecycle.getLastEndFileError()) + "(" + lifecycle.getLastEndFileError() + ")");
        if (!TextUtils.isEmpty(lifecycle.getLastEndFileErrorText())) {
            parts.add("end-file-text=" + MpvDiagnosticsPolicy.redactSensitive(lifecycle.getLastEndFileErrorText()));
        }
        if (lifecycle.isCurrentLikelyHls()) {
            parts.add("hls-proxy=" + host.getHlsCoordinator().diagnostics());
        }
        return String.join(" ", parts);
    }

    public void handleLogMessage(String prefix, int level, String text, MpvDiagnosticCollector diagnostics) {
        if (host.isReleased()) return;
        diagnostics.nativeLog(prefix, level, text);
        if (MpvDiagnosticCollector.isHook(text)) return;
        int performanceKind = MpvDiagnosticsPolicy.felPerformanceKind(prefix, level, text);
        if (performanceKind >= 0) return;
        if (diagnosticLogLevelApplied && !MpvDiagnosticsPolicy.includedByLogLevel(prefix, level, diagnosticBaseMsgLevel)) return;
        String line = MpvDiagnosticsPolicy.redactSensitive(prefix + ": " + text);
        String traceId = host.getPlaybackTraceId();
        long logGeneration = host.getPropertyGeneration();
        boolean debug = SpiderDebug.isEnabled();
        boolean fatalFel = MpvDiagnosticsPolicy.isFatalFelLog(level, text);
        long enqueuedAtMs = debug ? SystemClock.elapsedRealtime() : 0;
        host.postToMain(() -> {
            if (host.isReleased() || logGeneration != host.getPropertyGeneration()) return;
            if (enqueuedAtMs > 0) {
                long nowMs = SystemClock.elapsedRealtime();
                long delayMs = Math.max(0, nowMs - enqueuedAtMs);
                if (delayMs >= MAIN_THREAD_STALL_THRESHOLD_MS
                        && nowMs - lastNativeLogQueueWarningAtMs >= MAIN_THREAD_STALL_LOG_INTERVAL_MS) {
                    lastNativeLogQueueWarningAtMs = nowMs;
                    PlaybackTrace.log("mpv-anr", traceId, "native-log-queue delay=%dms level=%d", delayMs, level);
                }
            }
            rememberLog(line);
            markFailureSignal(line);
            if (fatalFel && host.getPlayerError() == null) {
                host.fail(mpvError(MpvPlayer.ERROR_DECODE_FAILED, line), PlaybackException.ERROR_CODE_DECODING_FAILED);
                return;
            }
            host.maybeRetryDtsHdAsCore(line);
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("sub") || lower.contains("font") || lower.contains("track switched") || lower.contains("mkv: select track")) {
                appendSubtitleDiagnostic("native " + line);
            }
        });
    }

    public void refreshRenderState(MpvDiagnosticsPolicy.Request request) {
        if (!host.isInitialized() || !MpvDiagnosticsPolicy.allowsSynchronousProperties(request, SpiderDebug.isEnabled())) return;
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        propertyDispatcher.refreshRenderState(
                host.stringProperty("current-vo", propertyDispatcher.getCachedCurrentVo()),
                host.stringProperty("current-gpu-context", propertyDispatcher.getCachedCurrentGpuContext()),
                host.stringProperty("gpu-api", propertyDispatcher.getCachedGpuApi()));
    }

    public void refreshRuntimeDiagnostics(MpvDiagnosticsPolicy.Request request) {
        if (!host.isInitialized() || !MpvDiagnosticsPolicy.allowsSynchronousProperties(request, SpiderDebug.isEnabled())) return;
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        propertyDispatcher.refreshRuntimeDiagnostics(
                host.stringProperty("current-ao", propertyDispatcher.getCachedCurrentAo()),
                host.stringProperty("audio-device", propertyDispatcher.getCachedAudioDevice()),
                host.stringProperty("hwdec-current", propertyDispatcher.getCachedHwdecCurrent()),
                host.doubleProperty("avsync", propertyDispatcher.getCachedAvSyncSeconds()),
                host.doubleProperty("display-fps", propertyDispatcher.getCachedDisplayFps()),
                host.doubleProperty("estimated-display-fps", propertyDispatcher.getCachedEstimatedDisplayFps()),
                Math.max(0, host.longProperty("decoder-frame-drop-count", propertyDispatcher.getCachedDecoderDroppedFrames())),
                Math.max(0, host.longProperty("frame-drop-count", propertyDispatcher.getCachedOutputDroppedFrames())),
                Math.max(0, host.longProperty("mistimed-frame-count", propertyDispatcher.getCachedMistimedFrames())),
                Math.max(0, host.longProperty("vo-delayed-frame-count", propertyDispatcher.getCachedDelayedFrames())),
                host.booleanProperty("display-sync-active", propertyDispatcher.isCachedDisplaySyncActive()));
    }

    public String getRenderDiagnostics() {
        refreshRenderState(MpvDiagnosticsPolicy.Request.PANEL);
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        MpvPlayerConfig config = host.getConfig();
        String requested = isConfiguredVulkan() ? "vulkan" : "opengl";
        String currentVo = firstNonEmpty(propertyDispatcher.getCachedCurrentVo(), config.vo());
        String currentGpuContext = firstNonEmpty(propertyDispatcher.getCachedCurrentGpuContext(), config.gpuContext());
        String gpuApi = firstNonEmpty(propertyDispatcher.getCachedGpuApi(), config.gpuApi(), config.openglEs() ? "opengl-es" : "");
        String actual = isRuntimeVulkan(currentVo, currentGpuContext, gpuApi) ? "vulkan" : "opengl";
        return "请求 " + requested
                + " / 实际 " + actual
                + " / vo " + emptyDash(currentVo)
                + " / context " + emptyDash(currentGpuContext)
                + " / api " + emptyDash(gpuApi);
    }

    public String getRuntimeDiagnostics() {
        refreshRuntimeDiagnostics(MpvDiagnosticsPolicy.Request.PANEL);
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        MpvPlayerConfig config = host.getConfig();
        String hwdec = firstNonEmpty(propertyDispatcher.getCachedHwdecCurrent(), config.hwdec());
        String ao = firstNonEmpty(propertyDispatcher.getCachedCurrentAo(), config.ao());
        String audioDevice = firstNonEmpty(propertyDispatcher.getCachedAudioDevice());
        return joinParts(
                "hwdec " + emptyDash(hwdec),
                "ao " + emptyDash(ao),
                TextUtils.isEmpty(audioDevice) ? "" : "device " + shortText(audioDevice, 32),
                propertyDispatcher.formatAvSync(),
                propertyDispatcher.formatDisplayFps(),
                propertyDispatcher.formatDisplaySync());
    }

    public long getDroppedFrames() {
        refreshRuntimeDiagnostics(MpvDiagnosticsPolicy.Request.PANEL);
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        return Math.max(0, propertyDispatcher.getCachedDecoderDroppedFrames()) + Math.max(0, propertyDispatcher.getCachedOutputDroppedFrames());
    }

    public boolean hasObservedDroppedFrames() {
        return host.getPropertyDispatcher().isObservedDroppedFrames();
    }

    public long getObservedDroppedFrames() {
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        return Math.max(0, propertyDispatcher.getCachedDecoderDroppedFrames()) + Math.max(0, propertyDispatcher.getCachedOutputDroppedFrames());
    }

    public MpvPlayer.FrameTimingSnapshot getFrameTimingSnapshot() {
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        double displayFps = propertyDispatcher.getCachedEstimatedDisplayFps() > 0
                ? propertyDispatcher.getCachedEstimatedDisplayFps() : propertyDispatcher.getCachedDisplayFps();
        return new MpvPlayer.FrameTimingSnapshot(
                Math.max(0, propertyDispatcher.getCachedDecoderDroppedFrames()),
                Math.max(0, propertyDispatcher.getCachedOutputDroppedFrames()),
                Math.max(0, propertyDispatcher.getCachedMistimedFrames()),
                Math.max(0, propertyDispatcher.getCachedDelayedFrames()),
                propertyDispatcher.getCachedAvSyncSeconds(),
                getObservedContentFrameRate(),
                displayFps > 0 && Double.isFinite(displayFps) ? displayFps : 0,
                propertyDispatcher.isObservedDroppedFrames());
    }

    public float getObservedContentFrameRate() {
        float rate = host.getPropertyDispatcher().getCachedContentFrameRate();
        return rate > 0 && Float.isFinite(rate) ? rate : 0f;
    }

    public float getObservedDisplayFrameRate() {
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        double value = propertyDispatcher.getCachedEstimatedDisplayFps() > 0 ? propertyDispatcher.getCachedEstimatedDisplayFps() : propertyDispatcher.getCachedDisplayFps();
        return value > 0 && Double.isFinite(value) && value <= Float.MAX_VALUE ? (float) value : 0f;
    }

    public String getObservedHwdecCurrent() {
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        return !propertyDispatcher.isObservedHwdecCurrent() || propertyDispatcher.getCachedHwdecCurrent() == null ? "" : propertyDispatcher.getCachedHwdecCurrent();
    }

    public boolean hasObservedHwdecCurrent() {
        return host.getPropertyDispatcher().isObservedHwdecCurrent();
    }

    public String getObservedCurrentVideoOutput() {
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        return !propertyDispatcher.isObservedCurrentVo() || propertyDispatcher.getCachedCurrentVo() == null ? "" : propertyDispatcher.getCachedCurrentVo();
    }

    public boolean hasObservedCurrentVideoOutput() {
        return host.getPropertyDispatcher().isObservedCurrentVo();
    }

    private boolean isConfiguredVulkan() {
        MpvPlayerConfig config = host.getConfig();
        return TextUtils.equals(config.vo(), "gpu-next")
                && TextUtils.equals(config.gpuContext(), "androidvk")
                && TextUtils.equals(config.gpuApi(), "vulkan");
    }

    private boolean isRuntimeVulkan(String currentVo, String currentGpuContext, String gpuApi) {
        return containsIgnoreCase(currentGpuContext, "vk")
                || containsIgnoreCase(currentGpuContext, "vulkan")
                || containsIgnoreCase(gpuApi, "vulkan")
                || containsIgnoreCase(currentVo, "gpu-next") && isConfiguredVulkan();
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null && value.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US));
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

    private static String shortText(String value, int maxLength) {
        if (TextUtils.isEmpty(value) || maxLength <= 0 || value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 1) + "...";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String emptyDash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        MpvTrackCoordinator trackCoordinator = host.getTrackCoordinator();
        MpvPropertyDispatcher propertyDispatcher = host.getPropertyDispatcher();
        MpvLifecycleCoordinator lifecycleCoordinator = host.getLifecycleCoordinator();
        PlaybackException playerError = host.getPlayerError();

        AudioPlaybackDiagnostics.Track current = trackCoordinator.getSelectedAudioTrackDiagnostics();
        boolean automaticTrackDowngrade = !TextUtils.isEmpty(
                trackCoordinator.getAutomaticAudioDowngradeReason())
                && sameAudioTrack(current, trackCoordinator.getAutomaticAudioActiveTrack());
        AudioPlaybackDiagnostics.Track original = automaticTrackDowngrade
                ? trackCoordinator.getAutomaticAudioOriginalTrack() : current;
        AudioPlaybackDiagnostics.Track active = current;
        String reason = automaticTrackDowngrade
                ? trackCoordinator.getAutomaticAudioDowngradeReason() : "";

        String outputFormat = propertyDispatcher.getCachedAudioOutFormat();
        AudioPlaybackDiagnostics.OutputMode outputMode =
                AudioPlaybackDiagnostics.mpvOutputMode(outputFormat);
        if (outputMode == AudioPlaybackDiagnostics.OutputMode.PASSTHROUGH) {
            AudioPlaybackDiagnostics.Track passthrough =
                    AudioPlaybackDiagnostics.passthroughTrack(active, outputFormat);
            String originalCodec = original.codec().toLowerCase(Locale.US);
            if ("DTS Core".equals(passthrough.codec())
                    && (originalCodec.contains("dts-hd")
                    || originalCodec.contains("dts:x"))) {
                AudioPlaybackDiagnostics.Track dtsFallback = lifecycleCoordinator.getDtsHdFallbackSourceTrack();
                if (dtsFallback.available()) {
                    original = dtsFallback;
                }
                active = AudioPlaybackDiagnostics.passthroughTrack(original, outputFormat);
                reason = "dts-hd-core";
            } else {
                active = passthrough;
            }
        }
        int outputChannels = propertyDispatcher.getCachedAudioOutChannels();
        int outputSampleRate = propertyDispatcher.getCachedAudioOutSampleRate();
        String cachedAudioDecoder = propertyDispatcher.getCachedAudioDecoder();
        AudioPlaybackDiagnostics.DecodeMode decodeMode =
                outputMode == AudioPlaybackDiagnostics.OutputMode.PASSTHROUGH
                        ? AudioPlaybackDiagnostics.DecodeMode.NONE
                        : isRawPcmTrack(active)
                        ? AudioPlaybackDiagnostics.DecodeMode.NONE
                        : outputMode == AudioPlaybackDiagnostics.OutputMode.PCM
                        ? mpvAudioDecodeMode(cachedAudioDecoder)
                        : AudioPlaybackDiagnostics.DecodeMode.UNKNOWN;
        if (isAudioDiagnosticsFailure(current, playerError, propertyDispatcher)) {
            AudioPlaybackDiagnostics.FailureReason failureReason =
                    AudioPlaybackDiagnostics.failureReason(playerError.errorCode);
            AudioPlaybackDiagnostics.DecodeMode attemptedDecode =
                    cachedAudioDecoder == null || cachedAudioDecoder.isBlank()
                            ? decodeMode : mpvAudioDecodeMode(cachedAudioDecoder);
            return new AudioPlaybackDiagnostics.Snapshot(original, active,
                    attemptedDecode, cachedAudioDecoder, outputMode, outputChannels,
                    outputSampleRate, false, reason,
                    AudioPlaybackDiagnostics.lastAttemptLevel(
                            failureReason, outputMode, attemptedDecode),
                    AudioPlaybackDiagnostics.RuntimeState.FAILED, failureReason);
        }
        return new AudioPlaybackDiagnostics.Snapshot(original, active,
                decodeMode, cachedAudioDecoder, outputMode, outputChannels,
                outputSampleRate, false, reason);
    }

    private boolean isAudioDiagnosticsFailure(
            AudioPlaybackDiagnostics.Track current,
            @Nullable PlaybackException playerError,
            MpvPropertyDispatcher propertyDispatcher) {
        if (playerError == null || current == null || !current.available()) return false;
        String message = playerError.getMessage();
        if (message != null && (message.startsWith(MpvPlayer.ERROR_NETWORK_FAILED)
                || message.startsWith(MpvPlayer.ERROR_NO_AV_DATA)
                || message.startsWith(MpvPlayer.ERROR_INVALID_MEDIA_DATA)
                || message.startsWith(MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED)
                || message.startsWith(MpvPlayer.ERROR_DRM_UNSUPPORTED))) {
            return false;
        }
        return AudioPlaybackDiagnostics.failureReason(playerError.errorCode)
                != AudioPlaybackDiagnostics.FailureReason.UNKNOWN
                || !TextUtils.isEmpty(propertyDispatcher.getCachedAudioDecoder())
                || !TextUtils.isEmpty(propertyDispatcher.getCachedAudioFormat());
    }

    public static boolean sameAudioTrack(AudioPlaybackDiagnostics.Track first,
                                         AudioPlaybackDiagnostics.Track second) {
        if (first == null || second == null || !first.available() || !second.available()) {
            return false;
        }
        return TextUtils.equals(first.codec(), second.codec())
                && (first.channels() <= 0 || second.channels() <= 0
                || first.channels() == second.channels())
                && (first.sampleRate() <= 0 || second.sampleRate() <= 0
                || first.sampleRate() == second.sampleRate());
    }

    public static boolean isRawPcmTrack(AudioPlaybackDiagnostics.Track track) {
        return track != null && "PCM".equalsIgnoreCase(track.codec());
    }

    public static AudioPlaybackDiagnostics.DecodeMode mpvAudioDecodeMode(String decoderName) {
        if (TextUtils.isEmpty(decoderName)) {
            return AudioPlaybackDiagnostics.DecodeMode.UNKNOWN;
        }
        String lower = decoderName.toLowerCase(Locale.US);
        if (lower.contains("mediacodec")) {
            return AudioPlaybackDiagnostics.DecodeMode.HARDWARE;
        }
        if (lower.startsWith("spdif_")) {
            return AudioPlaybackDiagnostics.DecodeMode.UNKNOWN;
        }
        return AudioPlaybackDiagnostics.DecodeMode.SOFTWARE;
    }
}
