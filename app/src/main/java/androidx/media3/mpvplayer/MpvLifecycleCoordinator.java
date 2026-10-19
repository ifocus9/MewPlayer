package androidx.media3.mpvplayer;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.iso.IsoSessionManager;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.player.mpv.MpvNetworkRecoveryPolicy;
import com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.net.HttpHeaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;

/**
 * Coordinates media preparation, playback lifecycle, MPV native events,
 * and error/end-file transitions for MpvPlayer.
 */
public final class MpvLifecycleCoordinator {

    private static final String TAG = "TV-mpv";
    private static final double SECONDS_TO_MS = 1000.0;
    private static final long LOAD_START_RETRY_DELAY_MS = 800;
    private static final long END_FILE_VALIDATION_DELAY_MS = 250;
    private static final long INITIAL_TRACK_SELECTION_LOAD_TIMEOUT_MS = 2500;
    private static final long INITIAL_TRACK_SELECTION_RESTORE_TIMEOUT_MS = 1500;

    private static final String HLS_LOAD_OPTIONS =
            "demuxer-lavf-format=hls,demuxer-lavf-probesize=2000000,demuxer-lavf-analyzeduration=2";
    private static final String DASH_LOAD_OPTIONS =
            "demuxer-lavf-format=dash,demuxer-lavf-probesize=2000000,demuxer-lavf-analyzeduration=2";

    public interface Host {
        Context getContext();
        MpvPlayerConfig getConfig();
        @Nullable MediaItem getMediaItem();
        String getPlaybackTraceId();
        PlaybackParameters getPlaybackParameters();
        float getVolume();
        boolean isRepeatOne();
        String getInitialSubtitleTrackId();
        long getInitialSeekPositionMs();
        void setInitialSeekPositionMs(long pos);
        long getCachedPositionMs();
        void setCachedPositionMs(long pos);
        long getCachedDurationMs();
        void setCachedDurationMs(long duration);
        int getPlaybackState();
        void setPlaybackState(int state);
        boolean isLoading();
        void setLoading(boolean loading);
        boolean isPlayWhenReady();
        boolean isInitialTrackSelectionGateActive();
        void setInitialTrackSelectionGateActive(boolean active);
        boolean isReleased();
        PlaybackException getPlayerError();
        void setPlayerError(PlaybackException error);
        VideoSize getVideoSize();
        void setVideoSize(VideoSize size);

        MpvSurfaceCoordinator getSurfaceCoordinator();
        MpvTrackCoordinator getTrackCoordinator();
        MpvCacheCoordinator getCacheCoordinator();
        MpvHlsCoordinator getHlsCoordinator();
        MpvDiscNavController getDiscNavController();
        MpvPropertyDispatcher getPropertyDispatcher();
        MpvMediaReplacementCoordinator getMediaReplacementCoordinator();
        MpvSeekPositionState getSeekPositionState();
        MpvDiagnosticCollector getDiagnostics();

        boolean isInitialized();
        void setInitialized(boolean initialized);
        boolean enqueueMpvCommand(String... command);
        void mpvCommand(String[] command);
        int mpvSetOptionString(String name, String value);
        void safeSetPropertyString(String name, String value);
        void safeSetPropertyBoolean(String name, boolean value);
        void safeSetPropertyDouble(String name, double value);
        void safeSetPropertyLong(String name, long value);
        String stringProperty(String property, String fallback);
        boolean booleanProperty(String property, boolean fallback);
        double doubleProperty(String property, double fallback);
        int intProperty(String property, int fallback);
        long longProperty(String property, long fallback);
        void observe(String property, int format);
        long doubleSecondsToMs(@Nullable Object value, long fallback);
        void seekMpv(long targetPositionMs);
        void invalidateState();
        void startStateRefresh();
        void stopStateRefresh();
        void startMainThreadWatchdog();
        void stopMainThreadWatchdog();
        void postToMain(Runnable r);
        void removeMainCallbacks(Runnable r);
        void postDelayedToMain(Runnable r, long delayMs);
        void closeContentFds();
        void applySubtitleStyle();
        void applyTextOffset();
        void applyAudioOffset();
        void updateVideoSize(String reason);
        void scheduleTrackRefresh(String reason);
        void refreshTracks();
        void refreshChapters();
        void addSubtitleConfigurations();
        void closeIsoSession();
        void requestIsoOsdSurface();
        void attachIsoTrackMetadataListener();
        void logSourceDiagnostics(MediaItem item, String playableUri, Map<String, String> headers);
        void resetFailureSignals();
        void resumeMediaReplacement(String reason);
        void applyPreloadDiskCacheMode();
        void applyCacheTimePolicy();
        void updatePreloadCacheOverlay();
        void restorePreloadCacheOverlay();
        void clearCoalescedPropertyEvents();
        void cancelScheduledTrackRefresh();
        void clearSurfaceFrameRate();
        void resetCacheState();
        void appendSubtitleDiagnostic(String line);
        boolean shouldCollectDebugDetails();
        String diagnosticSummary();
        boolean shouldPauseNativePlayback();
        void bindVideoOutput();
        void applyShaderPipeline(boolean force);
        void scheduleLoadStartRetry();
        void ensureNativeContextOwner() throws IOException;
        void syncDiagnosticLogLevel();
        String endFileReasonName(int reason);
        String mpvErrorName(int error);
        Throwable nativeEndFileError(int reason, int error, @Nullable String errorText);
        int nativeEndFilePlaybackExceptionCode(int error);
        Throwable classifyLoadError(Throwable e, String message);
        String failedLoadedMediaMessage();
        boolean isFailedLoadedMedia();
        void clearRecentLogs();
        String playableUri(MediaItem item) throws IOException;
        boolean isImageUri(String uri);
        boolean sameUri(String a, String b);
    }

    private final Host host;

    private boolean loadStarted;
    private boolean fileLoaded;
    private long fileLoadedAtElapsedRealtimeMs;
    private boolean playbackRestarted;
    private boolean stopping;
    private boolean eofReached;
    private boolean idleActive;
    private long loadStartPositionMs = C.TIME_UNSET;
    private int loadStartRetryCount;
    private int videoReconfigCount;
    private String currentPlayableUri;
    private String currentIsoUri;
    private boolean currentLikelyHls;
    private boolean currentLikelyDash;
    private PlaybackResourceClassifier.Classification resourceClassification;
    private long pendingSurfaceLoadGeneration = C.INDEX_UNSET;
    private int lastEndFileReason;
    private int lastEndFileError;
    private String lastEndFileErrorText;
    private boolean dtsHdCoreFallbackAttempted;
    private String activeAudioSpdif;
    private AudioPlaybackDiagnostics.Track dtsHdFallbackSourceTrack = AudioPlaybackDiagnostics.Track.empty();

