package com.fongmi.android.tv.player.mpv;

import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTelemetryCoordinator;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.codec.CodecCapabilityInspector;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;

import is.xyz.mpv.MPVLib;

import java.util.List;

/**
 * Coordinates MPV output backend choices (SurfaceDirect vs. GPU OpenGL vs. GPU Vulkan),
 * Dolby Vision Profile 7/8 handling, and output fallback transitions.
 */
public final class MpvOutputCoordinator {

    private static final int MPV_AUTO_OUTPUT_PROBE_INTERVAL_MS = 100;
    private static final int MPV_AUTO_OUTPUT_PROBE_MAX_ATTEMPTS = 15;

    public interface Host {
        MpvPlayerEngine getMpvEngine();
        Player getPlayer();
        PlaySpec getSpec();
        int getVideoWidth();
        int getVideoHeight();
        Tracks getCurrentTracks();
        Format getVideoFormat();
        PlayerEngine.VideoPlaybackDetails getVideoPlaybackDetails();
        boolean isHardDecode();
        boolean isManagedReload();
        boolean isSurfaceDirect();
        String getKey();
        PlaybackTrace getPlaybackTrace();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        PlaybackTelemetryCoordinator getTelemetryCoordinator();
        boolean rebuildAndRestartMpv(Boolean surfaceDirect, String reason);
        void rebuildPlayer();
        void onPlayerOutputPending();
        void onPlayerOutputReady();
    }

    private final Host host;

    private boolean mpvAutoOutputEvaluated;
    private boolean mpvAutoOutputFrameReady;
    private boolean mpvAutoOutputEvaluationScheduled;
    private boolean mpvExplicitSubtitlePreference;
    private boolean mpvAutoGpuPinnedForSession;
    private boolean mpvAutoVulkanPinnedForItem;
    private boolean mpvAutoVulkanDisabledForItem;
    private boolean mpvSurfaceFallbackTried;
    private boolean mpvVulkanFallbackTried;
    private int mpvOutputEvaluationSeq;
    private int mpvAutoOutputProbeAttempts;

    public MpvOutputCoordinator(Host host) {
        this.host = host;
    }

    public boolean isEvaluated() {
        return mpvAutoOutputEvaluated;
    }

    public void setEvaluated(boolean evaluated) {
        this.mpvAutoOutputEvaluated = evaluated;
    }

    public boolean isFrameReady() {
        return mpvAutoOutputFrameReady;
    }

    public void setFrameReady(boolean frameReady) {
        this.mpvAutoOutputFrameReady = frameReady;
    }

    public boolean isSurfaceFallbackTried() {
        return mpvSurfaceFallbackTried;
    }

    public void setSurfaceFallbackTried(boolean tried) {
        this.mpvSurfaceFallbackTried = tried;
    }

    public boolean isVulkanFallbackTried() {
        return mpvVulkanFallbackTried;
    }

    public void setVulkanFallbackTried(boolean tried) {
        this.mpvVulkanFallbackTried = tried;
    }

