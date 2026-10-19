package com.fongmi.android.tv.player;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.mpv.MpvVulkanBackendPolicy;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.LocalProxyDebug;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;

public class PlaybackErrorHandler {

    public static final long HARD_DECODE_SWITCH_RETRY_DELAY_MS = 150;
    public static final long EXO_TUNNELING_RETRY_DELAY_MS = 150;
    public static final long EXO_DECODER_RUNTIME_RETRY_DELAY_MS = 150;
    public static final long EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS = 150;
    public static final long LOCAL_PROXY_RETRY_DELAY_MS = 1000;
    public static final int LOCAL_PROXY_MAX_RETRY = 2;
    public static final int EXTERNAL_LOCAL_PROXY_MAX_RETRY = 15;

    public interface Host {
        @Nullable
        Player getPlayer();
        @Nullable
        PlayerEngine getEngine();
        @Nullable
        PlaySpec getSpec();
        int getPlayerType();
        int getRetry();
        long getPosition();
        float getSpeed();
        boolean isRepeatOne();
        boolean isHardDecodeSwitchRetryArmed();
        void setHardDecodeSwitchRetryArmed(boolean armed);
        boolean isMpvSurfaceDirect();
        boolean isDv7NativeAttemptRequested();
        boolean isMpvSurfaceFallbackTried();
        void setMpvSurfaceFallbackTried(boolean tried);
        void setMpvAutoOutputEvaluated(boolean evaluated);
        void incrementMpvOutputEvaluationSeq();
        boolean isMpvVulkanFallbackTried();
        void setMpvVulkanFallbackTried(boolean tried);
        boolean isMpvAutoVulkanPinnedForItem();
        void setMpvAutoVulkanPinnedForItem(boolean pinned);
        void setMpvAutoVulkanDisabledForItem(boolean disabled);
        int getVideoWidth();
        int getVideoHeight();
        PlaybackRoute.Resolution getEffectivePlaybackRoute();
        PlaybackTrace getPlaybackTrace();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action);
        boolean handleExoDecoderResourcesReclaimed(PlaybackException e);
        void onRtspPlaybackError(PlaybackAutoContext.SessionToken session);
        void publishTelemetryError();
        boolean recoverMpvHlsVariantError();
        void finishPlaybackProfileAbSession(String reason, long nowElapsedMs);
        void onReloadCallback(String message);
        void onErrorCallback(String message);
        void onPrepareCallback();
        void setSpeed(float speed);
        void setRepeatOne(boolean repeat);
        void setMediaItem();
        void applySubtitleStyle();
        void rebuildPlayer(boolean reuseSurface);
        void scheduleMpvAutoOutputEvaluation();
        int nextPrepareSeq();
        int getPrepareSeq();
        void setPlayWhenReady(boolean playWhenReady);
        void setInitTrack(boolean initTrack);
        void resetPlaybackTimeout();
        void schedulePlaybackTimeout(long timeoutMs);
        boolean rebuildAndRestartMpv(@Nullable Boolean surfaceDirect, String reason);
        PlayerEngine buildEngine(int playerType, int decode);
        void switchPlayer(int type, boolean persist);
        void setEngineAndPlayer(PlayerEngine engine, Player player, boolean notifyRebuild);
        String formatCauseChain(Throwable error);
        String getDebugSpec();
    }

    private final Host host;
    private int localProxyRetry;

    public PlaybackErrorHandler(@NonNull Host host) {
        this.host = host;
    }

    public void resetLocalProxyRetry() {
        this.localProxyRetry = 0;
    }

    public void handlePlayerError(@NonNull PlaybackException e) {
        host.resetPlaybackTimeout();
        if (host.handleExoDecoderResourcesReclaimed(e)) return;
        host.onRtspPlaybackError(host.getPlaybackAutoSession());
        host.publishTelemetryError();
        if (host.recoverMpvHlsVariantError()) return;
        if (retryMpvDv7P81Failure(e)) return;
        if (retryMpvSurfaceDirectFailure(e)) return;
        if (retryMpvVulkanBackendFailure(e)) return;
        if (retryMpvAutoVulkanFailure(e)) return;
        PlaybackRoute.Resolution route = host.getEffectivePlaybackRoute();
        PlaybackErrorClassifier.Failure failure = PlaybackErrorClassifier.classify(e, route);
        PlayerEngine engine = host.getEngine();
        PlayerEngine.ErrorAction action = engine != null ? engine.handleError(e) : PlayerEngine.ErrorAction.FATAL;
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("playback-error", trace.current(), "%s action=%s player=%d decode=%d", failure.logSummary(), action, host.getPlayerType(), engine == null ? -1 : engine.getDecode());
        }
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("player", "error code=%d errorType=%s action=%s retry=%d causeTypes=%s", e.errorCode, e.getClass().getSimpleName(), action, host.getRetry(), host.formatCauseChain(e));
        }
        PlaySpec spec = host.getSpec();
        LocalProxyDebug.dumpIfLocalFailure(spec == null ? null : spec.getUrl(), e);
        boolean decoderRuntimeObserved = action == PlayerEngine.ErrorAction.DECODE
                && engine instanceof ExoPlayerEngine exo
                && exo.observeDecoderRuntimeFailure(e);
        if (action == PlayerEngine.ErrorAction.DECODE && retryExoTunnelingFailure(e)) return;
        if (decoderRuntimeObserved && retryExoDecoderRuntimeFailure(e)) return;
        if (action == PlayerEngine.ErrorAction.DECODE && retryHardDecodeSwitch(e)) return;
        if (action == PlayerEngine.ErrorAction.FATAL) {
            if (retryLocalProxy(e)) return;
        }
        if (action == PlayerEngine.ErrorAction.RELOAD) {
            host.finishPlaybackProfileAbSession("player-error", SystemClock.elapsedRealtime());
            host.onReloadCallback(getPlaybackErrorMessage(failure));
            return;
        }
        if (action == PlayerEngine.ErrorAction.RECOVERED) {
            return;
        }
        host.finishPlaybackProfileAbSession("player-error", SystemClock.elapsedRealtime());
        host.onErrorCallback(getPlaybackErrorMessage(failure));
    }

    public void handlePlaybackTimeout() {
        if (retryMpvDv7P81FirstFrameTimeout()) return;
        if (retryMpvVulkanBackendTimeout()) return;
        if (retryMpvAutoVulkanToOpenGl("auto-vulkan-first-frame-timeout")) return;
        if (retryExoDv7FirstFrameTimeout()) return;
        host.onErrorCallback(ResUtil.getString(R.string.error_play_timeout));
    }

    private boolean retryMpvDv7P81Failure(PlaybackException error) {
        PlayerEngine engine = host.getEngine();
        if (error == null || !(engine instanceof MpvPlayerEngine mpv)
                || !mpv.isDv7P81Active()) return false;
        String message = error.getMessage();
        boolean conversionOrDecodeFailure = error.errorCode
                == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || message != null && (message.startsWith(MpvPlayer.ERROR_DECODE_FAILED)
                || message.startsWith(MpvPlayer.ERROR_INVALID_MEDIA_DATA));
        if (!conversionOrDecodeFailure || !mpv.prepareDv7P81Hdr10Fallback()) {
            return false;
        }
        return host.rebuildAndRestartMpv(null, "dv7-p81-hdr10-fallback");
    }

    private boolean retryMpvDv7P81FirstFrameTimeout() {
        PlayerEngine engine = host.getEngine();
        Player player = host.getPlayer();
        PlaybackTrace trace = host.getPlaybackTrace();
        if (!(engine instanceof MpvPlayerEngine mpv)
                || player == null
                || !mpv.isDv7P81Active()
                || (trace != null && trace.hasStage(PlaybackTrace.Stage.FIRST_FRAME))
                || !mpv.prepareDv7P81Hdr10Fallback()) {
            return false;
        }
        long position = Math.max(0, player.getCurrentPosition());
        if (trace != null) {
            PlaybackTrace.log("mpv-dv", trace.current(),
                    "P8.1 produced no first frame before startup timeout; retry HDR10 position=%d",
                    position);
        }
        return host.rebuildAndRestartMpv(null, "dv7-p81-first-frame-timeout");
    }

    private boolean retryMpvSurfaceDirectFailure(PlaybackException error) {
        if (!host.isMpvSurfaceDirect() || host.isMpvSurfaceFallbackTried() || error == null) return false;
        if (host.isDv7NativeAttemptRequested()) return false;
        String message = error.getMessage();
        boolean outputFailure = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || message != null && (message.startsWith(MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED) || message.startsWith(MpvPlayer.ERROR_DECODE_FAILED));
        if (!outputFailure) return false;
        host.setMpvSurfaceFallbackTried(true);
        host.setMpvAutoOutputEvaluated(true);
        host.incrementMpvOutputEvaluationSeq();
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("mpv-output", trace.current(), "surface direct failed; fallback gpu once code=%d message=%s", error.errorCode, message);
        }
        return host.rebuildAndRestartMpv(false, "surface-direct-failure");
    }

    private boolean retryMpvVulkanBackendFailure(PlaybackException error) {
        PlayerEngine engine = host.getEngine();
        if (host.isMpvVulkanFallbackTried() || error == null
                || !(engine instanceof MpvPlayerEngine mpv)
                || !mpv.shouldFallbackVulkanToStable()) return false;
        String message = error.getMessage();
        boolean outputFailure = error.errorCode
                == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                || message != null
                && message.startsWith(MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED);
        if (!outputFailure) return false;
        return retryMpvVulkanBackendToStable(mpv, "direct-output-failure");
    }

    private boolean retryMpvVulkanBackendTimeout() {
        PlayerEngine engine = host.getEngine();
        if (host.isMpvVulkanFallbackTried() || !(engine instanceof MpvPlayerEngine mpv)
                || !mpv.shouldFallbackVulkanToStable()) return false;
        VideoSize size = mpv.getVideoSizeSnapshot();
        if ((size == null || size.width <= 0 || size.height <= 0)
                && host.getVideoWidth() <= 0 && host.getVideoHeight() <= 0) return false;
        return retryMpvVulkanBackendToStable(mpv, "direct-first-frame-timeout");
    }

    private boolean retryMpvVulkanBackendToStable(MpvPlayerEngine mpv, String reason) {
        host.setMpvVulkanFallbackTried(true);
        MpvVulkanBackendPolicy.rememberDirectFailure();
        mpv.setVulkanBackendOverride(MpvVulkanBackendPolicy.STABLE);
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("mpv-vulkan", trace.current(),
                    "backend direct failed; fallback stable once reason=%s", reason);
        }
        return host.rebuildAndRestartMpv(false, reason);
    }

    private boolean retryMpvAutoVulkanFailure(PlaybackException error) {
        PlayerEngine engine = host.getEngine();
        if (error == null || !host.isMpvAutoVulkanPinnedForItem()
                || !(engine instanceof MpvPlayerEngine mpv)
                || !mpv.isVulkanRenderer()) return false;
        String message = error.getMessage();
        boolean outputFailure = error.errorCode
                == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                || message != null
                && message.startsWith(MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED);
        return outputFailure
                && retryMpvAutoVulkanToOpenGl("auto-vulkan-output-failure");
    }

    private boolean retryMpvAutoVulkanToOpenGl(String reason) {
        PlayerEngine engine = host.getEngine();
        if (!host.isMpvAutoVulkanPinnedForItem()
                || !(engine instanceof MpvPlayerEngine mpv)
                || !mpv.isVulkanRenderer()) return false;
        host.setMpvAutoVulkanPinnedForItem(false);
        host.setMpvAutoVulkanDisabledForItem(true);
        mpv.setVulkanRenderOverride(false);
        mpv.setVulkanBackendOverride(null);
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("mpv-vulkan", trace.current(),
                    "automatic DV GPU Vulkan failed; fallback OpenGL once reason=%s",
                    reason);
        }
        return host.rebuildAndRestartMpv(false, reason);
    }

    private boolean retryHardDecodeSwitch(PlaybackException e) {
        PlayerEngine engine = host.getEngine();
        Player player = host.getPlayer();
        PlaySpec spec = host.getSpec();
        if (!host.isHardDecodeSwitchRetryArmed() || engine == null || player == null || spec == null || !engine.isHard()) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        host.setHardDecodeSwitchRetryArmed(false);
        int seq = host.nextPrepareSeq();
        PlaySpec target = spec;
        long position = Math.max(0, host.getPosition());
        float speed = host.getSpeed();
        boolean repeat = host.isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        host.resetPlaybackTimeout();
        engine.release();
        PlayerEngine newEngine = host.buildEngine(host.getPlayerType(), PlayerEngine.HARD);
        Player newPlayer = newEngine.getPlayer();
        host.setEngineAndPlayer(newEngine, newPlayer, true);
        host.setPlayWhenReady(wasPlayWhenReady);
        host.setInitTrack(false);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry delay=%d position=%d errorType=%s", HARD_DECODE_SWITCH_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != host.getPrepareSeq() || host.getSpec() != target || host.getEngine() == null || host.getPlayer() == null || !host.getEngine().isHard()) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry start position=%d", position);
            host.setInitTrack(false);
            host.applySubtitleStyle();
            host.getEngine().start(target.checkUa(), position, wasPlayWhenReady);
            host.scheduleMpvAutoOutputEvaluation();
            if (speed != 1f) host.setSpeed(speed);
            host.setRepeatOne(repeat);
            host.schedulePlaybackTimeout(Constant.TIMEOUT_PLAY);
            host.onPrepareCallback();
        }, HARD_DECODE_SWITCH_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoTunnelingFailure(PlaybackException e) {
        PlayerEngine engine = host.getEngine();
        Player player = host.getPlayer();
        PlaySpec spec = host.getSpec();
        if (!(engine instanceof ExoPlayerEngine exo) || player == null || spec == null) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        if (!exo.disableTunnelingForSession()) return false;
        int seq = host.nextPrepareSeq();
        PlaySpec target = spec;
        long position = Math.max(0, host.getPosition());
        float speed = host.getSpeed();
        boolean repeat = host.isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        host.resetPlaybackTimeout();
        host.rebuildPlayer(true);
        host.setPlayWhenReady(wasPlayWhenReady);
        host.setInitTrack(false);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback scheduled delay=%d position=%d errorType=%s", EXO_TUNNELING_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != host.getPrepareSeq() || host.getSpec() != target || host.getEngine() != exo || host.getPlayer() == null) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback start position=%d", position);
            host.applySubtitleStyle();
            host.getEngine().start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) host.setSpeed(speed);
            host.setRepeatOne(repeat);
            host.schedulePlaybackTimeout(Constant.TIMEOUT_PLAY);
            host.onPrepareCallback();
        }, EXO_TUNNELING_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDecoderRuntimeFailure(PlaybackException e) {
        PlayerEngine engine = host.getEngine();
        Player player = host.getPlayer();
        PlaySpec spec = host.getSpec();
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null) {
            return false;
        }
        boolean dolbyVisionFallback = exo.isDolbyVisionP81RuntimeFailurePending();
        if (!dolbyVisionFallback
                && !host.isExperimentAllowed(PlaybackExperimentPolicy.Action.EXO_DECODER_RUNTIME_REBUILD)) {
            return false;
        }
        if (!exo.prepareDecoderRuntimeFallback()) {
            return false;
        }
        host.setHardDecodeSwitchRetryArmed(false);
        int seq = host.nextPrepareSeq();
        PlaySpec target = spec;
        long position = Math.max(0, host.getPosition());
        float speed = host.getSpeed();
        boolean repeat = host.isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        host.resetPlaybackTimeout();
        host.rebuildPlayer(true);
        host.setPlayWhenReady(wasPlayWhenReady);
        host.setInitTrack(false);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-decoder-profile",
                    "action=retry-scheduled delay=%d position=%d errorType=%s",
                    EXO_DECODER_RUNTIME_RETRY_DELAY_MS,
                    position,
                    e.getClass().getSimpleName());
        }
        App.post(() -> {
            if (seq != host.getPrepareSeq() || host.getSpec() != target || host.getEngine() != exo || host.getPlayer() == null) return;
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-decoder-profile",
                        "action=retry-start position=%d",
                        position);
            }
            host.applySubtitleStyle();
            host.getEngine().start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) host.setSpeed(speed);
            host.setRepeatOne(repeat);
            host.schedulePlaybackTimeout(Constant.TIMEOUT_PLAY);
            host.onPrepareCallback();
        }, EXO_DECODER_RUNTIME_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDv7FirstFrameTimeout() {
        PlayerEngine engine = host.getEngine();
        Player player = host.getPlayer();
        PlaySpec spec = host.getSpec();
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null
                || !exo.prepareDv7Hdr10FallbackForFirstFrameTimeout()) {
            return false;
        }
        int seq = host.nextPrepareSeq();
        PlaySpec target = spec;
        long position = Math.max(0, player.getCurrentPosition());
        float speed = host.getSpeed();
        boolean repeat = host.isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        host.resetPlaybackTimeout();
        host.rebuildPlayer(true);
        host.setPlayWhenReady(wasPlayWhenReady);
        host.setInitTrack(false);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-dv",
                    "action=first-frame-timeout-fallback-scheduled delay=%d position=%d",
                    EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS,
                    position);
        }
        App.post(() -> {
            if (seq != host.getPrepareSeq() || host.getSpec() != target || host.getEngine() != exo || host.getPlayer() == null) return;
            host.applySubtitleStyle();
            host.getEngine().start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) host.setSpeed(speed);
            host.setRepeatOne(repeat);
            host.schedulePlaybackTimeout(Constant.TIMEOUT_PLAY);
            host.onPrepareCallback();
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-dv",
                        "action=first-frame-timeout-fallback-start position=%d",
                        position);
            }
        }, EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS);
        return true;
    }

    private boolean retryLocalProxy(PlaybackException e) {
        PlaySpec spec = host.getSpec();
        if (spec == null || !LocalProxyDebug.isLocalProxyUrl(spec.getUrl())) return false;
        PlaybackRoute.Resolution route = host.getEffectivePlaybackRoute();
        boolean isExternalLoopback = route != null && route.route() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY;
        if (!isLocalProxyRetryable(e, isExternalLoopback)) return false;
        int maxRetry = isExternalLoopback ? EXTERNAL_LOCAL_PROXY_MAX_RETRY : LOCAL_PROXY_MAX_RETRY;
        if (++localProxyRetry > maxRetry) return false;
        int attempt = localProxyRetry;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry schedule attempt=%d/%d delay=%d spec=%s", attempt, maxRetry, LOCAL_PROXY_RETRY_DELAY_MS, host.getDebugSpec());
        host.resetPlaybackTimeout();
        App.post(() -> {
            if (host.getSpec() == null || attempt != localProxyRetry) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry start attempt=%d/%d spec=%s", attempt, maxRetry, host.getDebugSpec());
            host.setMediaItem();
        }, LOCAL_PROXY_RETRY_DELAY_MS);
        return true;
    }


    private boolean isLocalProxyRetryable(PlaybackException e, boolean isExternalLoopback) {
        if (LocalProxyDebug.isConnectionRefused(e)) return true;
        if (isExternalLoopback) {
            if (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    || e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                return true;
            }
            return LocalProxyDebug.isLocalConnectOrTimeout(e);
        }
        return false;
    }

    private String getPlaybackErrorMessage(PlaybackErrorClassifier.Failure failure) {
        return switch (failure.stage()) {
            case LOCAL_ENDPOINT -> switch (failure.route().owner()) {
                case APP_MAIN_SERVER, APP_HLS_PROXY -> ResUtil.getString(R.string.error_play_stage_app_local);
                default -> ResUtil.getString(R.string.error_play_stage_external_local);
            };
            case NETWORK_IO -> PlaybackRouteCapabilities.resolve(failure.route()).externalUpstreamOpaque()
                    ? ResUtil.getString(R.string.error_play_stage_external_supply)
                    : ResUtil.getString(R.string.error_play_stage_network);
            case MEDIA_PARSING -> ResUtil.getString(R.string.error_play_stage_media);
            case DECODER -> ResUtil.getString(R.string.error_play_stage_decoder);
            case OUTPUT -> isAudioOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_audio_output)
                    : isVideoOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_video_output)
                    : ResUtil.getString(R.string.error_play_stage_output);
            case DRM -> ResUtil.getString(R.string.error_play_stage_drm);
            case UNKNOWN -> ResUtil.getString(R.string.error_play_stage_unknown);
        };
    }

    private boolean isAudioOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED);
    }

    private boolean isVideoOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)
                || "mpv-video-output-marker".equals(failure.evidence());
    }

    private boolean hasErrorCode(PlaybackErrorClassifier.Failure failure, int errorCode) {
        return PlaybackException.getErrorCodeName(errorCode).equals(failure.errorCode());
    }
}