    public MpvLifecycleCoordinator(Host host) {
        this.host = host;
        this.activeAudioSpdif = host.getConfig() != null ? host.getConfig().audioSpdif() : null;
    }

    public synchronized boolean isLoadStarted() {
        return loadStarted;
    }

    public synchronized void setLoadStarted(boolean started) {
        this.loadStarted = started;
    }

    public synchronized boolean isFileLoaded() {
        return fileLoaded;
    }

    public synchronized void setFileLoaded(boolean loaded) {
        this.fileLoaded = loaded;
    }

    public synchronized long getFileLoadedAtElapsedRealtimeMs() {
        return fileLoadedAtElapsedRealtimeMs;
    }

    public synchronized boolean isPlaybackRestarted() {
        return playbackRestarted;
    }

    public synchronized void setPlaybackRestarted(boolean restarted) {
        this.playbackRestarted = restarted;
    }

    public synchronized boolean isStopping() {
        return stopping;
    }

    public synchronized void setStopping(boolean stopping) {
        this.stopping = stopping;
    }

    public synchronized boolean isEofReached() {
        return eofReached;
    }

    public synchronized void setEofReached(boolean eof) {
        this.eofReached = eof;
    }

    public synchronized boolean isIdleActive() {
        return idleActive;
    }

    public synchronized void setIdleActive(boolean idle) {
        this.idleActive = idle;
    }

    public synchronized String getCurrentPlayableUri() {
        return currentPlayableUri;
    }

    public synchronized void setCurrentPlayableUri(String uri) {
        this.currentPlayableUri = uri;
    }

    public synchronized String getCurrentIsoUri() {
        return currentIsoUri;
    }

    public synchronized void setCurrentIsoUri(String uri) {
        this.currentIsoUri = uri;
    }

    public synchronized boolean isCurrentLikelyHls() {
        return currentLikelyHls;
    }

    public synchronized boolean isCurrentLikelyDash() {
        return currentLikelyDash;
    }

    public synchronized PlaybackResourceClassifier.Classification getResourceClassification() {
        return resourceClassification;
    }

    public synchronized long getPendingSurfaceLoadGeneration() {
        return pendingSurfaceLoadGeneration;
    }

    public synchronized int getLastEndFileReason() {
        return lastEndFileReason;
    }

    public synchronized int getLastEndFileError() {
        return lastEndFileError;
    }

    public synchronized String getLastEndFileErrorText() {
        return lastEndFileErrorText;
    }

    public synchronized boolean isDtsHdCoreFallbackAttempted() {
        return dtsHdCoreFallbackAttempted;
    }

    public synchronized String getActiveAudioSpdif() {
        return activeAudioSpdif;
    }

    public synchronized AudioPlaybackDiagnostics.Track getDtsHdFallbackSourceTrack() {
        return dtsHdFallbackSourceTrack;
    }

    public synchronized void setDtsHdFallbackSourceTrack(AudioPlaybackDiagnostics.Track track) {
        this.dtsHdFallbackSourceTrack = track != null ? track : AudioPlaybackDiagnostics.Track.empty();
    }

    public synchronized int getVideoReconfigCount() {
        return videoReconfigCount;
    }

    public synchronized void resetEndFileState() {
        this.lastEndFileReason = MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_UNKNOWN;
        this.lastEndFileError = MPVLib.MpvError.MPV_ERROR_SUCCESS;
        this.lastEndFileErrorText = null;
    }

    public synchronized int getLoadStartRetryCount() {
        return loadStartRetryCount;
    }

    public synchronized void incrementLoadStartRetryCount() {
        this.loadStartRetryCount++;
    }

    public synchronized long getLoadStartPositionMs() {
        return loadStartPositionMs;
    }

    public synchronized void setLoadStartPositionMs(long pos) {
        this.loadStartPositionMs = pos;
    }

    public synchronized void setPendingSurfaceLoadGeneration(long generation) {
        this.pendingSurfaceLoadGeneration = generation;
    }

    public synchronized void setLastEndFileReason(int reason) {
        this.lastEndFileReason = reason;
    }

    public synchronized void setLastEndFileError(int error) {
        this.lastEndFileError = error;
    }

    public synchronized void setLastEndFileErrorText(String text) {
        this.lastEndFileErrorText = text;
    }

    public synchronized void setActiveAudioSpdif(String spdif) {
        this.activeAudioSpdif = spdif;
    }

    public synchronized void resetForNewMedia() {
        pendingSurfaceLoadGeneration = C.INDEX_UNSET;
        fileLoaded = false;
        fileLoadedAtElapsedRealtimeMs = 0;
        playbackRestarted = false;
        loadStarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        idleActive = false;
        currentPlayableUri = null;
        currentIsoUri = null;
        currentLikelyHls = false;
        currentLikelyDash = false;
        loadStartPositionMs = C.TIME_UNSET;
    }

    public synchronized void onNativeContextReleased() {
        stopping = false;
        loadStarted = false;
        loadStartRetryCount = 0;
    }

