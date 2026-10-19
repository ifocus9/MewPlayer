package androidx.media3.mpvplayer;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.PreloadPausePolicy;
import com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.player.iso.IsoSessionManager;
import com.fongmi.android.tv.player.lut.MpvLutShader;
import com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicy;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.player.mpv.MpvNetworkRecoveryPolicy;
import com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy;
import com.fongmi.android.tv.player.mpv.PlaybackRecoveryMonitor;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import is.xyz.mpv.MPVLib;

@UnstableApi
public final class MpvPlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final String TAG = "TV-mpv";
    private static final String SIZE_TAG = "MPV_SIZE";
    private static final long STATE_REFRESH_INTERVAL_MS = 1000;
    private static final long PROPERTY_EVENT_COALESCE_MS = 50;
    private static final long MAIN_THREAD_WATCHDOG_INTERVAL_MS = 500;
    // A 60 Hz display has only 16.67 ms per frame. Record shorter calls too;
    // a 100 ms threshold hides the single-frame misses that accumulate into bursts.
    private static final long SLOW_MPV_NATIVE_CALL_THRESHOLD_MS = 16;
    private static final long END_FILE_VALIDATION_DELAY_MS = 800;
    private static final long LOAD_START_RETRY_DELAY_MS = 1000;
    private static final long POST_RESTART_TRACK_REFRESH_DELAY_MS = 120;
    private static final long INITIAL_TRACK_SELECTION_LOAD_TIMEOUT_MS = 15000;
    private static final long INITIAL_TRACK_SELECTION_RESTORE_TIMEOUT_MS = 1500;
    private static final long MEDIA_REPLACEMENT_STOP_TIMEOUT_MS = 1200;
    private static final float FRAME_RATE_REQUEST_EPSILON = 0.001f;
    private static final int MAX_LOAD_START_RETRIES = 2;
    private static final double SECONDS_TO_MS = 1000.0;
    private static final double DEFAULT_SUBTITLE_TEXT_SIZE_FRACTION = 0.0533;
    private static final String HLS_LOAD_OPTIONS = "demuxer=lavf,demuxer-lavf-format=hls,demuxer-lavf-probesize=10485760,demuxer-lavf-analyzeduration=5";
    private static final String DASH_LOAD_OPTIONS = "demuxer=lavf,demuxer-lavf-format=dash,demuxer-lavf-probesize=10485760,demuxer-lavf-analyzeduration=5";
    private static final Object NATIVE_CONTEXT_LOCK = MpvNativeBridge.NATIVE_CONTEXT_LOCK;
    private static final AtomicLong NATIVE_REQUEST_IDS = MpvNativeBridge.NATIVE_REQUEST_IDS;
    @Nullable
    private static MpvPlayer nativeContextOwner;

    public static final String ERROR_HLS_PLAYBACK_FAILED = "MPV_HLS_PLAYBACK_FAILED";
    public static final String ERROR_LOAD_FAILED = "MPV_LOAD_FAILED";
    public static final String ERROR_NETWORK_FAILED = "MPV_NETWORK_FAILED";
    public static final String ERROR_DRM_UNSUPPORTED = "MPV_DRM_UNSUPPORTED";
    public static final String ERROR_UNEXPECTED_IMAGE = "MPV_UNEXPECTED_IMAGE";
    public static final String ERROR_NO_AV_DATA = "MPV_NO_AV_DATA";
    public static final String ERROR_INVALID_MEDIA_DATA = "MPV_INVALID_MEDIA_DATA";
    public static final String ERROR_DECODE_FAILED = "MPV_DECODE_FAILED";
    public static final String ERROR_VIDEO_OUTPUT_FAILED = "MPV_VIDEO_OUTPUT_FAILED";

    private static final Commands COMMANDS = new Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_RELEASE)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_TIMELINE)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SET_MEDIA_ITEM)
            .add(COMMAND_CHANGE_MEDIA_ITEMS)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_GET_VOLUME)
            .add(COMMAND_SET_VOLUME)
            .add(COMMAND_SET_SPEED_AND_PITCH)
            .add(COMMAND_GET_TEXT_OFFSET)
            .add(COMMAND_SET_TEXT_OFFSET)
            .add(COMMAND_GET_AUDIO_OFFSET)
            .add(COMMAND_SET_AUDIO_OFFSET)
            .add(COMMAND_SET_VIDEO_SURFACE)
            .add(COMMAND_GET_TRACKS)
            .build();

    private final Context context;
    private final MpvPlayerConfig config;
    private final Handler mainHandler;
    private final MpvPropertyCoalescer propertyCoalescer;
    private final Runnable stateRefreshRunnable;
    private final MpvDiscNavController discNavController;
    private final MpvMainThreadWatchdog mainThreadWatchdog;
    private final MpvSurfaceCoordinator surfaceCoordinator;
    private final Runnable endFileValidationRunnable;
    private final Runnable loadStartRetryRunnable;
    private final Runnable mediaReplacementStopTimeoutRunnable;
    private final Runnable initialTrackSelectionGateTimeoutRunnable;
    private final Runnable isoTrackMetadataReadyListener;
    private final MpvHlsCoordinator hlsCoordinator;
    private final MpvCacheCoordinator cacheCoordinator;
    private final MpvPropertyDispatcher propertyDispatcher;
    private final MpvLifecycleCoordinator lifecycleCoordinator;
    private final MpvDiagnosticCoordinator diagnosticCoordinator;
    private final MpvCaptionCoordinator captionCoordinator;
    private final MpvNativeBridge nativeBridge;
    private final MpvVideoSizeCoordinator videoSizeCoordinator;
    private final MpvPropertySnapshot propertySnapshot = new MpvPropertySnapshot();
    private final MpvDiagnosticCollector diagnostics = new MpvDiagnosticCollector(propertySnapshot);
    private final java.util.concurrent.atomic.AtomicLong propertyGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private final MpvSeekPositionState seekPositionState;
    private final MpvMediaReplacementCoordinator mediaReplacementCoordinator;
    private final MpvUriResolver uriResolver = new MpvUriResolver();
    private final MpvSurfaceTeardownPolicy surfaceTeardownPolicy;
    private MediaItem mediaItem;
    private volatile String playbackTraceId = PlaybackTrace.NONE;
    private volatile PlaybackResourceClassifier.Classification resourceClassification;
    private boolean isoTrackListDumped;
    private long isoMetadataListenerSessionId = -1;
    private MpvLutShader lutShader;
    private String appliedLutShaderPath;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private final MpvTrackCoordinator trackCoordinator;
    private VideoSize videoSize;
    private int playbackState;
    private long initialSeekPositionMs;
    private long cachedPositionMs;
    private long cachedDurationMs;
    private long cachedSelectedHlsBitrate;
    private boolean playWhenReady;
    private boolean loading;
    private boolean repeatOne;
    private boolean initialized;
    private volatile boolean released;
    private boolean initialTrackSelectionGateRequested;
    private boolean initialTrackSelectionGateActive;
    private String initialSubtitleTrackId;
    private BiConsumer<Integer, Integer> videoSizeProbeListener;
    private final List<Runnable> customButtonStateListeners = new ArrayList<>();
    private String customButtonStates = "";
    private String effectiveVo;
    private boolean firstVideoFrameReported;
    private boolean newlyRenderedFirstFrame;
    private float lastRequestedFrameRate = Float.NaN;
    private int lastFrameRateCompatibility = -1;
    private int lastFrameRateStrategy = -1;
    private float volume;

    public MpvPlayer(Context context, MpvPlayerConfig config) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.config = config;
        mainHandler = new Handler(Looper.getMainLooper());
        propertyCoalescer = new MpvPropertyCoalescer(new MpvPropertyCoalescer.Host() {
            @Override public long getPropertyGeneration() { return propertyGeneration.get(); }
            @Override public MpvPropertySnapshot getPropertySnapshot() { return propertySnapshot; }
            @Override public MpvDiagnosticCollector getDiagnostics() { return diagnostics; }
            @Override public boolean isReleased() { return released; }
            @Override public void postToMain(Runnable r) { MpvPlayer.this.postToMain(r); }
            @Override public void postDelayedToMain(Runnable r, long delayMs) { mainHandler.postDelayed(r, delayMs); }
            @Override public void removeMainCallbacks(Runnable r) { mainHandler.removeCallbacks(r); }
            @Override public void onPropertyDispatched(String property, @Nullable Object value) {
                if (!released) propertyDispatcher.dispatch(property, value);
            }
        });
        cacheCoordinator = new MpvCacheCoordinator(new MpvCacheCoordinator.Host() {
            @Override public MpvPlayerConfig getConfig() { return config; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isReleased() { return released; }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public boolean isPlaying() { return isPlayingInternal(); }
            @Override public boolean isPlayWhenReady() { return playWhenReady; }
            @Override public boolean isLoading() { return loading; }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public PlaybackResourceClassifier.Classification getResourceClassification() { return resourceClassification; }
            @Override public boolean isLikelyHls() { return lifecycleCoordinator.isCurrentLikelyHls(); }
            @Override public boolean isLikelyDash() { return lifecycleCoordinator.isCurrentLikelyDash(); }
            @Override public boolean shouldCollectDebugDetails() { return MpvPlayer.this.shouldCollectDebugDetails(); }
            @Override public boolean setRuntimeStringChecked(String name, String value) { return MpvPlayer.this.setRuntimeStringChecked(name, value); }
            @Override public String stringProperty(String name, String fallback) { return MpvPlayer.this.stringProperty(name, fallback); }
            @Override public long longProperty(String name, long fallback) { return MpvPlayer.this.longProperty(name, fallback); }
            @Override public Long nullableLongProperty(String name) { return MpvPlayer.this.nullableLongProperty(name); }
            @Override public long doublePropertyMs(String name, long fallbackMs) { return MpvPlayer.this.doublePropertyMs(name, fallbackMs); }
            @Override public boolean booleanProperty(String name, boolean fallback) { return MpvPlayer.this.booleanProperty(name, fallback); }
            @Override public void requestHlsPreload(long positionMs) { MpvPlayer.this.requestHlsPreload(positionMs); }
        });
        seekPositionState = new MpvSeekPositionState();
        mediaReplacementCoordinator = new MpvMediaReplacementCoordinator();
        surfaceTeardownPolicy = new MpvSurfaceTeardownPolicy();
        stateRefreshRunnable = this::refreshPlaybackState;
        discNavController = new MpvDiscNavController(new MpvDiscNavController.Host() {
            @Override public int executeMpvCommand(String[] command) { return mpvCommand(command); }
            @Override public Handler getMainHandler() { return mainHandler; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isReleased() { return released; }
            @Override public boolean isStopping() { return lifecycleCoordinator.isStopping(); }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public boolean isPlaybackRestarted() { return lifecycleCoordinator.isPlaybackRestarted(); }
            @Override public boolean isPlayWhenReady() { return playWhenReady; }
            @Override public int getPlaybackState() { return playbackState; }
            @Override public boolean hasPlayerError() { return playerError != null; }
            @Override public boolean isInitialTrackSelectionGateActive() { return initialTrackSelectionGateActive; }
            @Override public boolean isSeekPending() { return seekPositionState.hasTarget(); }
            @Override public String getCurrentIsoUri() { return lifecycleCoordinator.getCurrentIsoUri(); }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public long getCachedCacheDurationMs() { return cacheCoordinator.getCachedCacheDurationMs(); }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public int getVideoWidth() { return videoSize.width; }
            @Override public int getVideoHeight() { return videoSize.height; }
            @Override public boolean isSurfaceAttached() { return surfaceCoordinator.isSurfaceAttached(); }
            @Override public boolean isSurfaceValid() { return surfaceCoordinator.isSurfaceValid(); }
            @Override public void requestIsoOsdSurface() { surfaceCoordinator.requestIsoOsdSurface(); }
        });
        surfaceCoordinator = new MpvSurfaceCoordinator(new MpvSurfaceCoordinator.Host() {
            @Override public MpvPlayerConfig getConfig() { return config; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isReleased() { return released; }
            @Override public String getCurrentIsoUri() { return lifecycleCoordinator.getCurrentIsoUri(); }
            @Override public boolean isAndroidFelActive() { return propertyDispatcher.isAndroidFelActive(); }
            @Override public boolean isObservedCurrentVo() { return propertyDispatcher.isObservedCurrentVo(); }
            @Override public String getCachedCurrentVo() { return propertyDispatcher.getCachedCurrentVo(); }
            @Override public String getEffectiveVo() { return effectiveVo; }
            @Override public boolean shouldBindSurface() { return surfaceTeardownPolicy.shouldBindSurface(); }
            @Override public boolean shouldDetachSurface() { return surfaceTeardownPolicy.shouldDetachSurface(); }
            @Override public void attachNativeSurface(Surface target) { mpvAttachSurface(target); }
            @Override public void detachNativeSurface() { mpvDetachSurface(); }
            @Override public void detachNativeOsdSurface() { mpvDetachOsdSurface(); }
            @Override public int enqueueNativeOsdSurface(long requestId, Surface target) { return mpvEnqueueOsdSurface(requestId, target); }
            @Override public boolean enqueueMpvCommand(String... command) { return MpvPlayer.this.enqueueMpvCommand(command); }
            @Override public void resumePendingSurfaceLoad() { MpvPlayer.this.resumePendingSurfaceLoad(); }
            @Override public void onVideoOutputFailed(Throwable error) {
                fail(diagnosticCoordinator.mpvError(ERROR_VIDEO_OUTPUT_FAILED, error.getMessage(), error), PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
            }
            @Override public void rememberLog(String message) { diagnosticCoordinator.rememberLog(message); }
            @Override public void markFailureSignal(String message) { diagnosticCoordinator.markFailureSignal(message); }
            @Override public boolean booleanProperty(String name, boolean defaultValue) { return MpvPlayer.this.booleanProperty(name, defaultValue); }
            @Override public String propertyStringOrInt(String name) { return MpvPlayer.this.propertyStringOrInt(name); }
            @Override public String currentTrackId(int trackType) { return MpvPlayer.this.currentTrackId(trackType); }
            @Override public boolean isDisabledTrackChoice(String choice) { return MpvPlayer.this.isDisabledTrackChoice(choice); }
        });
        mainThreadWatchdog = new MpvMainThreadWatchdog(new MpvMainThreadWatchdog.Host() {
            @Override public Context getContext() { return MpvPlayer.this.context; }
            @Override public Handler getMainHandler() { return mainHandler; }
            @Override public boolean isReleased() { return released; }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public MpvDiagnosticCollector getDiagnostics() { return diagnostics; }
            @Override public void onSyncDiagnosticLogLevelRequested() { syncDiagnosticLogLevel(); }
        });
        trackCoordinator = new MpvTrackCoordinator(new MpvTrackCoordinator.Host() {
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isReleased() { return released; }
            @Override public MpvPlayerConfig getConfig() { return config; }
            @Override public Handler getMainHandler() { return mainHandler; }
            @Override public String getCurrentIsoUri() { return lifecycleCoordinator.getCurrentIsoUri(); }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public boolean isPlaybackRestarted() { return lifecycleCoordinator.isPlaybackRestarted(); }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public long getFileLoadedAtElapsedRealtimeMs() { return lifecycleCoordinator.getFileLoadedAtElapsedRealtimeMs(); }
            @Override public VideoSize getVideoSize() { return videoSize; }
            @Override public float getVideoFrameRate() { return MpvPlayer.this.videoFrameRate(); }
            @Override public String getCachedAudioCodecProfile() { return propertyDispatcher.getCachedAudioCodecProfile(); }
            @Override public String getActiveAudioSpdif() { return lifecycleCoordinator.getActiveAudioSpdif(); }
            @Override public String getActiveVideoOutputVo() { return surfaceCoordinator.activeVideoOutputVo(); }
            @Override public boolean shouldCollectDebugDetails() { return MpvPlayer.this.shouldCollectDebugDetails(); }
            @Override public void syncOsdSurfaceRequirementFromMpv() { surfaceCoordinator.syncOsdSurfaceRequirementFromMpv(); }
            @Override public void invalidateState() { MpvPlayer.this.invalidateState(); }
            @Override public String propertyStringOrInt(String property) { return MpvPlayer.this.propertyStringOrInt(property); }
            @Override public String stringProperty(String property, String fallback) { return MpvPlayer.this.stringProperty(property, fallback); }
            @Override public int intProperty(String property, int fallback) { return MpvPlayer.this.intProperty(property, fallback); }
            @Override public boolean booleanProperty(String property, boolean fallback) { return MpvPlayer.this.booleanProperty(property, fallback); }
            @Override public double doubleProperty(String property, double fallback) { return MpvPlayer.this.doubleProperty(property, fallback); }
            @Override public void safeSetPropertyString(String name, String value) { MpvPlayer.this.safeSetPropertyString(name, value); }
            @Override public void safeSetPropertyInt(String name, int value) { MpvPlayer.this.safeSetPropertyInt(name, value); }
            @Override public void appendSubtitleDiagnostic(String text) { MpvPlayer.this.appendSubtitleDiagnostic(text); }
        });
        endFileValidationRunnable = this::validateEarlyEndFile;
        loadStartRetryRunnable = this::retryLoadIfNotStarted;
        mediaReplacementStopTimeoutRunnable = this::resumeMediaReplacementAfterStopTimeout;
        initialTrackSelectionGateTimeoutRunnable = () -> releaseInitialTrackSelectionGate("timeout");
        isoTrackMetadataReadyListener = this::onIsoTrackMetadataReady;
        hlsCoordinator = new MpvHlsCoordinator(new MpvHlsCoordinator.Host() {
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public boolean isReleased() { return released; }
            @Override public boolean isStopping() { return lifecycleCoordinator.isStopping(); }
            @Override public boolean isPlayWhenReady() { return playWhenReady; }
            @Override public boolean isPlaybackRestarted() { return lifecycleCoordinator.isPlaybackRestarted(); }
            @Override public boolean isRepeatOne() { return repeatOne; }
            @Override public boolean isLikelyHls() { return lifecycleCoordinator.isCurrentLikelyHls(); }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public long getCachedSelectedHlsBitrate() { return cachedSelectedHlsBitrate; }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public boolean shouldCollectDebugDetails() { return MpvPlayer.this.shouldCollectDebugDetails(); }
            @Override public boolean hasSeekTarget() { return seekPositionState.hasTarget(); }
            @Override public long getCachedCacheSpeedBytesPerSecond() { return cacheCoordinator.getCachedCacheSpeedBytesPerSecond(); }
            @Override public boolean isFreshCacheSpeedSample(long nowMs) { return cacheCoordinator.isFreshCacheSpeedSample(nowMs); }
            @Override public boolean isCachedCacheUnderrun() { return cacheCoordinator.isCachedCacheUnderrun(); }
            @Override public long getCachedCacheUnderrunCount() { return cacheCoordinator.getCachedCacheUnderrunCount(); }
            @Override public MpvAutoHlsBitrateState.Snapshot getAutoHlsBitrateSnapshot() { return cacheCoordinator.getAutoHlsBitrateSnapshot(); }
            @Override public String stringProperty(String name, String fallback) { return MpvPlayer.this.stringProperty(name, fallback); }
            @Override public int mpvSetPropertyString(String name, String value) { return MpvPlayer.this.mpvSetPropertyString(name, value); }
            @Override public void safeSetPropertyString(String name, String value) { MpvPlayer.this.safeSetPropertyString(name, value); }
            @Override public double doubleProperty(String name, double fallback) { return MpvPlayer.this.doubleProperty(name, fallback); }
            @Override public long doubleSecondsToMs(@Nullable Object value, long fallback) { return MpvPlayer.this.doubleSecondsToMs(value, fallback); }
            @Override public void seekToPosition(long positionMs, boolean fromAdblock) { MpvPlayer.this.seekToPosition(positionMs, fromAdblock); }
            @Override public void markPlaybackEnded(String reason) { MpvPlayer.this.markPlaybackEnded(reason); }
            @Override public void clearEofReached() { lifecycleCoordinator.setEofReached(false); }
        });
        diagnostics.felBindProbe().connect(mainHandler::post, action -> {
            if (!initialized || released || nativeContextOwner != this) return false;
            if (action.equals("start") && !com.github.catvod.crawler.DebugLogStore.isEnabled()) return false;
            return mpvSetPropertyString("android-fel-bind-probe", action) >= 0;
        });
        playbackParameters = PlaybackParameters.DEFAULT;
        videoSize = VideoSize.UNKNOWN;
        playbackState = Player.STATE_IDLE;
        initialSeekPositionMs = C.TIME_UNSET;
        cachedDurationMs = C.TIME_UNSET;
        playWhenReady = true;
        volume = 1f;
        propertyDispatcher = new MpvPropertyDispatcher(new MpvPropertyDispatcher.Host() {
            @Override public boolean hasMediaItem() { return mediaItem != null; }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public boolean isStopping() { return lifecycleCoordinator.isStopping(); }
            @Override public boolean isEofReached() { return lifecycleCoordinator.isEofReached(); }
            @Override public boolean isPlaybackRestarted() { return lifecycleCoordinator.isPlaybackRestarted(); }
            @Override public boolean isPlayWhenReady() { return playWhenReady; }
            @Override public boolean isInitialTrackSelectionGateActive() { return initialTrackSelectionGateActive; }
            @Override public int getPlaybackState() { return playbackState; }
            @Override public void setPlaybackState(int state) { playbackState = state; }
            @Override public boolean isLoading() { return loading; }
            @Override public void setLoading(boolean loading) { MpvPlayer.this.loading = loading; }
            @Override public void setEofReached(boolean eof) { lifecycleCoordinator.setEofReached(eof); }
            @Override public void setIdleActive(boolean idle) { lifecycleCoordinator.setIdleActive(idle); }
            @Override public void markPlaybackEnded(String reason) { lifecycleCoordinator.markPlaybackEnded(reason); }
            @Override public void invalidateState() { MpvPlayer.this.invalidateState(); }
            @Override public boolean shouldPauseNativePlayback() { return MpvPlayer.this.shouldPauseNativePlayback(); }
            @Override public void safeSetPropertyBoolean(String name, boolean value) { MpvPlayer.this.safeSetPropertyBoolean(name, value); }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public void setCachedPositionMs(long positionMs) { cachedPositionMs = positionMs; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public void setCachedDurationMs(long durationMs) { cachedDurationMs = durationMs; }
            @Override public long stabilizedPositionMs(long rawPositionMs) { return MpvPlayer.this.stabilizedPositionMs(rawPositionMs); }
            @Override public long doubleSecondsToMs(@Nullable Object value, long fallback) { return MpvPlayer.this.doubleSecondsToMs(value, fallback); }
            @Override public void updateHlsAdBoundary(long positionMs) { hlsCoordinator.updateHlsAdBoundary(positionMs); }
            @Override public void maybeSkipHlsAd() { hlsCoordinator.maybeSkipHlsAd(); }
            @Override public boolean handleHlsAdBoundaryEof() { return hlsCoordinator.handleHlsAdBoundaryEof(); }
            @Override public void recordCacheObservedProperty(String property, @Nullable Object value) { cacheCoordinator.recordObservedProperty(property, value); }
            @Override public void recordCacheMetric(String property, @Nullable Object value, long nowMs) { cacheCoordinator.recordCacheMetric(property, value, nowMs); }
            @Override public void handleDemuxerCacheProperty(String property, @Nullable Object value) { cacheCoordinator.handlePropertyChange(property, value); }
            @Override public boolean isDiscNavigationActive() { return discNavController.isDiscNavigationActive(); }
            @Override public void onDiscMenuActiveChanged(boolean active) { discNavController.onDiscMenuActiveChanged(active); }
            @Override public void refreshChapters() { MpvPlayer.this.refreshChapters(); }
            @Override public void handleChapterListProperty(Object value) { MpvPlayer.this.handleChapterListProperty(value); }
            @Override public void updateVideoSize(String reason) { MpvPlayer.this.updateVideoSize(reason); }
            @Override public VideoSize getVideoSize() { return videoSize; }
            @Override public void scheduleTrackRefresh(String reason) { MpvPlayer.this.scheduleTrackRefresh(reason); }
            @Override public float videoFrameRate() { return MpvPlayer.this.videoFrameRate(); }
            @Override public void onContentFrameRateChanged(float frameRate) { surfaceCoordinator.setCachedContentFrameRate(frameRate); }
            @Override public boolean reportFirstSubmittedVideoFrame() { return MpvPlayer.this.reportFirstSubmittedVideoFrame(); }
            @Override public void onFelBindProbeObserved(String text) { diagnostics.felBindProbe().observed(text); }
            @Override public void onMsgLevelChanged(String level) { diagnosticCoordinator.onMsgLevelChanged(level, diagnostics); }
            @Override public void setCustomButtonStates(String states) { MpvPlayer.this.setCustomButtonStates(states); }
        });
        diagnosticCoordinator = new MpvDiagnosticCoordinator(new MpvDiagnosticCoordinator.Host() {
            @Override public Context getContext() { return context; }
            @Override public MpvPlayerConfig getConfig() { return config; }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public boolean isReleased() { return released; }
            @Override public long getPropertyGeneration() { return propertyGeneration.get(); }
            @Override public PlaybackException getPlayerError() { return playerError; }
            @Override public MpvPropertyDispatcher getPropertyDispatcher() { return propertyDispatcher; }
            @Override public MpvLifecycleCoordinator getLifecycleCoordinator() { return lifecycleCoordinator; }
            @Override public MpvHlsCoordinator getHlsCoordinator() { return hlsCoordinator; }
            @Override public MpvLutShader getLutShader() { return lutShader; }
            @Override public VideoSize getVideoSize() { return videoSize; }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public Tracks getCurrentTracks() { return trackCoordinator.getCurrentTracks(); }
            @Override public float getVideoFrameRate() { return MpvPlayer.this.videoFrameRate(); }
            @Override public boolean setRuntimeStringChecked(String name, String value) { return MpvPlayer.this.setRuntimeStringChecked(name, value); }
            @Override public String stringProperty(String property, String fallback) { return MpvPlayer.this.stringProperty(property, fallback); }
            @Override public boolean booleanProperty(String property, boolean fallback) { return MpvPlayer.this.booleanProperty(property, fallback); }
            @Override public double doubleProperty(String property, double fallback) { return MpvPlayer.this.doubleProperty(property, fallback); }
            @Override public long longProperty(String property, long fallback) { return MpvPlayer.this.longProperty(property, fallback); }
            @Override public MpvTrackCoordinator getTrackCoordinator() { return trackCoordinator; }
            @Override public String propertyStringOrInt(String property) { return MpvPlayer.this.propertyStringOrInt(property); }
            @Override public void setDiagnosticTracking(boolean enabled, boolean deep, int categories) { mainThreadWatchdog.setDiagnosticTracking(enabled, deep, categories); }
            @Override public void fail(Throwable e, int errorCode) { MpvPlayer.this.fail(e, errorCode); }
            @Override public void maybeRetryDtsHdAsCore(String line) { MpvPlayer.this.maybeRetryDtsHdAsCore(line); }
            @Override public void postToMain(Runnable r) { MpvPlayer.this.postToMain(r); }
        });
        lifecycleCoordinator = new MpvLifecycleCoordinator(new MpvLifecycleCoordinator.Host() {
            @Override public Context getContext() { return context; }
            @Override public MpvPlayerConfig getConfig() { return config; }
            @Override public MediaItem getMediaItem() { return mediaItem; }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public PlaybackParameters getPlaybackParameters() { return playbackParameters; }
            @Override public float getVolume() { return volume; }
            @Override public boolean isRepeatOne() { return repeatOne; }
            @Override public String getInitialSubtitleTrackId() { return initialSubtitleTrackId; }
            @Override public long getInitialSeekPositionMs() { return initialSeekPositionMs; }
            @Override public void setInitialSeekPositionMs(long pos) { initialSeekPositionMs = pos; }
            @Override public long getCachedPositionMs() { return cachedPositionMs; }
            @Override public void setCachedPositionMs(long pos) { cachedPositionMs = pos; }
            @Override public long getCachedDurationMs() { return cachedDurationMs; }
            @Override public void setCachedDurationMs(long duration) { cachedDurationMs = duration; }
            @Override public int getPlaybackState() { return playbackState; }
            @Override public void setPlaybackState(int state) { playbackState = state; }
            @Override public boolean isLoading() { return loading; }
            @Override public void setLoading(boolean loadingVal) { loading = loadingVal; }
            @Override public boolean isPlayWhenReady() { return playWhenReady; }
            @Override public boolean isInitialTrackSelectionGateActive() { return initialTrackSelectionGateActive; }
            @Override public void setInitialTrackSelectionGateActive(boolean active) { initialTrackSelectionGateActive = active; }
            @Override public boolean isReleased() { return released; }
            @Override public PlaybackException getPlayerError() { return playerError; }
            @Override public void setPlayerError(PlaybackException error) { playerError = error; }
            @Override public VideoSize getVideoSize() { return videoSize; }
            @Override public void setVideoSize(VideoSize size) { videoSize = size; }
            @Override public MpvSurfaceCoordinator getSurfaceCoordinator() { return surfaceCoordinator; }
            @Override public MpvTrackCoordinator getTrackCoordinator() { return trackCoordinator; }
            @Override public MpvCacheCoordinator getCacheCoordinator() { return cacheCoordinator; }
            @Override public MpvHlsCoordinator getHlsCoordinator() { return hlsCoordinator; }
            @Override public MpvDiscNavController getDiscNavController() { return discNavController; }
            @Override public MpvPropertyDispatcher getPropertyDispatcher() { return propertyDispatcher; }
            @Override public MpvMediaReplacementCoordinator getMediaReplacementCoordinator() { return mediaReplacementCoordinator; }
            @Override public MpvSeekPositionState getSeekPositionState() { return seekPositionState; }
            @Override public MpvDiagnosticCollector getDiagnostics() { return diagnostics; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public void setInitialized(boolean initializedVal) { initialized = initializedVal; }
            @Override public boolean enqueueMpvCommand(String... command) { return MpvPlayer.this.enqueueMpvCommand(command); }
            @Override public void mpvCommand(String[] command) { MpvPlayer.this.mpvCommand(command); }
            @Override public int mpvSetOptionString(String name, String value) { return MpvPlayer.this.mpvSetOptionString(name, value); }
            @Override public void safeSetPropertyString(String name, String value) { MpvPlayer.this.safeSetPropertyString(name, value); }
            @Override public void safeSetPropertyBoolean(String name, boolean value) { MpvPlayer.this.safeSetPropertyBoolean(name, value); }
            @Override public void safeSetPropertyDouble(String name, double value) { MpvPlayer.this.safeSetPropertyDouble(name, value); }
            @Override public void safeSetPropertyLong(String name, long value) { MpvPlayer.this.safeSetPropertyLong(name, value); }
            @Override public String stringProperty(String property, String fallback) { return MpvPlayer.this.stringProperty(property, fallback); }
            @Override public boolean booleanProperty(String property, boolean fallback) { return MpvPlayer.this.booleanProperty(property, fallback); }
            @Override public double doubleProperty(String property, double fallback) { return MpvPlayer.this.doubleProperty(property, fallback); }
            @Override public int intProperty(String property, int fallback) { return MpvPlayer.this.intProperty(property, fallback); }
            @Override public long longProperty(String property, long fallback) { return MpvPlayer.this.longProperty(property, fallback); }
            @Override public void observe(String property, int format) { MpvPlayer.this.observe(property, format); }
            @Override public long doubleSecondsToMs(@Nullable Object value, long fallback) { return MpvPlayer.this.doubleSecondsToMs(value, fallback); }
            @Override public void seekMpv(long targetPositionMs) { MpvPlayer.this.seekMpv(targetPositionMs); }
            @Override public void invalidateState() { MpvPlayer.this.invalidateState(); }
            @Override public void startStateRefresh() { MpvPlayer.this.startStateRefresh(); }
            @Override public void stopStateRefresh() { MpvPlayer.this.stopStateRefresh(); }
            @Override public void startMainThreadWatchdog() { MpvPlayer.this.startMainThreadWatchdog(); }
            @Override public void stopMainThreadWatchdog() { MpvPlayer.this.stopMainThreadWatchdog(); }
            @Override public void postToMain(Runnable r) { MpvPlayer.this.postToMain(r); }
            @Override public void removeMainCallbacks(Runnable r) { mainHandler.removeCallbacks(r); }
            @Override public void postDelayedToMain(Runnable r, long delayMs) { mainHandler.postDelayed(r, delayMs); }
            @Override public void closeContentFds() { MpvPlayer.this.closeContentFds(); }
            @Override public void applySubtitleStyle() { MpvPlayer.this.applySubtitleStyle(); }
            @Override public void applyTextOffset() { MpvPlayer.this.applyTextOffset(); }
            @Override public void applyAudioOffset() { MpvPlayer.this.applyAudioOffset(); }
            @Override public void updateVideoSize(String reason) { MpvPlayer.this.updateVideoSize(reason); }
            @Override public void scheduleTrackRefresh(String reason) { MpvPlayer.this.scheduleTrackRefresh(reason); }
            @Override public void refreshTracks() { MpvPlayer.this.refreshTracks(); }
            @Override public void refreshChapters() { MpvPlayer.this.refreshChapters(); }
            @Override public void addSubtitleConfigurations() { MpvPlayer.this.addSubtitleConfigurations(); }
            @Override public void closeIsoSession() { MpvPlayer.this.closeIsoSession(); }
            @Override public void requestIsoOsdSurface() { MpvPlayer.this.requestIsoOsdSurface(); }
            @Override public void attachIsoTrackMetadataListener() { MpvPlayer.this.attachIsoTrackMetadataListener(); }
            @Override public void logSourceDiagnostics(MediaItem item, String playableUri, Map<String, String> headers) { diagnosticCoordinator.logSourceDiagnostics(item, playableUri, headers); }
            @Override public void resetFailureSignals() { diagnosticCoordinator.resetFailureSignals(); }
            @Override public void resumeMediaReplacement(String reason) { MpvPlayer.this.resumeMediaReplacement(reason); }
            @Override public void applyPreloadDiskCacheMode() { MpvPlayer.this.applyPreloadDiskCacheMode(); }
            @Override public void applyCacheTimePolicy() { MpvPlayer.this.applyCacheTimePolicy(); }
            @Override public void updatePreloadCacheOverlay() { MpvPlayer.this.updatePreloadCacheOverlay(); }
            @Override public void restorePreloadCacheOverlay() { MpvPlayer.this.restorePreloadCacheOverlay(); }
            @Override public void clearCoalescedPropertyEvents() { MpvPlayer.this.clearCoalescedPropertyEvents(); }
            @Override public void cancelScheduledTrackRefresh() { MpvPlayer.this.cancelScheduledTrackRefresh(); }
            @Override public void clearSurfaceFrameRate() { MpvPlayer.this.clearSurfaceFrameRate(); }
            @Override public void resetCacheState() { MpvPlayer.this.resetCacheState(); }
            @Override public void appendSubtitleDiagnostic(String line) { MpvPlayer.this.appendSubtitleDiagnostic(line); }
            @Override public boolean shouldCollectDebugDetails() { return diagnosticCoordinator.shouldCollectDebugDetails(); }
            @Override public String diagnosticSummary() { return diagnosticCoordinator.diagnosticSummary(videoSize, cachedPositionMs, cachedDurationMs, trackCoordinator.getCurrentTracks()); }
            @Override public boolean shouldPauseNativePlayback() { return MpvPlayer.this.shouldPauseNativePlayback(); }
            @Override public void bindVideoOutput() { MpvPlayer.this.bindVideoOutput(); }
            @Override public void applyShaderPipeline(boolean force) { MpvPlayer.this.applyShaderPipeline(force); }
            @Override public void scheduleLoadStartRetry() { MpvPlayer.this.scheduleLoadStartRetry(); }
            @Override public void ensureNativeContextOwner() throws IOException { MpvPlayer.this.ensureNativeContextOwner(); }
            @Override public void syncDiagnosticLogLevel() { diagnosticCoordinator.syncDiagnosticLogLevel(diagnostics); }
            @Override public String endFileReasonName(int reason) { return diagnosticCoordinator.endFileReasonName(reason); }
            @Override public String mpvErrorName(int error) { return diagnosticCoordinator.mpvErrorName(error); }
            @Override public Throwable nativeEndFileError(int reason, int error, @Nullable String errorText) { return diagnosticCoordinator.nativeEndFileError(reason, error, errorText); }
            @Override public int nativeEndFilePlaybackExceptionCode(int error) { return diagnosticCoordinator.nativeEndFilePlaybackExceptionCode(error); }
            @Override public Throwable classifyLoadError(Throwable e, String message) { return diagnosticCoordinator.classifyLoadError(e, message); }
            @Override public String failedLoadedMediaMessage() { return diagnosticCoordinator.failedLoadedMediaMessage(); }
            @Override public boolean isFailedLoadedMedia() { return diagnosticCoordinator.isFailedLoadedMedia(); }
            @Override public void clearRecentLogs() { diagnosticCoordinator.clearRecentLogs(); }
            @Override public String playableUri(MediaItem item) throws IOException { return MpvPlayer.this.playableUri(item); }
            @Override public boolean isImageUri(String uri) { return MpvPlayer.this.isImageUri(uri); }
            @Override public boolean sameUri(String a, String b) { return MpvPlayer.this.sameUri(a, b); }
        });
        nativeBridge = new MpvNativeBridge(new MpvNativeBridge.Host() {
            @Override public boolean isInitialized() { return initialized; }
            @Override public MpvMainThreadWatchdog getMainThreadWatchdog() { return mainThreadWatchdog; }
            @Override public MpvDiagnosticCollector getDiagnostics() { return diagnostics; }
            @Override public MpvPropertySnapshot getPropertySnapshot() { return propertySnapshot; }
            @Override public void onBaseMsgLevelOptionSet(String value) { diagnosticCoordinator.onBaseMsgLevelOptionSet(value); }
        });
        captionCoordinator = new MpvCaptionCoordinator(new MpvCaptionCoordinator.Host() {
            @Override public Context getContext() { return context; }
            @Override public boolean isInitialized() { return initialized; }
            @Override public void safeSetPropertyDouble(String property, double value) { nativeBridge.safeSetPropertyDouble(property, value); }
            @Override public void safeSetPropertyString(String property, String value) { nativeBridge.safeSetPropertyString(property, value); }
        });
        videoSizeCoordinator = new MpvVideoSizeCoordinator(new MpvVideoSizeCoordinator.Host() {
            @Override public int cachedVideoIntProperty(String property, int fallback) { return propertyDispatcher.cachedVideoIntProperty(property, fallback); }
            @Override public boolean isFileLoaded() { return lifecycleCoordinator.isFileLoaded(); }
            @Override public boolean isPlaybackRestarted() { return lifecycleCoordinator.isPlaybackRestarted(); }
            @Override public int getVideoReconfigCount() { return lifecycleCoordinator.getVideoReconfigCount(); }
            @Override public int getSurfaceWidth() { return surfaceCoordinator.getSurfaceWidth(); }
            @Override public int getSurfaceHeight() { return surfaceCoordinator.getSurfaceHeight(); }
            @Override public String getPlaybackTraceId() { return playbackTraceId; }
            @Override public boolean shouldCollectDebugDetails() { return MpvPlayer.this.shouldCollectDebugDetails(); }
        });
    }

    @Override
    protected State getState() {
        int state = playbackState;
        MediaItem currentItem = mediaItem;
        if (currentItem == null && state != Player.STATE_IDLE && state != Player.STATE_ENDED) {
            Log.w(TAG, "Coerce empty playlist state=" + state + " loading=" + loading + " fileLoaded=" + lifecycleCoordinator.isFileLoaded() + " playbackRestarted=" + lifecycleCoordinator.isPlaybackRestarted());
            state = Player.STATE_IDLE;
        }
        boolean firstFrameEvent = newlyRenderedFirstFrame;
        newlyRenderedFirstFrame = false;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setIsLoading(currentItem != null && loading && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                .setPlayerError(playerError)
                .setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF)
                .setPlaybackParameters(playbackParameters)
                .setTextOffsetMs(captionCoordinator.getTextOffsetMs())
                .setAudioOffsetMs(captionCoordinator.getAudioOffsetMs())
                .setVideoSize(videoSize)
                .setNewlyRenderedFirstFrame(firstFrameEvent)
                .setVolume(volume)
                .setCurrentMediaChapters(getCurrentChapters())
                .setCurrentMediaEditions(trackCoordinator.getCurrentEditions())
                .setPlaylist(currentItem == null ? ImmutableList.of() : ImmutableList.of(mediaItemData(currentItem)))
                .setCurrentMediaItemIndex(currentItem == null ? C.INDEX_UNSET : 0);
        if (currentItem != null) {
            long duration = cachedDurationMs > 0 ? cachedDurationMs : C.TIME_UNSET;
            long position = Math.max(0, cachedPositionMs);
            PositionSupplier positionSupplier = isPlayingInternal()
                    ? PositionSupplier.getExtrapolating(position, playbackParameters.speed)
                    : PositionSupplier.getConstant(position);
            builder.setContentPositionMs(positionSupplier);
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPositionMs(position, duration)));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(Math.max(0, bufferedPositionMs(position, duration) - position)));
        }
        return builder.build();
    }

    private MediaItemData mediaItemData(MediaItem item) {
        long duration = cachedDurationMs > 0 ? cachedDurationMs : C.TIME_UNSET;
        return new MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(duration == C.TIME_UNSET ? C.TIME_UNSET : duration * 1000)
                .setIsSeekable(duration > 0)
                .setIsDynamic(duration == C.TIME_UNSET)
                .setTracks(trackCoordinator.getCurrentTracks())
                .build();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        hlsCoordinator.resetHlsAdBoundary();
        restorePreloadCacheOverlay();
        clearCoalescedPropertyEvents();
        boolean reusingContext = canReuseContextForMediaReplacement();
        boolean hadActiveMedia = mediaItem != null && (lifecycleCoordinator.isFileLoaded() || lifecycleCoordinator.isLoadStarted() || playbackState != Player.STATE_IDLE);
        cancelScheduledTrackRefresh();
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        initialSeekPositionMs = mediaItem != null && startPositionMs > 0
                ? startPositionMs : C.TIME_UNSET;
        seekPositionState.clear();
        cachedPositionMs = Math.max(0, startPositionMs == C.TIME_UNSET ? 0 : startPositionMs);
        cachedDurationMs = C.TIME_UNSET;
        resetVideoMetadataCache();
        resetCacheState();
        trackCoordinator.resetTrackDiagnostics();
        surfaceCoordinator.onNewMediaStarting();
        mainHandler.removeCallbacks(initialTrackSelectionGateTimeoutRunnable);
        initialTrackSelectionGateActive = mediaItem != null
                && initialTrackSelectionGateRequested;
        playbackState = Player.STATE_IDLE;
        loading = false;
        closeIsoSession();
        lifecycleCoordinator.resetForNewMedia();
        trackCoordinator.reset();
        diagnosticCoordinator.resetFailureSignals();
        diagnosticCoordinator.clearRecentLogs();
        playerError = null;
        mainHandler.removeCallbacks(mediaReplacementStopTimeoutRunnable);
        if (mediaItem == null) {
            mediaReplacementCoordinator.reset();
        } else {
            if (initialized && !reusingContext) releaseNativeContext("new media");
            // PlaybackService may keep this player after preparing a terminal
            // Surface detach. Retire that context before allowing a new binding.
            surfaceTeardownPolicy.beginNewMedia();
            long generation = mediaReplacementCoordinator.begin(reusingContext, hadActiveMedia, lifecycleCoordinator.isStopping());
            if (reusingContext) {
                SpiderDebug.log("mpv", "context reused reason=new-media generation=%d stopPending=%s active=%s player=%s", generation, lifecycleCoordinator.isStopping(), hadActiveMedia, identity(this));
            }
        }
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        closeContentFds();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        if (mediaItems.isEmpty()) {
            stopInternal(true);
            stopMainThreadWatchdog();
            mediaItem = null;
            invalidateState();
        } else {
            mediaItem = mediaItems.get(0);
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        stopInternal(true);
        stopMainThreadWatchdog();
        mediaItem = null;
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        long generation = mediaReplacementCoordinator.generation();
        boolean reusingContext = canReuseContextForMediaReplacement();
        if (mediaReplacementCoordinator.deferPrepare(reusingContext, lifecycleCoordinator.isStopping())) {
            SpiderDebug.log("mpv", "media replace deferred generation=%d reason=stop-pending player=%s", generation, identity(this));
            mainHandler.removeCallbacks(mediaReplacementStopTimeoutRunnable);
            mainHandler.postDelayed(mediaReplacementStopTimeoutRunnable, MEDIA_REPLACEMENT_STOP_TIMEOUT_MS);
        } else {
            openCurrent(generation);
        }
        return Futures.immediateVoidFuture();
    }

    private boolean canReuseContextForMediaReplacement() {
        return initialized && !released && nativeContextOwner == this
                && surfaceTeardownPolicy.shouldBindSurface()
                && "mediacodec_embed".equals(config.vo());
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        hlsCoordinator.setPlaybackPaused(!playWhenReady);
        hlsCoordinator.updateHlsAdBoundary(cachedPositionMs);
        if (initialized && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            safeSetPropertyBoolean("pause", shouldPauseNativePlayback());
        }
        updatePreloadCacheOverlay();
        if (!playWhenReady) hlsCoordinator.requestHlsPreload(cachedPositionMs);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        diagnostics.end(MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_STOP, 0);
        stopInternal(true);
        stopMainThreadWatchdog();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        diagnostics.end(MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_QUIT, 0);
        prepareTerminalRelease();
        released = true;
        startMainThreadWatchdog();
        videoSizeProbeListener = null;
        cancelScheduledTrackRefresh();
        mainHandler.removeCallbacks(mediaReplacementStopTimeoutRunnable);
        mediaReplacementCoordinator.reset();
        try {
            stopInternal(false);
            hlsCoordinator.release();
            clearVideoOutput();
            mainHandler.removeCallbacks(stateRefreshRunnable);
            mainHandler.removeCallbacks(endFileValidationRunnable);
            releaseNativeContext("release");
        } finally {
            discNavController.release();
            customButtonStateListeners.clear();
            stopMainThreadWatchdog();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE;
        if (initialized) safeSetPropertyString("loop-file",
                hlsCoordinator.isHlsAdClippingActive() ? "no" : repeatOne ? "inf" : "no");
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        return seekToPosition(positionMs, false);
    }

    private ListenableFuture<?> seekToPosition(long positionMs, boolean automaticAdSkip) {
        if (positionMs == C.TIME_UNSET) positionMs = 0;
        if (discNavController.isDiscMenuActive()) return Futures.immediateVoidFuture();
        if (!automaticAdSkip) {
            hlsCoordinator.resetHlsAdBoundary();
        }
        positionMs = hlsCoordinator.resolveHlsAdSeekTarget(Math.max(0, positionMs));
        cachedPositionMs = Math.max(0, positionMs);
        resetCacheTimelineForSeek(cachedPositionMs);
        if (!lifecycleCoordinator.isFileLoaded()) {
            // Keep only the latest target until FILE_LOADED restores it once,
            // or recognizes that loadfile's start option already covered it.
            initialSeekPositionMs = cachedPositionMs;
            invalidateState();
            return Futures.immediateVoidFuture();
        }
        if (initialized && playbackState != Player.STATE_IDLE) {
            long nowMs = SystemClock.elapsedRealtime();
            if (lifecycleCoordinator.isFileLoaded()) {
                initialSeekPositionMs = C.TIME_UNSET;
                seekPositionState.begin(cachedPositionMs, nowMs);
                cacheCoordinator.onPlaybackDiscontinuity(nowMs);
            }
            if (lifecycleCoordinator.isCurrentLikelyHls() && lifecycleCoordinator.isPlaybackRestarted()) {
                hlsCoordinator.cancelAutomaticPreloadForDiscontinuity();
            }
            hlsCoordinator.updateHlsAdBoundary(cachedPositionMs);
            seekMpv(cachedPositionMs);
            if (lifecycleCoordinator.isCurrentLikelyHls() && lifecycleCoordinator.isPlaybackRestarted()) hlsCoordinator.requestHlsPreload(cachedPositionMs);
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_BUFFERING;
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        if (initialized) safeSetPropertyDouble("speed", playbackParameters.speed);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (initialized) safeSetPropertyDouble("volume", this.volume * 100.0);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long offsetMs) {
        captionCoordinator.setTextOffsetMs(offsetMs);
        captionCoordinator.applyTextOffset();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long offsetMs) {
        captionCoordinator.setAudioOffsetMs(offsetMs);
        captionCoordinator.applyAudioOffset();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    public void setSubtitleStyle(float textSize, float position) {
        captionCoordinator.setSubtitleStyle(textSize, position);
        invalidateState();
    }

    public void prepareTerminalRelease() {
        if (surfaceTeardownPolicy.requestTerminalRelease()) {
            SpiderDebug.log("mpv", "terminal Surface release requested player=%s", identity(this));
        }
    }

    public String getAudioSpdifCodecs() {
        return lifecycleCoordinator.getActiveAudioSpdif();
    }

    public void setPlaybackTraceId(String playbackTraceId) {
        this.playbackTraceId = PlaybackTrace.normalize(playbackTraceId);
    }

    public String getPlaybackTraceId() {
        return playbackTraceId;
    }

    /** Sends an application-owned script message without exposing MPVLib to UI code. */
    public boolean sendScriptMessage(String message, String... args) {
        if (!initialized || TextUtils.isEmpty(message)) return false;
        String[] command = new String[2 + (args == null ? 0 : args.length)];
        command[0] = "script-message";
        command[1] = message;
        if (args != null) System.arraycopy(args, 0, command, 2, args.length);
        return enqueueMpvCommand(command);
    }

    /** Lua-owned state cached on the main thread; no synchronous native reads. */
    public boolean isCustomButtonActive(String id) {
        return !TextUtils.isEmpty(id) && ("," + customButtonStates + ",").contains("," + id + ",");
    }

    public void addCustomButtonStateListener(Runnable listener) {
        if (!released && !customButtonStateListeners.contains(listener)) {
            customButtonStateListeners.add(listener);
        }
    }

    public void removeCustomButtonStateListener(Runnable listener) {
        customButtonStateListeners.remove(listener);
    }

    private void setCustomButtonStates(String states) {
        if (customButtonStates.equals(states)) return;
        customButtonStates = states;
        for (Runnable listener : List.copyOf(customButtonStateListeners)) listener.run();
    }

    public PlaybackRoute.Resolution getPlaybackRouteResolution() {
        return PlaybackRoute.resolve(lifecycleCoordinator.getCurrentPlayableUri());
    }

    @Nullable
    public PlaybackResourceClassifier.Classification getResourceClassification() {
        PlaybackResourceClassifier.Classification current = resourceClassification;
        PlaybackResourceClassifier.Classification proxy = hlsCoordinator.resourceClassification();
        if (proxy != null) current = PlaybackResourceClassifier.merge(current, proxy);
        if (current == null) return null;
        try {
            return PlaybackResourceClassifier.observePlayer(current, isCurrentMediaItemLive(), getDuration());
        } catch (Throwable ignored) {
            return current;
        }
    }

    public void setLutShader(@Nullable MpvLutShader shader) {
        lutShader = shader;
        applyShaderPipeline(false);
    }

    public void setLutPreviewProgress(float progress) {
        if (!initialized || lutShader == null || !lutShader.isPreview()) return;
        float value = Math.max(0f, Math.min(1f, progress));
        safeCommand(new String[]{
                "change-list", "glsl-shader-opts", "append",
                lutShader.getPreviewOptionKey() + "=" + String.format(Locale.US, "%.5f", value)});
    }

    public AutoCacheBaselineResult applyAutoCacheBaseline(long forwardBytes, long backBytes) {
        return cacheCoordinator.applyAutoCacheBaseline(forwardBytes, backBytes);
    }

    public void clearAutoCacheBaseline() {
        cacheCoordinator.clearAutoCacheBaseline();
    }

    public boolean updateAutomaticPreloadControl(
            boolean automatic,
            boolean resourceAllowed,
            boolean trafficAllowed) {
        return hlsCoordinator.updateAutomaticPreloadControl(
                automatic, resourceAllowed, trafficAllowed);
    }

    public void requestAutomaticHlsPreload(long positionMs) {
        hlsCoordinator.requestAutomaticHlsPreload(positionMs);
    }

    private void requestHlsPreload(long positionMs) {
        hlsCoordinator.requestHlsPreload(positionMs);
    }

    private long selectedHlsBitsPerSecond() {
        return hlsCoordinator.selectedHlsBitsPerSecond();
    }

    public AutoHlsBitrateResult applyAutoHlsBitrate(String option) {
        return cacheCoordinator.applyAutoHlsBitrate(option);
    }

    public void clearAutoHlsBitrate() {
        cacheCoordinator.clearAutoHlsBitrate();
    }

    /** Cached track/proxy HLS state; this method never performs a native query. */
    public AutoHlsRuntimeSnapshot getAutoHlsRuntimeSnapshot() {
        return hlsCoordinator.getAutoHlsRuntimeSnapshot();
    }

    /** Proxy-only upstream and disk facts; this never trusts MPV loopback speed. */
    public AutoHlsPreloadRuntimeSnapshot getAutoHlsPreloadRuntimeSnapshot() {
        return hlsCoordinator.getAutoHlsPreloadRuntimeSnapshot();
    }

    public enum AutoCacheBaselineResult {
        REJECTED("rejected", false, false),
        STAGED("staged", true, true),
        APPLIED("applied", true, false),
        FAILED("failed", false, false);

        private final String label;
        private final boolean accepted;
        private final boolean staged;

        AutoCacheBaselineResult(String label, boolean accepted, boolean staged) {
            this.label = label;
            this.accepted = accepted;
            this.staged = staged;
        }

        public String label() {
            return label;
        }

        public boolean accepted() {
            return accepted;
        }

        public boolean staged() {
            return staged;
        }
    }

    public enum AutoHlsBitrateResult {
        REJECTED("rejected", false, false),
        STAGED("staged", true, true),
        APPLIED("applied", true, false),
        FAILED("failed", false, false);

        private final String label;
        private final boolean accepted;
        private final boolean staged;

        AutoHlsBitrateResult(String label, boolean accepted, boolean staged) {
            this.label = label;
            this.accepted = accepted;
            this.staged = staged;
        }

        public String label() {
            return label;
        }

        public boolean accepted() {
            return accepted;
        }

        public boolean staged() {
            return staged;
        }
    }

    public record HlsVariant(
            long bandwidthBitsPerSecond,
            long averageBandwidthBitsPerSecond,
            int width,
            int height) {

        public HlsVariant {
            bandwidthBitsPerSecond = Math.max(0, bandwidthBitsPerSecond);
            averageBandwidthBitsPerSecond = Math.max(0,
                    averageBandwidthBitsPerSecond);
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        public long selectionBitsPerSecond() {
            return bandwidthBitsPerSecond > 0
                    ? bandwidthBitsPerSecond : averageBandwidthBitsPerSecond;
        }
    }

    public record AutoHlsRuntimeSnapshot(
            boolean likelyHls,
            List<HlsVariant> variants,
            @Nullable HlsVariant selectedVariant,
            long rawInputBitsPerSecond,
            boolean rawInputRateUsable,
            boolean underrun,
            long underrunCount,
            String stagedOption,
            String acceptedOption,
            long observedBitsPerSecond,
            int observedCount) {

        public AutoHlsRuntimeSnapshot {
            variants = variants == null ? List.of() : List.copyOf(variants);
            rawInputBitsPerSecond = Math.max(0, rawInputBitsPerSecond);
            underrunCount = Math.max(0, underrunCount);
            stagedOption = stagedOption == null ? "" : stagedOption;
            acceptedOption = acceptedOption == null ? "" : acceptedOption;
            observedCount = Math.max(0, observedCount);
        }
    }

    public record AutoHlsPreloadRuntimeSnapshot(
            boolean preloadConfigured,
            boolean vod,
            long upstreamBitsPerSecond,
            boolean throughputKnown,
            boolean throughputFresh,
            long throughputSampleAtElapsedMs,
            long throughputAgeMs,
            int acceptedThroughputSamples,
            int rejectedThroughputSamples,
            String lastThroughputRejectReason,
            int foregroundRequests,
            boolean cacheEnabled,
            boolean cacheStorageKnown,
            boolean cacheBudgetAvailable,
            boolean cacheCircuitOpen,
            long cachePhysicalBytes,
            long cacheReservedBytes,
            long cacheNewWriteBudgetBytes,
            long cacheEffectiveCapacityBytes,
            int preloadTasks) {

        public AutoHlsPreloadRuntimeSnapshot {
            upstreamBitsPerSecond = Math.max(0, upstreamBitsPerSecond);
            acceptedThroughputSamples = Math.max(0, acceptedThroughputSamples);
            rejectedThroughputSamples = Math.max(0, rejectedThroughputSamples);
            lastThroughputRejectReason = lastThroughputRejectReason == null
                    ? "none" : lastThroughputRejectReason;
            foregroundRequests = Math.max(0, foregroundRequests);
            cachePhysicalBytes = Math.max(0, cachePhysicalBytes);
            cacheReservedBytes = Math.max(0, cacheReservedBytes);
            cacheNewWriteBudgetBytes = Math.max(0, cacheNewWriteBudgetBytes);
            cacheEffectiveCapacityBytes = Math.max(0, cacheEffectiveCapacityBytes);
            preloadTasks = Math.max(0, preloadTasks);
        }
    }

    public PlayerCacheState getCacheState() {
        if (initialized && mediaItem != null) refreshCacheState();
        return cacheCoordinator.buildCacheState();
    }

    /** Observer-only cache snapshot for periodic telemetry; never queries native properties. */
    public PlayerCacheState getCachedCacheState() {
        return cacheCoordinator.buildCacheState();
    }

    public String getRenderDiagnostics() {
        return diagnosticCoordinator.getRenderDiagnostics();
    }

    public String getRuntimeDiagnostics() {
        return diagnosticCoordinator.getRuntimeDiagnostics();
    }

    /** Whole-device GPU sampling is owned by the panel's background resource monitor. */
    public String getGpuLoadDiagnostics() {
        return "";
    }

    public void setGpuLoadDiagnosticsEnabled(boolean enabled) {
    }

    /** Cached values from mpv runtime property observers; never falls back to requested config. */
    public String getObservedHwdecCurrent() {
        return diagnosticCoordinator.getObservedHwdecCurrent();
    }

    public boolean hasObservedHwdecCurrent() {
        return diagnosticCoordinator.hasObservedHwdecCurrent();
    }

    /** Cached values from mpv runtime property observers; never falls back to requested config. */
    public String getObservedCurrentVideoOutput() {
        return diagnosticCoordinator.getObservedCurrentVideoOutput();
    }

    public boolean hasObservedCurrentVideoOutput() {
        return diagnosticCoordinator.hasObservedCurrentVideoOutput();
    }

    public boolean isAndroidFelActive() {
        return propertyDispatcher.isAndroidFelActive();
    }

    public boolean isNativeOutputSelectionReady() {
        return lifecycleCoordinator.isFileLoaded();
    }

    public boolean isObservedVulkanRenderer() {
        return "androidvk".equals(propertyDispatcher.getCachedCurrentGpuContext());
    }

    /** The native VO accepted a frame in this seek/reconfigure epoch. This is
     * submission evidence, not a physical display presentation timestamp. */
    public boolean hasSubmittedVideoFrame() {
        return propertyDispatcher.isVideoFrameSubmitted();
    }

    private boolean reportFirstSubmittedVideoFrame() {
        if (!lifecycleCoordinator.isFileLoaded() || !propertyDispatcher.isVideoFrameSubmitted() || firstVideoFrameReported) return false;
        firstVideoFrameReported = true;
        newlyRenderedFirstFrame = true;
        return true;
    }

    public long getDroppedFrames() {
        return diagnosticCoordinator.getDroppedFrames();
    }

    public boolean hasObservedDroppedFrames() {
        return diagnosticCoordinator.hasObservedDroppedFrames();
    }

    public long getObservedDroppedFrames() {
        return diagnosticCoordinator.getObservedDroppedFrames();
    }

    /** Cached observer values only; this method never queries MPV synchronously. */
    public FrameTimingSnapshot getFrameTimingSnapshot() {
        return diagnosticCoordinator.getFrameTimingSnapshot();
    }

    public record FrameTimingSnapshot(
            long decoderDroppedFrames,
            long outputDroppedFrames,
            long mistimedFrames,
            long delayedFrames,
            double avSyncSeconds,
            float contentFrameRate,
            double displayFrameRate,
            boolean observed) {
    }

    public float getObservedContentFrameRate() {
        return diagnosticCoordinator.getObservedContentFrameRate();
    }

    public float getObservedDisplayFrameRate() {
        return diagnosticCoordinator.getObservedDisplayFrameRate();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        surfaceCoordinator.setVideoOutput(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == surfaceCoordinator.getVideoOutput()) {
            surfaceCoordinator.clearVideoOutput();
            resumePendingSurfaceLoad();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    public void eventProperty(String property) {
        dispatchProperty(property, null);
    }

    @Override
    public void eventProperty(String property, long value) {
        dispatchProperty(property, value);
    }

    @Override
    public void eventProperty(String property, boolean value) {
        dispatchProperty(property, value);
    }

    @Override
    public void eventProperty(String property, String value) {
        dispatchProperty(property, value);
    }

    @Override
    public void eventProperty(String property, double value) {
        dispatchProperty(property, value);
    }

    @Override
    public void event(int eventId) {
        long generation = eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE
                ? propertyGeneration.incrementAndGet() : propertyGeneration.get();
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE) propertySnapshot.diagnosticBegin(generation);
        diagnostics.event(eventId, generation);
        postToMain(() -> {
            if (released) return;
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE)
                propertySnapshot.beginFile(generation);
            handleEvent(eventId);
        });
    }

    @Override
    public void eventPropertyNode(String property, String json) {
        if (released) return;
        if (json == null) PlaybackTrace.log("mpv-native", playbackTraceId,
                "NODE snapshot rejected for %s: invalid or over bounded serializer limits; no synchronous fallback", property);
        if ("chapter-list".equals(property)) {
            dispatchProperty(property, json);
            return;
        }
        if (!"track-list".equals(property)) return;
        MpvPropertySnapshot.TrackList tracks = MpvPropertySnapshot.TrackList.parse(json);
        if (!tracks.valid()) PlaybackTrace.log("mpv-native", playbackTraceId,
                "track snapshot rejected: invalid or oversized NODE; no synchronous fallback");
        dispatchProperty(property, tracks);
    }

    @Override
    public void eventCommandReply(long requestId, int error) {
        diagnostics.command("async-reply", requestId, error, "completed", -1);
        if (surfaceCoordinator.isPendingOsdSurfaceRequestId(requestId)) {
            postToMain(() -> surfaceCoordinator.handleOsdSurfaceReply(requestId, error));
            return;
        }
        if (error >= MPVLib.MpvError.MPV_ERROR_SUCCESS) return;
        postToMain(() -> {
            String message = "asynchronous command failed request=" + requestId + " error=" + error;
            Log.e(TAG, message);
            diagnosticCoordinator.rememberLog(message);
            diagnosticCoordinator.markFailureSignal(message);
        });
    }

    @Override
    public void endFile(int reason, int error, String errorText) {
        diagnostics.endFile(reason, error);
        postToMain(() -> handleEndFile(reason, error, errorText));
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        diagnosticCoordinator.handleLogMessage(prefix, level, text, diagnostics);
    }

    private void openCurrent(long generation) {
        lifecycleCoordinator.openCurrent(generation);
    }

    private void continueOpenCurrent(Map<String, String> headers, long generation) {
        lifecycleCoordinator.continueOpenCurrent(headers, generation);
    }

    private boolean isSurfaceReadyForLoad() {
        return surfaceCoordinator.isSurfaceReadyForLoad();
    }

    private boolean deferLoadUntilSurfaceReady(long generation) {
        return lifecycleCoordinator.deferLoadUntilSurfaceReady(generation);
    }

    private void resumePendingSurfaceLoad() {
        lifecycleCoordinator.resumePendingSurfaceLoad();
    }

    private void startPreparedMedia(long generation) {
        lifecycleCoordinator.startPreparedMedia(generation);
    }

    private void ensureNativeContextOwner() throws IOException {
        ensureInitialized();
    }

    private void ensureInitialized() throws IOException {
        synchronized (NATIVE_CONTEXT_LOCK) {
            if (initialized && nativeContextOwner == this) return;
            if (nativeContextOwner != null && nativeContextOwner != this) {
                MpvPlayer previousOwner = nativeContextOwner;
                SpiderDebug.log("mpv", "native context takeover old=%s new=%s", identity(previousOwner), identity(this));
                previousOwner.released = true;
                previousOwner.releaseNativeContextLocked("takeover");
                previousOwner.stopMainThreadWatchdog();
            }
            if (!MPVLib.ensureLoaded(context)) {
                Throwable e = MPVLib.getLoadError();
                if (e instanceof IOException io) throw io;
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IOException(e == null ? "MPV native libraries are unavailable" : e.getMessage(), e);
            }
            copySupportAssets();
            nativeContextOwner = this;
            if (!mpvTryCreate(context)) {
                throw new IOException("MPV native context creation is already in progress");
            }
            applyPreInitOptions();
            mpvInit();
            initialized = true;
            lifecycleCoordinator.setActiveAudioSpdif(config.audioSpdif());
            MPVLib.addObserver(this);
            MPVLib.addLogObserver(this);
            applyPostInitOptions();
            applyShaderPipeline(true);
            observeProperties();
            syncDiagnosticLogLevel();
        }
    }

    private void applyPreInitOptions() {
        lifecycleCoordinator.applyPreInitOptions();
    }

    private void applyPostInitOptions() {
        effectiveVo = lifecycleCoordinator.applyPostInitOptions();
    }

    private void observeProperties() {
        lifecycleCoordinator.observeProperties();
    }

    private void dispatchProperty(String property, @Nullable Object value) {
        propertyCoalescer.dispatch(property, value);
    }

    private void clearCoalescedPropertyEvents() {
        propertyCoalescer.clear();
    }


    public List<MediaChapter> getCurrentChapters() {
        return trackCoordinator.getCurrentChapters();
    }

    public int getCurrentChapter() {
        return trackCoordinator.getCurrentChapter();
    }

    public Tracks getCurrentTracksSnapshot() {
        return trackCoordinator.getCurrentTracks();
    }

    public VideoTrackDiagnostics getSelectedVideoTrackDiagnostics() {
        return trackCoordinator.getSelectedVideoTrackDiagnostics();
    }

    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        return diagnosticCoordinator.getAudioPlaybackDiagnostics();
    }

    /** Metadata for the first video track, including when mpv temporarily reports vid=no. */
    public VideoTrackDiagnostics getAvailableVideoTrackDiagnostics() {
        return trackCoordinator.getAvailableVideoTrackDiagnostics();
    }

    public VideoSize getVideoSizeSnapshot() {
        return videoSize;
    }

    public void setVideoSizeProbeListener(@Nullable BiConsumer<Integer, Integer> listener) {
        videoSizeProbeListener = listener;
    }

    public void setInitialOsdSurfaceRequested(boolean requested) {
        surfaceCoordinator.setInitialOsdSurfaceRequested(requested);
        if (mediaItem != null) return;
        setOsdSurfaceRequested(requested);
    }

    public void setInitialTrackSelectionGateRequested(boolean requested) {
        initialTrackSelectionGateRequested = requested;
    }

    public void setInitialSubtitleTrackId(@Nullable String trackId) {
        initialSubtitleTrackId = trackId;
    }

    public void releaseInitialTrackSelectionGate() {
        releaseInitialTrackSelectionGate("tracks-restored");
    }

    private void releaseInitialTrackSelectionGate(String reason) {
        if (!initialTrackSelectionGateActive) return;
        initialTrackSelectionGateActive = false;
        mainHandler.removeCallbacks(initialTrackSelectionGateTimeoutRunnable);
        PlaybackTrace.log("mpv", playbackTraceId,
                "initial track gate released reason=%s play=%s fileLoaded=%s restart=%s",
                reason, playWhenReady, lifecycleCoordinator.isFileLoaded(), lifecycleCoordinator.isPlaybackRestarted());
        if (initialized && lifecycleCoordinator.isFileLoaded() && !lifecycleCoordinator.isStopping() && !lifecycleCoordinator.isEofReached()) {
            safeSetPropertyBoolean("pause", shouldPauseNativePlayback());
            invalidateState();
        }
    }

    private boolean shouldPauseNativePlayback() {
        return MpvInitialTrackSelectionPolicy.shouldPauseNativePlayback(
                playWhenReady, initialTrackSelectionGateActive);
    }

    public void resetTrackSelection() {
        trackCoordinator.resetTrackSelection();
    }

    public void setTrackSelection(int type, String mpvId) {
        if (TextUtils.isEmpty(mpvId)) return;
        if (type == C.TRACK_TYPE_TEXT) logSubtitleState("before-select requested=" + mpvId);
        trackCoordinator.setTrackSelection(type, mpvId);
        if (type == C.TRACK_TYPE_TEXT) {
            logSubtitleState("after-select requested=" + mpvId);
            mainHandler.postDelayed(() -> logSubtitleState("after-select-100ms requested=" + mpvId), 100);
            mainHandler.postDelayed(() -> logSubtitleState("after-select-500ms requested=" + mpvId), 500);
        }
    }

    public void restoreVideoTrackSelection() {
        trackCoordinator.restoreVideoTrackSelection();
    }

    public void setSecondarySubtitleTrackSelection(String mpvId) {
        trackCoordinator.setSecondarySubtitleTrackSelection(mpvId);
    }

    public boolean isSecondarySubtitleSelected(String mpvId) {
        return trackCoordinator.isSecondarySubtitleSelected(mpvId);
    }

    public boolean selectEdition(MediaEdition edition) {
        return trackCoordinator.selectEdition(edition);
    }

    public boolean selectChapter(MediaChapter chapter) {
        return trackCoordinator.selectChapter(chapter);
    }

    String propertyStringOrInt(String property) {
        String value = stringProperty(property, "");
        if (!TextUtils.isEmpty(value)) return value;
        int intValue = intProperty(property, Integer.MIN_VALUE);
        return intValue == Integer.MIN_VALUE ? "" : String.valueOf(intValue);
    }

    float videoFrameRate() {
        double fps = propertyDispatcher.getCachedContainerFps() > 0 ? propertyDispatcher.getCachedContainerFps() : propertyDispatcher.getCachedEstimatedVfFps();
        return fps > 0 ? (float) fps : C.RATE_UNSET;
    }

    private static String mpvTrackProperty(int type) {
        return MpvTrackCoordinator.mpvTrackProperty(type);
    }

    void setMpvTrack(int type, String mpvId) {
        trackCoordinator.setMpvTrack(type, mpvId);
    }

    private void logSubtitleState(String reason) {
        diagnosticCoordinator.logSubtitleState(reason);
    }

    void appendSubtitleDiagnostic(String text) {
        diagnosticCoordinator.appendSubtitleDiagnostic(text);
    }

    boolean isDisabledTrackChoice(String choice) {
        return MpvTrackCoordinator.isDisabledTrackChoice(choice);
    }

    private void handleEvent(int eventId) {
        lifecycleCoordinator.handleEvent(eventId);
    }

    private void handleEndFile(int reason, int error, @Nullable String errorText) {
        lifecycleCoordinator.handleEndFile(reason, error, errorText);
    }

    private void markPlaybackEnded(String reason) {
        lifecycleCoordinator.markPlaybackEnded(reason);
    }

    private boolean isSuccessfulNaturalEof(int reason, int error) {
        return lifecycleCoordinator.isSuccessfulNaturalEof(reason, error);
    }

    private boolean isImageUri(String uri) {
        return MpvUriResolver.isImageUri(uri);
    }

    private boolean sameUri(String a, String b) {
        return MpvUriResolver.sameUri(a, b);
    }

    private String playableUri(Uri uri) throws IOException {
        return uriResolver.playableUri(context, uri);
    }

    private String playableUri(MediaItem item) throws IOException {
        return uriResolver.playableUri(context, item);
    }

    private void addSubtitleConfigurations() {
        if (mediaItem == null || mediaItem.localConfiguration == null || mediaItem.localConfiguration.subtitleConfigurations.isEmpty()) return;
        for (MediaItem.SubtitleConfiguration sub : mediaItem.localConfiguration.subtitleConfigurations) {
            Uri uri = sub.uri;
            try {
                mpvCommand(new String[]{"sub-add", playableUri(uri), "auto"});
            } catch (Throwable ignored) {
            }
        }
    }

    private void setVideoOutput(Object output) {
        surfaceCoordinator.setVideoOutput(output);
    }

    private boolean requiresOsdSurface() {
        return surfaceCoordinator.requiresOsdSurface();
    }

    private void requestIsoOsdSurface() {
        surfaceCoordinator.requestIsoOsdSurface();
    }

    public boolean isDiscMenuActive() {
        return discNavController.isDiscMenuActive();
    }

    public void addDiscMenuStateListener(Runnable listener) {
        discNavController.addDiscMenuStateListener(listener);
    }

    public void removeDiscMenuStateListener(Runnable listener) {
        discNavController.removeDiscMenuStateListener(listener);
    }

    public boolean isDiscMenuAvailable() {
        return discNavController.isDiscMenuAvailable();
    }

    public boolean hasDiscNavigationTimeline() {
        return discNavController.hasDiscNavigationTimeline();
    }

    public boolean hasStartedDiscNavigation() {
        return discNavController.hasStartedDiscNavigation();
    }

    public int getDiscRebufferCount() {
        return discNavController.getDiscRebufferCount();
    }

    public long getDiscRebufferTotalMs(long nowMs) {
        return discNavController.getDiscRebufferTotalMs(nowMs);
    }

    public boolean sendDiscNav(String action) {
        return discNavController.sendDiscNav(action);
    }

    public boolean sendDiscNavPointer(int pointerX, int pointerY, boolean activate) {
        return discNavController.sendDiscNavPointer(pointerX, pointerY, activate);
    }

    private String videoOutputVo() {
        return surfaceCoordinator.videoOutputVo();
    }

    private String activeVideoOutputVo() {
        return surfaceCoordinator.activeVideoOutputVo();
    }

    private void syncOsdSurfaceRequirementFromMpv() {
        surfaceCoordinator.syncOsdSurfaceRequirementFromMpv();
    }

    private void setOsdSurfaceRequested(boolean requested) {
        surfaceCoordinator.setOsdSurfaceRequested(requested);
    }

    private void bindVideoOutput() {
        surfaceCoordinator.bindVideoOutput();
    }

    private void clearVideoOutput() {
        surfaceCoordinator.clearVideoOutput();
    }

    private void detachMpvSurface() {
        surfaceCoordinator.detachMpvSurface();
    }

    private void detachMpvSurface(boolean resetVideoOutput) {
        surfaceCoordinator.detachMpvSurface(resetVideoOutput);
    }

    private void applySurfaceFrameRate() {
        surfaceCoordinator.setCachedContentFrameRate(propertyDispatcher.getCachedContentFrameRate());
    }

    private void clearSurfaceFrameRate() {
        surfaceCoordinator.clearSurfaceFrameRate();
    }

    private void stopInternal(boolean resetState) {
        lifecycleCoordinator.stopInternal(resetState);
    }

    private void stopMpv(boolean markStopping) {
        lifecycleCoordinator.stopMpv(markStopping);
    }

    private void resumeMediaReplacementAfterStopTimeout() {
        if (released || mediaItem == null || !mediaReplacementCoordinator.resumeAfterTimeout()) return;
        lifecycleCoordinator.setStopping(false);
        SpiderDebug.log("mpv", "media replace resume generation=%d reason=stop-timeout player=%s", mediaReplacementCoordinator.generation(), identity(this));
        openCurrent(mediaReplacementCoordinator.generation());
    }

    private void resumeMediaReplacement(String reason) {
        mainHandler.removeCallbacks(mediaReplacementStopTimeoutRunnable);
        long generation = mediaReplacementCoordinator.generation();
        SpiderDebug.log("mpv", "media replace resume generation=%d reason=%s player=%s", generation, reason, identity(this));
        mainHandler.post(() -> openCurrent(generation));
    }

    private void releaseNativeContext(String reason) {
        synchronized (NATIVE_CONTEXT_LOCK) {
            releaseNativeContextLocked(reason);
        }
    }

    private void releaseNativeContextLocked(String reason) {
        if (!initialized) return;
        boolean ownsNativeContext = nativeContextOwner == this;
        try {
            if (ownsNativeContext && surfaceCoordinator.isAnySurfaceAttached()) surfaceCoordinator.detachMpvSurface();
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.removeObserver(this);
            MPVLib.removeLogObserver(this);
            if (ownsNativeContext) mpvDestroyCreatedContext();
        } catch (Throwable ignored) {
        } finally {
            if (ownsNativeContext) nativeContextOwner = null;
            mainHandler.removeCallbacks(mediaReplacementStopTimeoutRunnable);
            mediaReplacementCoordinator.reset();
            initialized = false;
            setCustomButtonStates("");
            cacheCoordinator.onNativeContextReleased();
            surfaceCoordinator.onNativeContextReleased();
            effectiveVo = null;
            lifecycleCoordinator.onNativeContextReleased();
        }
        SpiderDebug.log("mpv", "context released reason=%s owner=%s player=%s", reason, ownsNativeContext, identity(this));
    }

    private static String identity(MpvPlayer player) {
        return Integer.toHexString(System.identityHashCode(player));
    }

    private void seekMpv(long positionMs) {
        try {
            mpvCommand(new String[]{"seek", String.format(Locale.US, "%.3f", positionMs / SECONDS_TO_MS), "absolute+exact"});
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_UNSPECIFIED);
        }
    }

    private void loadCurrentUri() {
        lifecycleCoordinator.loadCurrentUri();
    }

    private void scheduleLoadStartRetry() {
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.postDelayed(loadStartRetryRunnable, LOAD_START_RETRY_DELAY_MS);
    }

    private void retryLoadIfNotStarted() {
        lifecycleCoordinator.retryLoadIfNotStarted();
    }

    private void updateVideoSize(String reason) {
        VideoSize newSize = videoSizeCoordinator.resolveVideoSize(videoSize, reason, trackCoordinator.getCurrentTracks());
        if (newSize == null) return;
        videoSize = newSize;
        BiConsumer<Integer, Integer> listener = videoSizeProbeListener;
        if (listener != null) {
            int width = newSize.width;
            int height = newSize.height;
            mainHandler.postAtFrontOfQueue(() -> {
                if (!released && listener == videoSizeProbeListener) listener.accept(width, height);
            });
        }
    }

    private void startStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.postDelayed(stateRefreshRunnable, STATE_REFRESH_INTERVAL_MS);
        discNavController.startRebufferSampling();
    }

    private void stopStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
        discNavController.stopRebufferSampling();
    }

    private void startMainThreadWatchdog() {
        mainThreadWatchdog.start(this);
    }

    private void stopMainThreadWatchdog() {
        mainThreadWatchdog.stop();
    }

    private long beginMpvNativeCall(String kind, String target) {
        return mainThreadWatchdog.beginNativeCall(kind, target);
    }

    private void endMpvNativeCall(long startedAtMs, String kind, String target) {
        mainThreadWatchdog.endNativeCall(startedAtMs, kind, target);
    }

    private void refreshPlaybackState() {
        if (released || mediaItem == null || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || playerError != null) return;
        hlsCoordinator.updateHlsAdBoundary(cachedPositionMs);
        updatePreloadCacheOverlay();
        if (lifecycleCoordinator.isCurrentLikelyHls()) hlsCoordinator.requestHlsPreload(cachedPositionMs);
        refreshCacheState();
        invalidateState();
        startStateRefresh();
    }

    private void refreshCacheState() {
        cacheCoordinator.refreshCacheState();
    }

    private void refreshCacheTimeline() {
        cacheCoordinator.refreshCacheTimeline();
    }

    private void validateEarlyEndFile() {
        if (released || lifecycleCoordinator.isStopping() || lifecycleCoordinator.isFileLoaded() || lifecycleCoordinator.isEofReached() || playerError != null || playbackState != Player.STATE_BUFFERING) return;
        // An idle native player is expected until the visible Surface is bound.
        if (lifecycleCoordinator.getPendingSurfaceLoadGeneration() != C.INDEX_UNSET) return;
        if (booleanProperty("idle-active", lifecycleCoordinator.isIdleActive())) {
            fail(diagnosticCoordinator.classifyLoadError(null, "idle-active=true"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        } else {
            startStateRefresh();
        }
    }

    private void resetCacheState() {
        cacheCoordinator.resetCacheState();
    }

    private void resetCacheTimelineForSeek(long targetPositionMs) {
        cacheCoordinator.resetCacheTimelineForSeek(targetPositionMs);
    }

    private void applyPreloadDiskCacheMode() {
        cacheCoordinator.applyPreloadDiskCacheMode();
    }

    private void applyCacheTimePolicy() {
        cacheCoordinator.applyCacheTimePolicy();
    }

    private void updatePreloadCacheOverlay() {
        cacheCoordinator.updatePreloadCacheOverlay();
    }

    private void restorePreloadCacheOverlay() {
        cacheCoordinator.restorePreloadCacheOverlay();
    }

    private void clearPreloadCacheOverlay() {
        cacheCoordinator.clearPreloadCacheOverlay();
    }

    private long positionMs() {
        return cachedPositionMs;
    }

    private long durationMs() {
        return cachedDurationMs > 0 ? cachedDurationMs : C.TIME_UNSET;
    }

    private long stabilizedPositionMs(long observedPositionMs) {
        long targetPositionMs = seekPositionState.targetPositionMs();
        long resolvedPositionMs = seekPositionState.resolve(
                observedPositionMs, SystemClock.elapsedRealtime());
        if (targetPositionMs != MpvSeekPositionState.NO_TARGET
                && !seekPositionState.hasTarget()) {
            PlaybackTrace.log("mpv", playbackTraceId,
                    "seek-latch action=release targetMs=%d observedMs=%d",
                    targetPositionMs, observedPositionMs);
        }
        return resolvedPositionMs;
    }

    private long bufferedPositionMs(long position, long duration) {
        PlaybackResourceClassifier.Classification classification = resourceClassification;
        boolean local = classification != null
                && classification.playerPath() == PlaybackAutoContext.PathKind.LOCAL;
        return MpvBufferedPositionPolicy.resolve(
                position,
                duration,
                cacheCoordinator.getCachedCacheDurationMs(),
                cacheCoordinator.getCachedCacheEndMs(),
                local,
                !TextUtils.isEmpty(lifecycleCoordinator.getCurrentIsoUri()));
    }

    private boolean isPlayingInternal() {
        return playbackState == Player.STATE_READY && playWhenReady && !loading;
    }

    void scheduleTrackRefresh(String reason) {
        trackCoordinator.scheduleTrackRefresh(reason);
    }

    private void cancelScheduledTrackRefresh() {
        trackCoordinator.cancelScheduledTrackRefresh();
    }

    private void refreshTracks() {
        trackCoordinator.refreshTracks();
    }

    private void refreshChapters() {
        trackCoordinator.refreshChapters();
    }

    private void handleChapterListProperty(@Nullable Object value) {
        trackCoordinator.handleChapterListProperty(value);
    }

    public String currentTrackId(int type) {
        return trackCoordinator.currentTrackId(type);
    }

    static String audioSampleMimeType(String codec) {
        return MpvAudioMimeTypes.fromCodec(codec);
    }

    private long doublePropertyMs(String property, long fallback) {
        return nativeBridge.doublePropertyMs(property, fallback);
    }

    private double doubleProperty(String property, double fallback) {
        return nativeBridge.doubleProperty(property, fallback);
    }

    private double doubleValue(@Nullable Object value, double fallback) {
        return nativeBridge.doubleValue(value, fallback);
    }

    private long doubleSecondsToMs(@Nullable Object value, long fallback) {
        return nativeBridge.doubleSecondsToMs(value, fallback);
    }

    private long longValue(@Nullable Object value, long fallback) {
        return nativeBridge.longValue(value, fallback);
    }

    private static long saturatingMultiply(long value, long multiplier) {
        return MpvNativeBridge.saturatingMultiply(value, multiplier);
    }

    private long longProperty(String property, long fallback) {
        return nativeBridge.longProperty(property, fallback);
    }

    @Nullable
    private Long nullableLongProperty(String property) {
        return nativeBridge.nullableLongProperty(property);
    }

    private int intProperty(String property, int fallback) {
        return nativeBridge.intProperty(property, fallback);
    }

    private boolean booleanProperty(String property, boolean fallback) {
        return nativeBridge.booleanProperty(property, fallback);
    }

    private String stringProperty(String property, String fallback) {
        return nativeBridge.stringProperty(property, fallback);
    }

    private void syncDiagnosticLogLevel() {
        diagnosticCoordinator.syncDiagnosticLogLevel(diagnostics);
    }

    private void resetRuntimeDiagnostics() {
        firstVideoFrameReported = false;
        newlyRenderedFirstFrame = false;
        propertyDispatcher.resetRuntimeDiagnostics();
    }

    private void resetAudioPlaybackDiagnostics() {
        trackCoordinator.resetAudioPlaybackDiagnostics();
        lifecycleCoordinator.setDtsHdFallbackSourceTrack(AudioPlaybackDiagnostics.Track.empty());
    }

    private void clearAutomaticAudioDowngrade() {
        trackCoordinator.clearAutomaticAudioDowngrade();
    }


    private void resetVideoMetadataCache() {
        propertyDispatcher.resetVideoMetadataCache();
    }

    private int cachedVideoIntProperty(String property, int fallback) {
        return propertyDispatcher.cachedVideoIntProperty(property, fallback);
    }

    private String stringValue(@Nullable Object value, String fallback) {
        return value instanceof String text && !TextUtils.isEmpty(text) ? text : fallback;
    }

    private void fail(Throwable e, int errorCode) {
        lifecycleCoordinator.fail(e, errorCode);
    }

    private void maybeRetryDtsHdAsCore(String line) {
        lifecycleCoordinator.maybeRetryDtsHdAsCore(line);
    }

    private void restoreConfiguredAudioSpdifForNewMedia() {
        lifecycleCoordinator.restoreConfiguredAudioSpdifForNewMedia();
    }

    private void applyShaderPipeline(boolean force) {
        if (!initialized) return;
        String target = lutShader == null ? "" : lutShader.getPath();
        if (!force && TextUtils.equals(appliedLutShaderPath, target)) return;
        if (!TextUtils.isEmpty(appliedLutShaderPath)) {
            String optionKey = MpvLutShader.getPreviewOptionKey(appliedLutShaderPath);
            if (!optionKey.isEmpty()) safeCommand(new String[]{"change-list", "glsl-shader-opts", "del", optionKey});
            safeCommand(new String[]{"change-list", "glsl-shaders", "remove", appliedLutShaderPath});
            SpiderDebug.log("mpv", "shader remove lut=%s", appliedLutShaderPath);
        }
        if (!TextUtils.isEmpty(target)) {
            if (lutShader != null && lutShader.isPreview()) setLutPreviewProgress(0f);
            safeCommand(new String[]{"change-list", "glsl-shaders", "append", target});
            SpiderDebug.log("mpv", "shader append lut=%s", target);
        }
        appliedLutShaderPath = target;
    }

    private void postToMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else mainHandler.post(runnable);
    }

    private boolean shouldCollectDebugDetails() {
        return MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.DEBUG_LOG, SpiderDebug.isEnabled());
    }

    private boolean mpvTryCreate(Context appContext) {
        return nativeBridge.mpvTryCreate(appContext);
    }

    private void mpvInit() {
        nativeBridge.mpvInit();
    }

    private void mpvDestroyCreatedContext() {
        nativeBridge.mpvDestroyCreatedContext();
    }

    private int mpvSetOptionString(String property, String value) {
        return nativeBridge.mpvSetOptionString(property, value);
    }

    private Integer mpvGetPropertyInt(String property) {
        return nativeBridge.mpvGetPropertyInt(property);
    }

    private Double mpvGetPropertyDouble(String property) {
        return nativeBridge.mpvGetPropertyDouble(property);
    }

    private Boolean mpvGetPropertyBoolean(String property) {
        return nativeBridge.mpvGetPropertyBoolean(property);
    }

    private String mpvGetPropertyString(String property) {
        return nativeBridge.mpvGetPropertyString(property);
    }

    private int mpvSetPropertyInt(String property, int value) {
        return nativeBridge.mpvSetPropertyInt(property, value);
    }

    private int mpvSetPropertyDouble(String property, double value) {
        return nativeBridge.mpvSetPropertyDouble(property, value);
    }

    private int mpvSetPropertyBoolean(String property, boolean value) {
        return nativeBridge.mpvSetPropertyBoolean(property, value);
    }

    private int mpvSetPropertyString(String property, String value) {
        return nativeBridge.mpvSetPropertyString(property, value);
    }

    private int mpvCommand(String[] command) {
        return nativeBridge.mpvCommand(command);
    }

    private int mpvEnqueueCommand(long requestId, String[] command) {
        return nativeBridge.mpvEnqueueCommand(requestId, command);
    }

    private int mpvObserveProperty(String property, int format) {
        return nativeBridge.mpvObserveProperty(property, format);
    }

    private void mpvAttachSurface(Surface target) {
        nativeBridge.mpvAttachSurface(target);
    }

    private void mpvDetachSurface() {
        nativeBridge.mpvDetachSurface();
    }

    private void mpvDetachOsdSurface() {
        nativeBridge.mpvDetachOsdSurface();
    }

    private int mpvEnqueueOsdSurface(long requestId, @Nullable Surface target) {
        return nativeBridge.mpvEnqueueOsdSurface(requestId, target);
    }

    private void setOption(String name, String value) {
        nativeBridge.setOption(name, value);
    }

    private void setRuntimeString(String name, String value) {
        nativeBridge.setRuntimeString(name, value);
    }

    private boolean setRuntimeStringChecked(String name, String value) {
        return nativeBridge.setRuntimeStringChecked(name, value);
    }

    private void observe(String property, int format) {
        nativeBridge.observe(property, format);
    }

    private void safeSetPropertyBoolean(String property, boolean value) {
        nativeBridge.safeSetPropertyBoolean(property, value);
    }

    private void applyTextOffset() {
        captionCoordinator.applyTextOffset();
    }

    private void applyAudioOffset() {
        captionCoordinator.applyAudioOffset();
    }

    private void applySubtitleStyle() {
        captionCoordinator.applySubtitleStyle();
    }

    private void safeSetPropertyDouble(String property, double value) {
        nativeBridge.safeSetPropertyDouble(property, value);
    }

    private void safeSetPropertyInt(String property, int value) {
        nativeBridge.safeSetPropertyInt(property, value);
    }

    private void safeSetPropertyString(String property, String value) {
        nativeBridge.safeSetPropertyString(property, value);
    }

    private void safeSetPropertyLong(String property, long value) {
        nativeBridge.safeSetPropertyLong(property, value);
    }

    private void safeCommand(String[] command) {
        nativeBridge.safeCommand(command);
    }

    private boolean enqueueMpvCommand(String... command) {
        return nativeBridge.enqueueMpvCommand(command);
    }

    private void closeContentFds() {
        uriResolver.closeContentFds();
    }

    private void closeIsoSession() {
        String isoUri = lifecycleCoordinator.getCurrentIsoUri();
        if (TextUtils.isEmpty(isoUri)) return;
        if (isoMetadataListenerSessionId > 0) {
            IsoSessionManager.removeTrackMetadataListener(isoMetadataListenerSessionId, isoTrackMetadataReadyListener);
            isoMetadataListenerSessionId = -1;
        }
        IsoSessionManager.closeUri(isoUri);
        lifecycleCoordinator.setCurrentIsoUri(null);
        discNavController.reset();
        isoTrackListDumped = false;
    }

    private void attachIsoTrackMetadataListener() {
        String isoUri = lifecycleCoordinator.getCurrentIsoUri();
        long sessionId = IsoSessionManager.parseId(isoUri);
        if (sessionId <= 0) return;
        if (isoMetadataListenerSessionId > 0) {
            IsoSessionManager.removeTrackMetadataListener(isoMetadataListenerSessionId, isoTrackMetadataReadyListener);
        }
        isoMetadataListenerSessionId = sessionId;
        IsoSessionManager.addTrackMetadataListener(sessionId, isoTrackMetadataReadyListener);
    }

    private void onIsoTrackMetadataReady() {
        long sessionId = isoMetadataListenerSessionId;
        mainHandler.post(() -> {
            String isoUri = lifecycleCoordinator.getCurrentIsoUri();
            if (released || sessionId <= 0 || sessionId != IsoSessionManager.parseId(isoUri)) return;
            SpiderDebug.log("mpv", "iso track metadata ready session=%d", sessionId);
            refreshTracks();
            invalidateState();
        });
    }

    private void logSourceDiagnostics(MediaItem item, String uri, Map<String, String> headers) {
        diagnosticCoordinator.logSourceDiagnostics(item, uri, headers);
    }

    private void copySupportAssets() throws IOException {
        MpvNativeBridge.copySupportAssets(context, config);
    }

    public record VideoTrackDiagnostics(
            String sourceCodecs,
            int dolbyVisionProfile,
            int dolbyVisionLevel,
            int sourceDolbyVisionProfile,
            int sourceDolbyVisionLevel,
            String decodedCodec,
            String decoderName,
            @Nullable ColorInfo outputColorInfo) {

        public VideoTrackDiagnostics {
            sourceCodecs = sourceCodecs == null ? "" : sourceCodecs;
            decodedCodec = decodedCodec == null ? "" : decodedCodec;
            decoderName = decoderName == null ? "" : decoderName;
        }

        public VideoTrackDiagnostics(
                String sourceCodecs,
                int dolbyVisionProfile,
                int dolbyVisionLevel,
                String decodedCodec,
                String decoderName,
                @Nullable ColorInfo outputColorInfo) {
            this(sourceCodecs, dolbyVisionProfile, dolbyVisionLevel,
                    dolbyVisionProfile, dolbyVisionLevel,
                    decodedCodec, decoderName, outputColorInfo);
        }

        public boolean hasDolbyVisionSource() {
            return sourceDolbyVisionProfile > 0;
        }

        public static VideoTrackDiagnostics empty() {
            return new VideoTrackDiagnostics("", C.INDEX_UNSET, C.INDEX_UNSET,
                    C.INDEX_UNSET, C.INDEX_UNSET, "", "", null);
        }
    }
}