    public void resetRuntime() {
        resetEvaluationState();
        mpvSurfaceFallbackTried = false;
        mpvAutoGpuPinnedForSession = false;
        mpvAutoVulkanPinnedForItem = false;
        mpvAutoVulkanDisabledForItem = false;
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv != null) {
            mpv.setSurfaceDirectOverride(null);
            mpv.setVulkanRenderOverride(null);
        }
    }

    public void resetEvaluationState() {
        mpvAutoOutputEvaluated = false;
        mpvAutoOutputFrameReady = false;
        mpvAutoOutputEvaluationScheduled = false;
        mpvAutoOutputProbeAttempts = 0;
        mpvVulkanFallbackTried = false;
        mpvOutputEvaluationSeq++;
    }

    public void release() {
        resetRuntime();
    }

    public void prepareForNewItem() {
        resetEvaluationState();
        mpvSurfaceFallbackTried = false;
        List<Track> persistedTracks = Track.find(host.getKey());
        Track persistedSubtitle = findRequestedSubtitle(persistedTracks);
        mpvExplicitSubtitlePreference = persistedSubtitle != null;
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null) return;
        mpv.prepareSubtitleForNewItem(persistedSubtitle);
        boolean dv7HandlingChanged = mpv.resetDv7HandlingForNewItem();
        boolean dv8HandlingChanged = mpv.resetDv8HandlingForNewItem();
        boolean clearAutoVulkanRenderer = mpvAutoVulkanPinnedForItem && !mpv.isDv7FelOutputEnabled();
        mpvAutoVulkanPinnedForItem = false;
        mpvAutoVulkanDisabledForItem = false;
        mpv.setVulkanRenderOverride(null);
        if (MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO) {
            host.onPlayerOutputPending();
        }
        mpv.setSurfaceDirectOverride(null);
        boolean autoDirectEligible = MpvAutoOutputPolicy.canStartSurfaceDirect(
                host.isHardDecode(),
                Util.isLeanback(),
                MpvPerformanceSetting.isInterpolation(),
                MpvConfigStore.hasGpuVideoProcessing());
        if (mpvAutoGpuPinnedForSession && MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO) {
            autoDirectEligible = false;
        }
        boolean shouldStartDirect = MpvPerformanceSetting.shouldUseSurfaceDirect(
                autoDirectEligible, Util.isLeanback(), host.isHardDecode());
        if (mpv.isConfiguredSurfaceDirect() == shouldStartDirect
                && !clearAutoVulkanRenderer && !dv7HandlingChanged && !dv8HandlingChanged) return;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("mpv-output", "prepare new item rebuild currentDirect=%s desiredDirect=%s clearAutoVulkan=%s mode=%s",
                    mpv.isSurfaceDirect(), shouldStartDirect, clearAutoVulkanRenderer, MpvPerformanceSetting.getOutputModeText());
        }
        mpv.setSurfaceDirectOverride(shouldStartDirect);
        host.rebuildPlayer();
    }

    public void scheduleAutoOutputEvaluation() {
        if (!shouldEvaluateOutput()) return;
        if (mpvAutoOutputEvaluated || mpvAutoOutputEvaluationScheduled) return;
        mpvAutoOutputEvaluationScheduled = true;
        int seq = ++mpvOutputEvaluationSeq;
        App.post(() -> {
            if (seq != mpvOutputEvaluationSeq) return;
            mpvAutoOutputEvaluationScheduled = false;
            if (host.isManagedReload()) {
                scheduleAutoOutputEvaluation();
                return;
            }
            mpvAutoOutputProbeAttempts++;
            boolean evaluated = evaluateAutoOutput();
            if (!evaluated && !mpvAutoOutputEvaluated && mpvAutoOutputProbeAttempts < MPV_AUTO_OUTPUT_PROBE_MAX_ATTEMPTS) {
                scheduleAutoOutputEvaluation();
            } else if (!evaluated && SpiderDebug.isEnabled()) {
                SpiderDebug.log("mpv-output", "auto probe exhausted attempts=%d size=%dx%d tracksEmpty=%s",
                        mpvAutoOutputProbeAttempts, host.getVideoWidth(), host.getVideoHeight(),
                        host.getCurrentTracks() == null || host.getCurrentTracks().isEmpty());
            }
        }, MPV_AUTO_OUTPUT_PROBE_INTERVAL_MS);
    }

    public boolean evaluateAutoOutput() {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || mpvAutoOutputEvaluated) return true;
        if (host.isManagedReload()) return false;
        if (mpv.isDv7FelRequested() && !mpv.isNativeOutputSelectionReady()) return false;
        prepareFelOutput(mpv);
        if (mpv.isDv7FelOutputEnabled()) {
            mpvAutoOutputEvaluated = true;
            return true;
        }
        Tracks tracks = host.getCurrentTracks();
        boolean tracksReady = tracks != null && !tracks.isEmpty();
        if (!tracksReady) return false;
        boolean automaticOutput = MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO;
        Format format = tracksReady ? host.getVideoFormat() : null;
        PlayerEngine.VideoPlaybackDetails videoDetails = host.getVideoPlaybackDetails();
        boolean dolbyVision = videoDetails != null && videoDetails.hasDolbyVisionSource();
        if (!automaticOutput && (!mpv.isHard() || !dolbyVision || videoDetails.dolbyVisionProfile() != 8)) {
            mpvAutoOutputEvaluated = true;
            return true;
        }
        if (!dolbyVision && (format == null || !tracks.containsType(C.TRACK_TYPE_VIDEO))) {
            return false;
        }
        VideoSize probedSize = mpv.getVideoSizeSnapshot();
        int width = format != null && format.width > 0 ? format.width : probedSize.width > 0 ? probedSize.width : host.getVideoWidth();
        int height = format != null && format.height > 0 ? format.height : probedSize.height > 0 ? probedSize.height : host.getVideoHeight();
        if (width <= 0 || height <= 0) return false;
        boolean externalSubtitleActive = host.getSpec() != null && host.getSpec().getSubs() != null && !host.getSpec().getSubs().isEmpty();
        boolean earlyEvaluation = false;
        boolean subtitleActive = externalSubtitleActive || mpvExplicitSubtitlePreference;
        boolean lutOrFilterActive = MpvPerformanceSetting.isInterpolation();
        boolean customGpuProcessing = MpvConfigStore.hasGpuVideoProcessing();
        boolean dv7Hdr10FallbackEnabled = dolbyVision
                && videoDetails.dolbyVisionProfile() == 7
                && mpv.isDv7Hdr10Active();
        MpvAutoOutputPolicy.DolbyVisionSupport dolbyVisionSupport = dolbyVision
                ? CodecCapabilityInspector.dolbyVisionSupport(App.get(), videoDetails, format, width, height)
                : MpvAutoOutputPolicy.DolbyVisionSupport.UNKNOWN;
        MpvAutoOutputPolicy.DolbyVisionSupport profile81Support =
                dolbyVision && videoDetails.dolbyVisionProfile() == 7
                        && PlaybackPerformanceSetting.getMpvDv7HandlingMode() == PlaybackPerformanceSetting.DV7_HANDLING_P81
                        ? CodecCapabilityInspector.dolbyVisionProfileSupport(
                        App.get(), 8, videoDetails.dolbyVisionLevel(),
                        videoDetails.sourceCodecs(), format, width, height)
                        : MpvAutoOutputPolicy.DolbyVisionSupport.UNKNOWN;
        boolean dv7HandlingChanged = dolbyVision
                && videoDetails.dolbyVisionProfile() == 7
                && mpv.updateDv7Handling(dolbyVisionSupport, profile81Support);
        MpvAutoOutputPolicy.DolbyVisionSupport hevcHdr10Support =
                dolbyVision && videoDetails.dolbyVisionProfile() == 8
                        ? CodecCapabilityInspector.hevcHdr10Support(App.get(), format, width, height)
                        : MpvAutoOutputPolicy.DolbyVisionSupport.UNKNOWN;
        boolean dv8HandlingChanged = dolbyVision
                && videoDetails.dolbyVisionProfile() == 8
                && mpv.updateDv8Handling(dolbyVisionSupport, hevcHdr10Support);
        if (!automaticOutput) {
            mpvAutoOutputEvaluated = true;
            if (dv8HandlingChanged) {
                if (SpiderDebug.isEnabled()) {
                    SpiderDebug.log("mpv-output", "P8 compatibility handling=%s dvSupport=%s hdr10Support=%s explicitOutput=%d",
                            mpv.getDv8HandlingOption(), dolbyVisionSupport, hevcHdr10Support, MpvPerformanceSetting.getOutputMode());
                }
                host.rebuildAndRestartMpv(null, "compat-dv8-" + mpv.getDv8HandlingOption());
            }
            return true;
        }
        dv7Hdr10FallbackEnabled = dolbyVision
                && videoDetails.dolbyVisionProfile() == 7
                && mpv.isDv7Hdr10Active();
        MpvAutoOutputPolicy.Decision decision = MpvAutoOutputPolicy.evaluate(
                width, height, host.isHardDecode(),
                Util.isLeanback(), lutOrFilterActive, customGpuProcessing,
                dolbyVisionSupport,
                dolbyVision ? videoDetails.dolbyVisionProfile() : C.INDEX_UNSET,
                dv7Hdr10FallbackEnabled,
                hevcHdr10Support);
        decision = MpvAutoOutputPolicy.afterSurfaceFailure(decision, mpvSurfaceFallbackTried);
        decision = MpvAutoOutputPolicy.forFelReconstruction(decision, mpv.isDv7FelOutputEnabled());
        int dolbyVisionProfile = dolbyVision ? videoDetails.dolbyVisionProfile() : C.INDEX_UNSET;
        boolean currentlyVulkan = mpv.isVulkanRenderer();
        MpvAutoRenderPolicy.Decision renderDecision = MpvAutoRenderPolicy.evaluate(
                PlaybackPerformanceSetting.isAuto(PlayerSetting.MPV, PlaybackPerformanceCatalog.MPV_RENDER),
                host.isHardDecode(), dolbyVisionProfile, dolbyVisionSupport,
                MPVLib.isBundledVulkanEnabled(App.get()),
                MPVLib.isDeviceVulkan13Capable(App.get()),
                currentlyVulkan, mpvAutoVulkanDisabledForItem,
                mpv.isDv7FelOutputEnabled());
        boolean enableAutoVulkan = renderDecision.action() == MpvAutoRenderPolicy.Action.ENABLE_VULKAN;
        if (enableAutoVulkan) {
            mpvAutoVulkanPinnedForItem = true;
            mpv.setVulkanRenderOverride(true);
        }
        if (dolbyVision && decision.reason().startsWith("dolby-vision-hw-")) {
            mpvAutoGpuPinnedForSession = true;
        }
        mpvAutoOutputEvaluated = true;
        boolean currentlyDirect = host.isSurfaceDirect();
        MpvAutoOutputPolicy.Transition transition = MpvAutoOutputPolicy.transition(decision.eligible(), currentlyDirect);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("mpv-output", "auto decision eligible=%s transition=%s reason=%s renderAction=%s renderReason=%s size=%dx%d tracksReady=%s early=%s subtitle=%s lutOrFilter=%s customGpu=%s dvProfile=%d dvSupport=%s direct=%s gpuPinned=%s autoVulkan=%s attempts=%d",
                    decision.eligible(), transition, decision.reason(), renderDecision.action(), renderDecision.reason(),
                    width, height, tracksReady, earlyEvaluation, subtitleActive, lutOrFilterActive, customGpuProcessing,
                    dolbyVisionProfile, dolbyVisionSupport, currentlyDirect, mpvAutoGpuPinnedForSession, mpvAutoVulkanPinnedForItem, mpvAutoOutputProbeAttempts);
        }
        boolean transitionRequested = dv7HandlingChanged || dv8HandlingChanged || enableAutoVulkan
                || transition == MpvAutoOutputPolicy.Transition.ENTER_SURFACE_DIRECT
                || transition == MpvAutoOutputPolicy.Transition.LEAVE_SURFACE_DIRECT;
        boolean requestAccepted = true;
        if (dv7HandlingChanged || dv8HandlingChanged) {
            Boolean outputOverride = enableAutoVulkan
                    || transition == MpvAutoOutputPolicy.Transition.LEAVE_SURFACE_DIRECT
                    ? Boolean.FALSE
                    : transition == MpvAutoOutputPolicy.Transition.ENTER_SURFACE_DIRECT
                    ? Boolean.TRUE : null;
            String reason = dv7HandlingChanged
                    ? "auto-dv7-" + mpv.getDv7HandlingOption()
                    : "auto-dv8-" + mpv.getDv8HandlingOption();
            requestAccepted = host.rebuildAndRestartMpv(outputOverride, reason);
        } else if (enableAutoVulkan) {
            requestAccepted = host.rebuildAndRestartMpv(false, "auto-" + renderDecision.reason());
        } else if (transition == MpvAutoOutputPolicy.Transition.ENTER_SURFACE_DIRECT) {
            requestAccepted = host.rebuildAndRestartMpv(true, "auto-" + decision.reason());
        } else if (transition == MpvAutoOutputPolicy.Transition.LEAVE_SURFACE_DIRECT) {
            requestAccepted = host.rebuildAndRestartMpv(false, "auto-" + decision.reason());
        }
        String oldOutput = currentlyDirect ? "surface-direct" : currentlyVulkan ? "gpu-vulkan" : "gpu-opengl";
        String targetOutput = enableAutoVulkan ? "gpu-vulkan" : decision.eligible() ? "surface-direct" : "gpu";
        PlaybackTelemetry.DecisionOutcome telemetryOutcome = transitionRequested
                ? requestAccepted ? PlaybackTelemetry.DecisionOutcome.REQUESTED : PlaybackTelemetry.DecisionOutcome.FAILED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        host.getTelemetryCoordinator().publishDecision(host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_OUTPUT,
                        telemetryOutcome,
                        oldOutput,
                        targetOutput,
                        transitionRequested && requestAccepted ? targetOutput : oldOutput,
                        enableAutoVulkan ? renderDecision.reason() : decision.reason(),
                        transitionRequested ? requestAccepted ? "none" : "rebuild-rejected" : "no-transition",
                        List.of(
                                PlaybackTelemetry.DecisionInput.number("width", width, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("height", height, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("hard_decode", host.isHardDecode(), PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("leanback", Util.isLeanback(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("tracks_ready", tracksReady, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("early_evaluation", earlyEvaluation, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("subtitle_active", subtitleActive, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("lut_or_filter", lutOrFilterActive, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("custom_gpu", customGpuProcessing, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("currently_direct", currentlyDirect, PlaybackAutoContext.ValueSource.NATIVE_RUNTIME, PlaybackAutoContext.Confidence.MEDIUM),
                                PlaybackTelemetry.DecisionInput.bool("eligible", decision.eligible(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("probe_attempts", mpvAutoOutputProbeAttempts, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                SystemClock.elapsedRealtime());
        if (!transitionRequested || !requestAccepted) {
            host.onPlayerOutputReady();
        }
        return true;
    }

    public void onVideoSizeProbed(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) return;
        if (!shouldEvaluateOutput() || mpvAutoOutputEvaluated) return;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("mpv-output", "auto size probe size=%dx%d attempts=%d", width, height, mpvAutoOutputProbeAttempts);
        }
        mpvAutoOutputEvaluationScheduled = false;
        mpvOutputEvaluationSeq++;
        if (!evaluateAutoOutput()) scheduleAutoOutputEvaluation();
    }

    public boolean shouldEvaluateOutput() {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null) return false;
        return MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO
                || mpv.isDv7FelRequested() || mpv.isHard();
    }

    public boolean prepareFelOutput(MpvPlayerEngine mpv) {
        if (!mpv.updateDv7FelOutputForCurrentItem()) return false;
        if (mpv.isDv7FelOutputEnabled() && mpv.isVulkanRenderer()
                && host.isHardDecode() && PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV, PlaybackPerformanceCatalog.MPV_RENDER)) {
            mpvAutoVulkanPinnedForItem = true;
        }
        PlaybackTrace.log("mpv-dv", host.getPlaybackTrace().current(),
                "native FEL selection: active=%s single-load=1",
                mpv.isDv7FelOutputEnabled());
        return true;
    }

    public boolean retryAutoVulkanFailure(PlaybackException error) {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (error == null || !mpvAutoVulkanPinnedForItem || mpv == null || !mpv.isVulkanRenderer()) return false;
        String message = error.getMessage();
        boolean outputFailure = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                || message != null && message.startsWith(MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED);
        return outputFailure && retryAutoVulkanToOpenGl("auto-vulkan-output-failure");
    }

    public boolean retryAutoVulkanToOpenGl(String reason) {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (!mpvAutoVulkanPinnedForItem || mpv == null || !mpv.isVulkanRenderer()) return false;
        mpvAutoVulkanPinnedForItem = false;
        mpvAutoVulkanDisabledForItem = true;
        mpv.setVulkanRenderOverride(false);
        mpv.setVulkanBackendOverride(null);
        PlaybackTrace.log("mpv-vulkan", host.getPlaybackTrace().current(),
                "automatic DV GPU Vulkan failed; fallback OpenGL once reason=%s",
                reason);
        return host.rebuildAndRestartMpv(false, reason);
    }

    public boolean isDv7NativeAttemptRequested() {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !host.isHardDecode() || !mpv.isDv7NativeActive()) return false;
        PlayerEngine.VideoPlaybackDetails details = host.getVideoPlaybackDetails();
        return details != null && details.dolbyVisionProfile() == 7;
    }

    public void completeDirectFirstFrame(int state) {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !mpv.hasSubmittedVideoFrame()) return;
        if (!MpvAutoOutputPolicy.canRevealDirectFrame(
                MpvPerformanceSetting.getOutputMode() == MpvPerformanceSetting.OUTPUT_AUTO,
                mpvAutoOutputEvaluated,
                state == Player.STATE_READY,
                host.isSurfaceDirect(),
                host.getVideoWidth(),
                host.getVideoHeight())) return;
        mpvAutoOutputFrameReady = true;
        host.onPlayerOutputReady();
        if (!host.getPlaybackTrace().hasStage(PlaybackTrace.Stage.FIRST_FRAME)) {
            host.getPlaybackTrace().mark(PlaybackTrace.Stage.FIRST_FRAME,
                    "source=mpv-vo-submitted-direct player=" + PlayerSetting.MPV);
        }
        PlaybackTrace.log("mpv-output", host.getPlaybackTrace().current(),
                "auto shutter release reason=direct-playback-restart size=%dx%d evaluated=%s",
                host.getVideoWidth(), host.getVideoHeight(), mpvAutoOutputEvaluated);
    }

    private static Track findRequestedSubtitle(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return null;
        for (Track track : tracks) {
            if (track.getType() == C.TRACK_TYPE_TEXT && track.isSelected() && !track.isDisabled()) {
                return track;
            }
        }
        return null;
    }
}