    public void retryLoadIfNotStarted() {
        if (host.isReleased() || isLoadStarted() || isFileLoaded() || host.getPlayerError() != null) return;
        if (host.getPlaybackState() != Player.STATE_BUFFERING || TextUtils.isEmpty(currentPlayableUri)) return;
        if (loadStartRetryCount >= 3) return;
        loadStartRetryCount++;
        if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "load retry attempt=%d source=%s idle=%s", loadStartRetryCount, MpvDiagnosticsPolicy.sourceSummary(currentPlayableUri), host.booleanProperty("idle-active", idleActive));
        try {
            loadCurrentUri();
            host.scheduleLoadStartRetry();
        } catch (Throwable e) {
            fail(host.classifyLoadError(e, e.getMessage()), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    public void openCurrent(long generation) {
        MediaItem mediaItem = host.getMediaItem();
        if (!host.getMediaReplacementCoordinator().isCurrent(generation) || mediaItem == null || mediaItem.localConfiguration == null) return;
        host.getDiagnostics().begin(host.getPlaybackTraceId());
        host.startMainThreadWatchdog();
        try {
            host.ensureNativeContextOwner();
            if (!host.getMediaReplacementCoordinator().isCurrent(generation)) return;
            restoreConfiguredAudioSpdifForNewMedia();
            host.setPlaybackState(Player.STATE_BUFFERING);
            host.setLoading(true);
            host.setPlayerError(null);
            fileLoaded = false;
            fileLoadedAtElapsedRealtimeMs = 0;
            loadStarted = false;
            playbackRestarted = false;
            loadStartPositionMs = C.TIME_UNSET;
            loadStartRetryCount = 0;
            videoReconfigCount = 0;
            eofReached = false;
            idleActive = false;
            host.setCachedDurationMs(C.TIME_UNSET);
            host.getCacheCoordinator().resetCacheTimelineForSeek(0);
            host.getPropertyDispatcher().resetRuntimeDiagnostics();
            host.resetFailureSignals();
            host.clearRecentLogs();
            host.closeContentFds();
            if (hasDrmConfiguration(mediaItem)) {
                fail(new IOException("MediaItem DRM configuration is not supported by libmpv"), PlaybackException.ERROR_CODE_DRM_UNSPECIFIED);
                return;
            }
            Map<String, String> headers = host.getConfig() != null ? host.getConfig().extraOptions() : Map.of();
            headers = applyMediaOptions(mediaItem);
            host.bindVideoOutput();
            host.safeSetPropertyBoolean("pause", host.shouldPauseNativePlayback());
            host.safeSetPropertyString("loop-file", host.isRepeatOne() ? "inf" : "no");
            host.safeSetPropertyDouble("speed", host.getPlaybackParameters().speed);
            host.safeSetPropertyDouble("volume", host.getVolume() * 100.0);
            host.applyTextOffset();
            host.applyAudioOffset();
            host.applySubtitleStyle();
            currentPlayableUri = host.playableUri(mediaItem);
            host.logSourceDiagnostics(mediaItem, currentPlayableUri, headers);
            boolean declaredIso = isLikelyIso(mediaItem, currentPlayableUri);
            boolean blurayMenuEnabled = PlayerSetting.isBlurayMenu();
            host.safeSetPropertyString("disc-menu", blurayMenuEnabled ? "yes" : "no");
            if (!declaredIso && shouldProbeOpaqueIso(mediaItem, currentPlayableUri)) {
                String probingUri = currentPlayableUri;
                Map<String, String> finalHeaders = headers;
                IsoSessionManager.probeAndCreateAsync(probingUri, headers, isoUri -> host.postToMain(() -> {
                    if (host.isReleased() || stopping || !host.getMediaReplacementCoordinator().isCurrent(generation) || !TextUtils.equals(currentPlayableUri, probingUri)) {
                        IsoSessionManager.closeUri(isoUri);
                        return;
                    }
                    currentIsoUri = MpvDiscMenuPolicy.isoUri(isoUri, blurayMenuEnabled);
                    host.requestIsoOsdSurface();
                    host.attachIsoTrackMetadataListener();
                    if (currentIsoUri != null) currentPlayableUri = currentIsoUri;
                    continueOpenCurrent(finalHeaders, generation);
                }));
                return;
            }
            if (declaredIso) {
                currentIsoUri = IsoSessionManager.create(currentPlayableUri, headers);
                currentIsoUri = MpvDiscMenuPolicy.isoUri(currentIsoUri, blurayMenuEnabled);
                host.requestIsoOsdSurface();
                host.attachIsoTrackMetadataListener();
            }
            if (currentIsoUri != null) currentPlayableUri = currentIsoUri;
            continueOpenCurrent(headers, generation);
        } catch (Throwable e) {
            fail(host.classifyLoadError(e, e.getMessage()), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    public void continueOpenCurrent(Map<String, String> headers, long generation) {
        if (!host.getMediaReplacementCoordinator().isCurrent(generation)) return;
        try {
            MediaItem mediaItem = host.getMediaItem();
            String sourceMime = mediaItem == null || mediaItem.localConfiguration == null ? null : mediaItem.localConfiguration.mimeType;
            resourceClassification = PlaybackResourceClassifier.classifyRequest(currentPlayableUri, sourceMime, sourceMime);
            if (currentIsoUri != null) {
                currentLikelyHls = false;
                currentLikelyDash = false;
                host.getHlsCoordinator().clear();
                Log.i(TAG, "load remote optical-disc ISO session");
            } else {
                currentLikelyHls = isLikelyHls(mediaItem, currentPlayableUri);
                currentLikelyDash = isLikelyDash(mediaItem, currentPlayableUri);
            }
            host.applyPreloadDiskCacheMode();
            host.applyCacheTimePolicy();
            if (currentIsoUri == null && MpvHlsCoordinator.shouldProxyHls(currentPlayableUri, currentLikelyHls)) {
                String originalUri = currentPlayableUri;
                currentPlayableUri = host.getHlsCoordinator().proxy(
                        originalUri, headers,
                        PlaybackDiskBufferStore.mediaKey(mediaItem));
                if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "hls proxy enabled original=%s proxy=%s", MpvDiagnosticsPolicy.sourceSummary(originalUri), MpvDiagnosticsPolicy.sourceSummary(currentPlayableUri));
            } else {
                host.getHlsCoordinator().clear();
            }
            MpvNetworkRecoveryPolicy.Decision recovery = MpvNetworkRecoveryPolicy.resolve(currentPlayableUri);
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "network recovery route=%s routeOwner=%s evidence=%s confidence=%s observedLeg=%s upstreamVisibility=%s controlScope=%s recoveryBoundary=%s policyKnown=%s nativeRemote=%s appOverlay=%s", recovery.route(), recovery.routeOwner(), recovery.routeEvidence(), recovery.routeConfidence(), recovery.observedLeg(), recovery.upstreamVisibility(), recovery.controlScope(), recovery.recoveryBoundary(), recovery.upstreamRecoveryPolicyKnown(), recovery.nativeRemoteRecovery(), recovery.appReconnectOverlay());
            if (!host.getMediaReplacementCoordinator().isCurrent(generation)) return;
            host.applyShaderPipeline(true);
            Log.d(TAG, "load scheme=" + safeScheme(currentPlayableUri) + " urlLen=" + (currentPlayableUri == null ? 0 : currentPlayableUri.length()) + " hls=" + currentLikelyHls + " dash=" + currentLikelyDash);
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "load scheme=%s urlLen=%d hls=%s dash=%s surface=%s attached=%s hwdec=%s vo=%s gpuContext=%s gpuApi=%s", safeScheme(currentPlayableUri), currentPlayableUri == null ? 0 : currentPlayableUri.length(), currentLikelyHls, currentLikelyDash, host.getSurfaceCoordinator().isSurfaceValid(), host.getSurfaceCoordinator().isSurfaceAttached(), host.getConfig().hwdec(), host.getConfig().vo(), host.getConfig().gpuContext(), host.getConfig().gpuApi());
            if (deferLoadUntilSurfaceReady(generation)) return;
            startPreparedMedia(generation);
        } catch (Throwable e) {
            fail(host.classifyLoadError(e, e.getMessage()), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    public boolean deferLoadUntilSurfaceReady(long generation) {
        if (host.getSurfaceCoordinator().isSurfaceReadyForLoad()) return false;
        pendingSurfaceLoadGeneration = generation;
        SpiderDebug.log("mpv", "initial surface gate waiting before load generation=%d videoAttached=%s osdRequested=%s osdAttached=%s pending=%d",
                generation, host.getSurfaceCoordinator().isSurfaceAttached(), host.getSurfaceCoordinator().isOsdSurfaceRequested(), host.getSurfaceCoordinator().isOsdSurfaceAttached(),
                host.getSurfaceCoordinator().getPendingOsdSurfaceRequestId());
        return true;
    }

    public void resumePendingSurfaceLoad() {
        long generation = pendingSurfaceLoadGeneration;
        if (generation == C.INDEX_UNSET || host.isReleased() || stopping || !host.isInitialized()
                || host.getPlayerError() != null || !host.getSurfaceCoordinator().isSurfaceReadyForLoad()) return;
        pendingSurfaceLoadGeneration = C.INDEX_UNSET;
        SpiderDebug.log("mpv", "initial surface gate ready before load generation=%d", generation);
        startPreparedMedia(generation);
    }

    public void startPreparedMedia(long generation) {
        if (!host.getMediaReplacementCoordinator().isCurrent(generation) || loadStarted) return;
        try {
            host.safeSetPropertyBoolean("pause", true);
            String initialSub = host.getInitialSubtitleTrackId();
            if (!TextUtils.isEmpty(initialSub)) {
                host.safeSetPropertyString("sid", initialSub);
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "initial subtitle preselect sid=%s phase=before-load",
                        initialSub);
            }
            loadCurrentUri();
            if (host.isInitialTrackSelectionGateActive()) {
                host.postDelayedToMain(() -> {
                    if (host.isInitialTrackSelectionGateActive()) {
                        host.setInitialTrackSelectionGateActive(false);
                        host.safeSetPropertyBoolean("pause", host.shouldPauseNativePlayback());
                    }
                }, INITIAL_TRACK_SELECTION_LOAD_TIMEOUT_MS);
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "initial track gate waiting phase=load timeoutMs=%d",
                        INITIAL_TRACK_SELECTION_LOAD_TIMEOUT_MS);
            }
            host.scheduleLoadStartRetry();
            host.invalidateState();
            host.startStateRefresh();
        } catch (Throwable e) {
            fail(host.classifyLoadError(e, e.getMessage()), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    public void loadCurrentUri() {
        if (currentIsoUri != null && host.shouldCollectDebugDetails()) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                    "ISO routing raw=%s disc-menu=%s access-references=%s",
                    MpvDiscMenuPolicy.usesRawIso(currentIsoUri),
                    host.stringProperty("disc-menu", "unavailable"),
                    host.stringProperty("access-references", "unavailable"));
        }
        String startOption = "";
        long initialSeek = host.getInitialSeekPositionMs();
        if (!MpvDiscMenuPolicy.usesRawIso(currentIsoUri)
                && initialSeek != C.TIME_UNSET && initialSeek > 0) {
            loadStartPositionMs = initialSeek;
            startOption = "start=" + String.format(Locale.US, "%.3f",
                    initialSeek / SECONDS_TO_MS);
        }
        if (currentLikelyHls) {
            host.mpvCommand(new String[]{"loadfile", currentPlayableUri, "replace", "-1",
                    appendLoadOption(HLS_LOAD_OPTIONS, startOption)});
        } else if (currentLikelyDash) {
            host.mpvCommand(new String[]{"loadfile", currentPlayableUri, "replace", "-1",
                    appendLoadOption(DASH_LOAD_OPTIONS, startOption)});
        } else if (!startOption.isEmpty()) {
            host.mpvCommand(new String[]{"loadfile", currentPlayableUri, "replace", "-1", startOption});
        } else {
            host.mpvCommand(new String[]{"loadfile", currentPlayableUri, "replace"});
        }
        if (host.shouldCollectDebugDetails() && !startOption.isEmpty()) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                    "load initial position=%d option=%s", loadStartPositionMs, startOption);
        }
    }

    private static String appendLoadOption(String options, String option) {
        return TextUtils.isEmpty(option) ? options : options + "," + option;
    }

    public void handleEvent(int eventId) {
        if (host.isReleased()) return;
        if (host.getMediaItem() == null && eventId != MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
            host.setPlaybackState(Player.STATE_IDLE);
            host.setLoading(false);
            Log.d(TAG, "Ignore stale mpv event without media item event=" + eventId);
            host.invalidateState();
            return;
        }
        switch (eventId) {
            case MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                host.getHlsCoordinator().resetHlsAdBoundary();
                host.getPropertyDispatcher().resetPlaybackMetrics();
                host.getMediaReplacementCoordinator().onStartFile();
                loadStarted = true;
                host.setPlaybackState(Player.STATE_BUFFERING);
                host.setLoading(true);
                fileLoaded = false;
                fileLoadedAtElapsedRealtimeMs = 0;
                playbackRestarted = false;
                stopping = false;
                eofReached = false;
                idleActive = false;
                host.resetFailureSignals();
                if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "event=start-file source=%s", MpvDiagnosticsPolicy.sourceSummary(currentPlayableUri));
                host.startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (loadedUnexpectedImage()) {
                    fail(new IOException("Unexpected image: path=" + host.stringProperty("path", "")), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED);
                    return;
                }
                fileLoaded = true;
                boolean felActive = host.booleanProperty("android-dovi-fel-active", false);
                host.getPropertyDispatcher().setAndroidFelActive(felActive);
                if (felActive) {
                    host.getPropertyDispatcher().updateFelRenderMetrics(
                            host.stringProperty("current-vo", "gpu-next"),
                            true,
                            host.stringProperty("current-gpu-context", ""));
                    if (host.getConfig().restoreFelAutomaticSubtitles()
                            && TextUtils.isEmpty(host.getInitialSubtitleTrackId())
                            && "no".equals(host.getConfig().extraOptions().get("sid"))) {
                        host.safeSetPropertyString("file-local-options/sid", "auto");
                    }
                }
                boolean navActive = MpvDiscMenuPolicy.usesRawIso(currentIsoUri)
                        && host.booleanProperty("disc-nav-active", false);
                host.getDiscNavController().onDiscNavActiveChanged(navActive);
                if (navActive) {
                    host.safeSetPropertyString("file-local-options/cache-pause", "no");
                    Log.i(TAG, "disc navigation start: ignore flat history position="
                            + host.getInitialSeekPositionMs() + " cache-pause="
                            + host.booleanProperty("cache-pause", true));
                    host.setInitialSeekPositionMs(C.TIME_UNSET);
                    loadStartPositionMs = C.TIME_UNSET;
                    host.setCachedPositionMs(0);
                    host.getSeekPositionState().clear();
                }
                fileLoadedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
                host.getCacheCoordinator().onFileLoaded(SystemClock.elapsedRealtime());
                host.setPlaybackState(Player.STATE_BUFFERING);
                host.setLoading(true);
                host.updateVideoSize("event=file-loaded");
                if (host.getConfig().deferStartupTrackRefresh()) {
                    host.scheduleTrackRefresh("event=file-loaded");
                } else {
                    host.refreshTracks();
                    host.refreshChapters();
                }
                if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "event=file-loaded duration=%d size=%dx%d path=%s", host.getCachedDurationMs(), host.getVideoSize().width, host.getVideoSize().height, MpvDiagnosticsPolicy.sourceSummary(currentPlayableUri));
                host.addSubtitleConfigurations();
                long initialSeek = host.getInitialSeekPositionMs();
                if (initialSeek != C.TIME_UNSET) {
                    long targetPositionMs = host.getHlsCoordinator().resolveHlsAdSeekTarget(initialSeek);
                    host.setInitialSeekPositionMs(C.TIME_UNSET);
                    host.getCacheCoordinator().resetCacheTimelineForSeek(targetPositionMs);
                    host.getSeekPositionState().begin(
                            targetPositionMs, SystemClock.elapsedRealtime());
                    boolean loadedAtTarget = loadStartPositionMs == targetPositionMs;
                    if (!loadedAtTarget) {
                        host.seekMpv(targetPositionMs);
                    } else if (host.shouldCollectDebugDetails()) {
                        PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                                "initial seek embedded target=%d observed=%d",
                                targetPositionMs, host.getCachedPositionMs());
                    }
                }
                loadStartPositionMs = C.TIME_UNSET;
                host.getHlsCoordinator().updateHlsAdBoundary(host.getHlsCoordinator().resolveHlsAdSeekTarget(host.getCachedPositionMs()));
                host.safeSetPropertyBoolean("pause", host.shouldPauseNativePlayback());
                host.updatePreloadCacheOverlay();
                host.startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                host.getHlsCoordinator().onPlaybackRestarted(host.doubleSecondsToMs(
                        host.doubleProperty("playback-time", host.getCachedPositionMs() / SECONDS_TO_MS), host.getCachedPositionMs()));
                playbackRestarted = true;
                if (host.getConfig().deferStartupTrackRefresh()) {
                    host.scheduleTrackRefresh("event=playback-restart");
                }
                if (host.isInitialTrackSelectionGateActive()) {
                    host.postDelayedToMain(() -> {
                        if (host.isInitialTrackSelectionGateActive()) {
                            host.setInitialTrackSelectionGateActive(false);
                            host.safeSetPropertyBoolean("pause", host.shouldPauseNativePlayback());
                        }
                    }, INITIAL_TRACK_SELECTION_RESTORE_TIMEOUT_MS);
                }
                if (currentLikelyHls) host.getHlsCoordinator().requestHlsPreload(host.getCachedPositionMs());
                host.updateVideoSize("event=playback-restart");
                if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "event=playback-restart position=%d duration=%d size=%dx%d", host.getCachedPositionMs(), host.getCachedDurationMs(), host.getVideoSize().width, host.getVideoSize().height);
                if (host.getPlaybackState() != Player.STATE_ENDED) {
                    host.setPlaybackState(Player.STATE_READY);
                    host.setLoading(false);
                    host.startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                videoReconfigCount++;
                host.updateVideoSize("event=video-reconfig#" + videoReconfigCount);
            }
            case MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                handleEndFile(MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_UNKNOWN, MPVLib.MpvError.MPV_ERROR_SUCCESS, null);
                return;
            }
            case MPVLib.MpvEvent.MPV_EVENT_IDLE -> {
                if (fileLoaded && !stopping) {
                    markPlaybackEnded("event:idle");
                } else if (host.isLoading() && !stopping) {
                    host.setPlaybackState(Player.STATE_BUFFERING);
                    host.startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                if (fileLoaded && !stopping) {
                    markPlaybackEnded("event:shutdown");
                } else {
                    host.setPlaybackState(Player.STATE_IDLE);
                    host.setLoading(false);
                    host.stopStateRefresh();
                }
            }
            default -> {
            }
        }
        host.invalidateState();
    }

    public void handleEndFile(int reason, int error, @Nullable String errorText) {
        if (host.isReleased()) return;
        if (host.getMediaReplacementCoordinator().shouldIgnoreEndFile(stopping, loadStarted)) {
            SpiderDebug.log("mpv", "ignore replaced-media end-file generation=%d reason=%s(%d) error=%s(%d)", host.getMediaReplacementCoordinator().generation(), host.endFileReasonName(reason), reason, host.mpvErrorName(error), error);
            return;
        }
        lastEndFileReason = reason;
        lastEndFileError = error;
        lastEndFileErrorText = errorText;
        if (host.shouldCollectDebugDetails()) PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "event=end-file reason=%s(%d) error=%s(%d) text=%s loaded=%s restart=%s eof=%s stopping=%s source=%s",
                host.endFileReasonName(reason), reason, host.mpvErrorName(error), error, TextUtils.isEmpty(errorText) ? "-" : MpvDiagnosticsPolicy.redactSensitive(errorText),
                fileLoaded, playbackRestarted, eofReached, stopping, MpvDiagnosticsPolicy.sourceSummary(currentPlayableUri));
        host.stopStateRefresh();
        host.setLoading(false);
        boolean resumeReplacement = false;
        if (stopping) {
            stopping = false;
            resumeReplacement = host.getMediaReplacementCoordinator().resumeAfterStopAcknowledged();
        } else if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR) {
            fail(host.nativeEndFileError(reason, error, errorText), host.nativeEndFilePlaybackExceptionCode(error));
            return;
        } else if (isSuccessfulNaturalEof(reason, error)) {
            host.setPlaybackState(Player.STATE_ENDED);
        } else if (host.isFailedLoadedMedia()) {
            fail(new IOException(host.failedLoadedMediaMessage()), PlaybackException.ERROR_CODE_DECODING_FAILED);
            return;
        } else if (fileLoaded || eofReached) {
            markPlaybackEnded("event:end-file");
        } else {
            host.setLoading(true);
            host.setPlaybackState(Player.STATE_BUFFERING);
            host.startStateRefresh();
        }
        host.invalidateState();
        if (resumeReplacement) host.resumeMediaReplacement("stop-ack");
    }

    public void markPlaybackEnded(String reason) {
        if (host.getPlaybackState() == Player.STATE_ENDED) return;
        eofReached = true;
        host.setLoading(false);
        host.setPlaybackState(Player.STATE_ENDED);
        host.stopStateRefresh();
        SpiderDebug.log("mpv", "playback ended reason=%s position=%d duration=%d", reason, host.getCachedPositionMs(), host.getCachedDurationMs());
        host.stopMainThreadWatchdog();
    }

    public boolean isSuccessfulNaturalEof(int reason, int error) {
        if (reason != MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF || error != MPVLib.MpvError.MPV_ERROR_SUCCESS || !fileLoaded) return false;
        if (eofReached) return true;
        long duration = host.getCachedDurationMs();
        long position = host.getCachedPositionMs();
        if (duration == C.TIME_UNSET || duration <= 0) return playbackRestarted && position > 0;
        long tolerance = Math.max(3000L, Math.min(15000L, duration / 100L));
        return position >= Math.max(0, duration - tolerance);
    }

    public boolean loadedUnexpectedImage() {
        String path = host.stringProperty("path", "");
        if (!host.isImageUri(path)) return false;
        if (!TextUtils.isEmpty(currentPlayableUri) && host.sameUri(path, currentPlayableUri)) return false;
        Log.w(TAG, "unexpected image pathScheme=" + safeScheme(path)
                + " requestedScheme=" + safeScheme(currentPlayableUri)
                + " requestedLen=" + (currentPlayableUri == null ? 0 : currentPlayableUri.length()));
        return true;
    }

    public void stopInternal(boolean resetState) {
        host.getHlsCoordinator().resetHlsAdBoundary();
        pendingSurfaceLoadGeneration = C.INDEX_UNSET;
        host.setInitialTrackSelectionGateActive(false);
        host.restorePreloadCacheOverlay();
        host.clearCoalescedPropertyEvents();
        host.cancelScheduledTrackRefresh();
        host.getMediaReplacementCoordinator().cancelDeferredPrepare();
        stopMpv(true);
        host.clearSurfaceFrameRate();
        host.closeContentFds();
        host.setLoading(false);
        fileLoaded = false;
        fileLoadedAtElapsedRealtimeMs = 0;
        loadStarted = false;
        playbackRestarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        host.setCachedPositionMs(0);
        host.setCachedDurationMs(C.TIME_UNSET);
        host.getPropertyDispatcher().resetVideoMetadataCache();
        host.resetCacheState();
        host.getTrackCoordinator().resetTrackDiagnostics();
        host.setVideoSize(VideoSize.UNKNOWN);
        host.setPlayerError(null);
        host.setInitialSeekPositionMs(C.TIME_UNSET);
        loadStartPositionMs = C.TIME_UNSET;
        host.getSeekPositionState().clear();
        idleActive = false;
        currentPlayableUri = null;
        resourceClassification = null;
        host.closeIsoSession();
        currentLikelyHls = false;
        currentLikelyDash = false;
        host.resetFailureSignals();
        host.getHlsCoordinator().clear();
        if (resetState) host.setPlaybackState(Player.STATE_IDLE);
        host.stopStateRefresh();
        host.invalidateState();
    }

    public void stopMpv(boolean markStopping) {
        if (!host.isInitialized()) return;
        boolean previousStopping = stopping;
        if (markStopping) stopping = true;
        if (!host.enqueueMpvCommand("stop")) {
            stopping = previousStopping;
        }
    }

    public void fail(Throwable e, int errorCode) {
        host.setPlayerError(new PlaybackException(e.getMessage(), e, errorCode));
        host.setPlaybackState(Player.STATE_IDLE);
        host.setLoading(false);
        fileLoaded = false;
        host.closeContentFds();
        host.stopStateRefresh();
        if (MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.ERROR_DETAILED, SpiderDebug.isEnabled())) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "fail code=%d message=%s diagnostics=%s", errorCode, MpvDiagnosticsPolicy.redactSensitive(e.getMessage()), host.diagnosticSummary());
        }
        host.invalidateState();
        host.stopMainThreadWatchdog();
    }

    public void maybeRetryDtsHdAsCore(String line) {
        if (!host.isInitialized() || host.getMediaItem() == null || !loadStarted) return;
        if (!MpvDtsHdFallbackPolicy.shouldInspectAudioState(
                activeAudioSpdif, line, dtsHdCoreFallbackAttempted)) return;
        String audioFormat = TextUtils.isEmpty(host.getPropertyDispatcher().getCachedAudioFormat())
                ? host.stringProperty("audio-params/format", "") : host.getPropertyDispatcher().getCachedAudioFormat();
        String codecProfile = TextUtils.isEmpty(host.getPropertyDispatcher().getCachedAudioCodecProfile())
                ? host.stringProperty("current-tracks/audio/codec-profile", "") : host.getPropertyDispatcher().getCachedAudioCodecProfile();
        MpvDtsHdFallbackPolicy.Decision decision =
                MpvDtsHdFallbackPolicy.evaluate(activeAudioSpdif,
                        audioFormat, codecProfile, line,
                        dtsHdCoreFallbackAttempted);
        if (!decision.retry()) return;
        dtsHdCoreFallbackAttempted = true;
        boolean applied = host.enqueueMpvCommand("set", "audio-spdif", decision.codecs());
        if (applied) {
            activeAudioSpdif = decision.codecs();
            dtsHdFallbackSourceTrack = host.getTrackCoordinator().getSelectedAudioTrackDiagnostics();
        }
        SpiderDebug.log("mpv-audio",
                "dts-hd fallback attempted=true applied=%s reason=%s format=%s profile=%s codecs=%s",
                applied, decision.reason(), audioFormat, codecProfile,
                applied ? activeAudioSpdif : "unchanged");
    }

    public void restoreConfiguredAudioSpdifForNewMedia() {
        if (!TextUtils.equals(activeAudioSpdif, host.getConfig().audioSpdif())) {
            boolean restored = host.enqueueMpvCommand("set", "audio-spdif", host.getConfig().audioSpdif());
            if (restored) activeAudioSpdif = host.getConfig().audioSpdif();
            SpiderDebug.log("mpv-audio",
                    "dts-hd fallback reset restored=%s codecs=%s",
                    restored, restored ? activeAudioSpdif : "unchanged");
        }
        dtsHdCoreFallbackAttempted = false;
        dtsHdFallbackSourceTrack = AudioPlaybackDiagnostics.Track.empty();
    }

    public void applyPreInitOptions() {
        MpvPlayerConfig config = host.getConfig();
        host.getCacheCoordinator().applyPreInitCacheOptions();
        host.mpvSetOptionString("config", "yes");
        host.mpvSetOptionString("config-dir", config.configDir().getAbsolutePath());
        host.mpvSetOptionString("gpu-shader-cache-dir", config.cacheDir().getAbsolutePath());
        host.mpvSetOptionString("icc-cache-dir", config.cacheDir().getAbsolutePath());
        host.mpvSetOptionString("vo", config.vo());
        host.mpvSetOptionString("gpu-context", config.gpuContext());
        if (!TextUtils.isEmpty(config.gpuApi())) host.mpvSetOptionString("gpu-api", config.gpuApi());
        if (config.openglEs()) host.mpvSetOptionString("opengl-es", "yes");
        host.mpvSetOptionString("hwdec", config.hwdec());
        host.mpvSetOptionString("hwdec-codecs", MpvOptionPriorityPolicy.HARDWARE_CODECS);
        host.mpvSetOptionString("ao", config.ao());
        host.mpvSetOptionString("ad", MpvAudioDecoderPolicy.hardwareFirstDecoderList());
        if (!TextUtils.isEmpty(config.audioSpdif())) host.mpvSetOptionString("audio-spdif", config.audioSpdif());
        host.mpvSetOptionString("audio-set-media-role", "yes");
        host.mpvSetOptionString("tls-verify", config.tlsVerify() ? "yes" : "no");
        if (config.caFile().isFile()) host.mpvSetOptionString("tls-ca-file", config.caFile().getAbsolutePath());
        host.mpvSetOptionString("input-default-bindings", "yes");
        host.mpvSetOptionString("disc-menu", PlayerSetting.isBlurayMenu() ? "yes" : "no");
        host.mpvSetOptionString("cache", config.cache() ? "yes" : "no");
        host.mpvSetOptionString("cache-on-disk", "no");
        if (host.getCacheCoordinator().getPreloadCacheCapacityBytes() > 0 && host.getCacheCoordinator().getPreloadCacheDir() != null) {
            host.mpvSetOptionString("demuxer-cache-dir", host.getCacheCoordinator().getPreloadCacheDir().getAbsolutePath());
        }
        host.mpvSetOptionString("cache-secs", String.valueOf(config.cacheSeconds()));
        host.mpvSetOptionString("cache-pause", MpvStartupBufferPolicy.CACHE_PAUSE);
        host.mpvSetOptionString("cache-pause-initial", MpvStartupBufferPolicy.CACHE_PAUSE_INITIAL);
        host.mpvSetOptionString("cache-pause-wait", String.format(Locale.US, "%.3f", config.rebufferMs() / SECONDS_TO_MS));
        host.mpvSetOptionString("demuxer-thread", "yes");
        host.mpvSetOptionString("demuxer-seekable-cache", "auto");
        host.mpvSetOptionString("demuxer-max-bytes", String.valueOf(config.demuxerMaxBytes()));
        host.mpvSetOptionString("demuxer-max-back-bytes", String.valueOf(config.demuxerMaxBackBytes()));
        host.mpvSetOptionString("demuxer-readahead-secs", String.valueOf(config.demuxerReadaheadSeconds()));
        host.mpvSetOptionString("demuxer-hysteresis-secs", String.valueOf(config.demuxerHysteresisSeconds()));
        host.mpvSetOptionString("sub-ass", "yes");
        host.mpvSetOptionString("sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE);
        host.mpvSetOptionString("embeddedfonts", "yes");
        host.mpvSetOptionString("sub-fix-timing", "yes");
        host.mpvSetOptionString("sub-use-margins", "yes");
        host.mpvSetOptionString("sub-font-provider", "fontconfig");
        String discLogLevel = BuildConfig.DEBUG ? "debug" : "info";
        host.mpvSetOptionString("msg-level", config.logLevel() + ",iso=" + discLogLevel
                + ",bd=" + discLogLevel + ",bdmv/bluray=" + discLogLevel);
        for (Map.Entry<String, String> entry : config.extraOptions().entrySet()) {
            host.mpvSetOptionString(entry.getKey(), entry.getValue());
        }
    }

    public String applyPostInitOptions() {
        host.safeSetPropertyString("save-position-on-quit", "no");
        host.safeSetPropertyString("force-window", "no");
        host.safeSetPropertyString("idle", "yes");
        int overlayCount = host.getCacheCoordinator().applyPerformanceOptionOverlay();
        String effectiveVo = MpvOptionPriorityPolicy.resolveVideoOutput(
                host.getConfig().performanceOptionsPriority(),
                host.getConfig().vo(),
                host.stringProperty("vo", ""));
        host.getCacheCoordinator().applyPostInitCacheOptions(overlayCount);
        return effectiveVo;
    }

    public void observeProperties() {
        for (String property : new String[]{"mpv-version", "ffmpeg-version", "options/msg-level", "hwdec", "hwdec-interop",
                "video-codec", "audio-codec", "current-tracks/video/decoder", "current-tracks/video/codec-profile",
                "audio-params/channels", "audio-out-params/channels", "video-dec-params/pixelformat", "video-params/pixelformat",
                "video-out-params/pixelformat", "audio-spdif", "options/af", "file-format"}) host.observe(property, MPVLib.MpvFormat.MPV_FORMAT_STRING);
        for (String property : new String[]{"volume", "speed", "audio-delay", "video-pts", "audio-pts"}) host.observe(property, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("mute", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("seeking", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("seekable", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("current-edition", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("time-pos/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("cache-speed", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-state/cache-end", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-state/reader-pts", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-cache-state/fw-bytes", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/total-bytes", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/file-cache-bytes", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/raw-input-rate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/bof-cached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("demuxer-cache-state/eof-cached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("cache-secs", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-readahead-secs", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("demuxer-hysteresis-secs", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("hls-bitrate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("demuxer-cache-state/idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("demuxer-cache-state/underrun", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("idle-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-params/dw", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-params/dh", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-out-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-out-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-out-params/dw", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("video-out-params/dh", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("container-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("video-params/primaries", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("video-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("video-params/colorlevels", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("video-params/colormatrix", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-vo", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("android-dovi-fel-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("android-fel-bind-probe", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("video-frame-submitted", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("current-gpu-context", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("gpu-api", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-ao", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("audio-device", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("audio-params/format", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("audio-params/channel-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("audio-params/samplerate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("audio-out-params/format", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("audio-out-params/channel-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("audio-out-params/samplerate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("current-tracks/audio/decoder", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-tracks/audio/codec-profile", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("avsync", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("display-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("estimated-display-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        host.observe("decoder-frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("mistimed-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("vo-delayed-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("display-sync-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE);
        host.observe("video-bitrate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("audio-bitrate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("vid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("secondary-sid", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("sub-visibility", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe("current-tracks/video/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-tracks/video/demux-w", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("current-tracks/video/demux-h", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("current-tracks/audio/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-tracks/sub/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("current-tracks/sub2/id", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        host.observe("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        host.observe("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_NODE);
        host.observe("disc-menu-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        host.observe(MpvConfigStore.CUSTOM_BUTTON_STATE_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_STRING);
    }

    public Map<String, String> applyMediaOptions(MediaItem item) {
        MpvRequestHeaderPolicy.Resolved resolved = MpvRequestHeaderPolicy.resolve(extractHeaders(item), host.getConfig().userAgent());
        Map<String, String> headers = resolved.headers();
        String userAgent = resolved.userAgent();
        String referer = resolved.referer();
        String origin = resolved.origin();
        String headerFields = buildHeaderFields(headers);
        host.safeSetPropertyString("user-agent", userAgent == null ? "" : userAgent);
        host.safeSetPropertyString("referrer", referer == null ? "" : referer);
        host.safeSetPropertyString("http-header-fields", headerFields);
        if (item.mediaMetadata.title != null) host.safeSetPropertyString("force-media-title", item.mediaMetadata.title.toString());
        SpiderDebug.log("mpv", "media options sourceHeadersOnly=true uaEmpty=%s refererEmpty=%s originEmpty=%s headerNames=%s headerFields=%s",
                TextUtils.isEmpty(userAgent), TextUtils.isEmpty(referer), TextUtils.isEmpty(origin), headerNames(headers), !TextUtils.isEmpty(headerFields));
        return headers;
    }

    private static Map<String, String> extractHeaders(MediaItem item) {
        if (item.requestMetadata.extras == null) return Map.of();
        android.os.Bundle extras = item.requestMetadata.extras;
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        for (String key : extras.keySet()) {
            String value = extras.getString(key);
            if (value != null) headers.put(key, value);
        }
        return headers;
    }

    private static String buildHeaderFields(Map<String, String> headers) {
        if (headers.isEmpty()) return "";
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (equalsHeader(key, HttpHeaders.USER_AGENT) || equalsHeader(key, HttpHeaders.REFERER) || equalsHeader(key, HttpHeaders.RANGE)) continue;
            fields.add(key + ": " + escapeListValue(entry.getValue()));
        }
        return String.join(",", fields);
    }

    private static List<String> headerNames(Map<String, String> headers) {
        if (headers.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String key : headers.keySet()) names.add(key);
        return names;
    }

    private static String escapeListValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }

    private static boolean equalsHeader(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean hasDrmConfiguration(MediaItem item) {
        return item != null && item.localConfiguration != null && item.localConfiguration.drmConfiguration != null;
    }

    private static boolean isLikelyIso(MediaItem item, String uri) {
        if (uri != null && uri.startsWith("edl://")) return false;
        String mime = item.localConfiguration == null ? null : item.localConfiguration.mimeType;
        if (mime != null) {
            String lowerMime = mime.toLowerCase(Locale.US);
            if (lowerMime.contains("iso") || lowerMime.contains("bluray") || lowerMime.contains("dvd")) return true;
        }
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        return lower.endsWith(".iso") || lower.contains(".iso?") || lower.contains("/iso/");
    }

    private static boolean shouldProbeOpaqueIso(MediaItem item, String uri) {
        if (uri == null || uri.startsWith("edl://") || uri.startsWith("content://") || uri.startsWith("file://")) return false;
        if (isLikelyIso(item, uri)) return false;
        String mime = item.localConfiguration == null ? null : item.localConfiguration.mimeType;
        if (mime != null && (mime.contains("video/") || mime.contains("audio/"))) return false;
        return true;
    }

    private static boolean isLikelyHls(MediaItem item, String uri) {
        if (uri != null && uri.startsWith("edl://")) return false;
        if (item.localConfiguration != null) {
            String mimeType = item.localConfiguration.mimeType;
            if (MimeTypes.APPLICATION_M3U8.equals(mimeType)
                    || "application/vnd.apple.mpegurl".equalsIgnoreCase(mimeType)
                    || "application/x-mpegurl".equalsIgnoreCase(mimeType)
                    || "hls".equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        return lower.contains("m3u8");
    }

    private static boolean isLikelyDash(MediaItem item, String uri) {
        if (uri != null && uri.startsWith("edl://")) return false;
        if (item.localConfiguration != null) {
            String mimeType = item.localConfiguration.mimeType;
            if (MimeTypes.APPLICATION_MPD.equals(mimeType)
                    || "application/dash+xml".equalsIgnoreCase(mimeType)
                    || "dash".equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        return lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    private static String safeScheme(String value) {
        try {
            return String.valueOf(Uri.parse(value).getScheme());
        } catch (Throwable ignored) {
            return "-";
        }
    }
}
