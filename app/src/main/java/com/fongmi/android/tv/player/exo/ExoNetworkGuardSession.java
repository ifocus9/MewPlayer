package com.fongmi.android.tv.player.exo;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackExperimentCoordinator;
import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
import com.fongmi.android.tv.player.PlaybackProfileAbCoordinator;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.List;
import java.util.Locale;

public class ExoNetworkGuardSession {

    private static final String TAG = "ExoNetworkGuard";

    public interface Host {
        @Nullable
        Player getPlayer();
        boolean isExo();
        boolean isVod();
        float getUserPlaybackSpeed();
        AudioPlaybackDiagnostics.OutputMode getAudioOutputMode();
        PlaybackRoute.Resolution getEffectivePlaybackRoute();
        @Nullable
        Format getVideoFormat();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action);
        PlaybackExperimentCoordinator.Token captureExperimentToken(PlaybackExperimentPolicy.Action action);
        boolean isCurrentExperimentToken(PlaybackExperimentCoordinator.Token token);
        PlaybackTrace getPlaybackTrace();
        void invalidatePlaybackProfileAssessments(PlaybackProfileAbCoordinator.InvalidationReason reason);
        void publishTelemetryDecision(PlaybackAutoContext.SessionToken session, PlaybackTelemetry.DecisionEvent event, long nowMs);
    }

    private final Host host;
    private final Runnable runnable;
    private final ExoNetworkGuardController controller;
    private final ForwardBufferTrend trend;

    private float speed = 1f;
    private float supportedSpeed = 1f;
    private long mediaBitrate;
    private ExoNetworkGuardController.State state = ExoNetworkGuardController.State.NORMAL;
    private ExoNetworkGuardController.ProtectionTier tier = ExoNetworkGuardController.ProtectionTier.NONE;
    private String reason = "waiting";
    private PlaybackExperimentCoordinator.Token experimentToken;

    public ExoNetworkGuardSession(@NonNull Host host) {
        this.host = host;
        this.runnable = this::evaluate;
        this.controller = new ExoNetworkGuardController();
        this.trend = new ForwardBufferTrend();
    }

    public void release() {
        reset("release");
        App.removeCallbacks(runnable);
    }

    public String getText() {
        if (!host.isExo() || !ExoPerformanceSetting.isNetworkProtectionEnabled()) return "";
        if (Math.abs(host.getUserPlaybackSpeed() - 1f) > 0.001f) return "手动倍速时停用";
        if (!host.isVod()) return "仅支持点播";
        ExoNetworkGuardEligibility.Decision eligibility = getEligibility();
        if (!eligibility.eligible()) return eligibilityText(eligibility.reason());
        return switch (state) {
            case NORMAL -> "正常";
            case WARNING -> "评估中";
            case PROTECT -> "降速中";
            case RECOVERY -> "恢复中";
            case UNSUSTAINABLE -> "网络不足";
        };
    }

    private String eligibilityText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "preserve-passthrough" -> "音频直通时停用";
            case "preserve-tunneling" -> "隧道模式时停用";
            case "speed-unsupported" -> "播放器不支持调速";
            case "user-speed" -> "手动倍速时停用";
            case "vod-only" -> "仅支持点播";
            default -> "未启用";
        };
    }

    public long getMediaBitrate() {
        return mediaBitrate;
    }

    public long getStableThroughput() {
        return mediaBitrate <= 0 ? 0 : Math.max(0, Math.round(mediaBitrate * supportedSpeed));
    }

    public long getConsumption(float effectiveSpeed) {
        return mediaBitrate <= 0 ? 0 : Math.max(0, Math.round(mediaBitrate * effectiveSpeed));
    }

    public float getSupportedSpeed() {
        return supportedSpeed;
    }

    public float getProtectionSpeed() {
        return speed;
    }

    public ExoNetworkGuardController.State getState() {
        return state;
    }

    public ExoNetworkGuardController.ProtectionTier getTier() {
        return tier;
    }

    public String getReason() {
        return reason;
    }

    public void onUserSpeedChanged(float userSpeed) {
        reset("user-speed");
        if (Math.abs(userSpeed - 1f) < 0.001f) {
            schedule(0);
        }
    }

    public void onIsPlayingChanged(boolean isPlaying) {
        Player player = host.getPlayer();
        if (player == null) return;
        if (isPlaying) {
            schedule(0);
        } else if (player.getPlaybackState() == Player.STATE_READY && !player.getPlayWhenReady()) {
            host.invalidatePlaybackProfileAssessments(PlaybackProfileAbCoordinator.InvalidationReason.PAUSED);
            reset("paused");
        } else if (player.getPlaybackState() != Player.STATE_BUFFERING) {
            App.removeCallbacks(runnable);
            controller.disrupt(speed);
            trend.reset();
            state = controller.getState();
            tier = controller.getTier();
        } else {
            // A rebuffer is exactly the evidence the guard needs after playback resumes.
            // Keep the current protection speed, controller history, and forward-buffer
            // trend instead of forcing another warm-up window on every stall.
            App.removeCallbacks(runnable);
            reason = "buffering-hold";
        }
    }

    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY) {
            schedule(0);
        } else if (playbackState == Player.STATE_BUFFERING) {
            App.removeCallbacks(runnable);
            reason = "buffering-hold";
        } else {
            reset(playbackState == Player.STATE_ENDED ? "ended" : "inactive");
        }
    }

    public void onTimelineChanged(@NonNull Timeline timeline, int timelineReason) {
        if (host.isExo()) schedule(0);
    }

    public void onTracksChanged() {
        if (host.isExo()) schedule(0);
    }

    public void onPositionDiscontinuity(int discontinuityReason) {
        reset("discontinuity-" + discontinuityReason);
        schedule(ExoNetworkGuardController.OBSERVE_INTERVAL_MS);
    }

    public void onPerformanceSettingsChanged() {
        reset("performance-settings-changed");
        schedule(0);
    }

    public void reset(String newReason) {
        App.removeCallbacks(runnable);
        controller.reset();
        trend.reset();
        state = ExoNetworkGuardController.State.NORMAL;
        tier = ExoNetworkGuardController.ProtectionTier.NONE;
        reason = newReason;
        speed = 1f;
        supportedSpeed = 1f;
        mediaBitrate = 0;
        experimentToken = null;
        applyEffectiveSpeed(host.getUserPlaybackSpeed(), newReason);
    }

    public void schedule(long delayMs) {
        App.removeCallbacks(runnable);
        ExoNetworkGuardEligibility.Decision eligibility = getEligibility();
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = host.getAudioOutputMode();
        Player player = host.getPlayer();
        log("schedule delay=" + delayMs + " eligible=" + eligibility.eligible()
                + " reason=" + eligibility.reason() + " exo=" + host.isExo() + " vod=" + host.isVod()
                + " userSpeed=" + host.getUserPlaybackSpeed() + " tunnel=" + PlayerSetting.isTunnel()
                + " configuredPassthrough=" + PlayerSetting.isAudioPassThrough(PlayerSetting.EXO)
                + " audioOutput=" + audioOutputMode
                + " state=" + (player == null ? -1 : player.getPlaybackState())
                + " playing=" + (player != null && player.isPlaying()));
        if (!eligibility.eligible()) {
            if (speed < 0.999f) reset(eligibility.reason());
            else {
                state = ExoNetworkGuardController.State.NORMAL;
                tier = ExoNetworkGuardController.ProtectionTier.NONE;
                reason = eligibility.reason();
            }
            return;
        }
        if (player == null || player.getPlaybackState() != Player.STATE_READY || !player.isPlaying()) return;
        experimentToken = host.captureExperimentToken(PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED);
        App.post(runnable, delayMs);
    }

    public ExoNetworkGuardEligibility.Decision getEligibility() {
        Player player = host.getPlayer();
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = host.getAudioOutputMode();
        return ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                ExoPerformanceSetting.isNetworkProtectionEnabled()
                        && host.isExperimentAllowed(PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED),
                player != null && host.isExo(),
                host.isVod(),
                Math.abs(host.getUserPlaybackSpeed() - 1f) < 0.001f,
                player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                PlayerSetting.isTunnel(),
                audioOutputMode));
    }

    private void evaluate() {
        Player player = host.getPlayer();
        if (player == null) return;
        if (!host.isCurrentExperimentToken(experimentToken)
                || !host.isExperimentAllowed(PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED)) {
            reset("experiment-disabled");
            return;
        }
        ExoNetworkGuardEligibility.Decision eligibility = getEligibility();
        boolean eligible = eligibility.eligible();
        long nowMs = SystemClock.elapsedRealtime();
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean playing = player.isPlaying();
        boolean loading = player.isLoading();
        long bufferedMs = Math.max(0, player.getTotalBufferedDuration());
        trend.observe(
                nowMs,
                bufferedMs,
                eligible && ready && playing,
                loading);
        ForwardBufferTrend.Snapshot trendSnapshot = trend.snapshot();
        PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
        PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media = PlaybackAnalyticsListener.getDisplayMediaBitrateEstimate(host.getVideoFormat());
        boolean networkEstimateKnown = isTrustedNetworkEstimate(analytics, media);
        float networkSupportedSpeed = networkEstimateKnown ? Math.min(2f, analytics.bandwidthEstimate() * 0.90f / media.bitrateBitsPerSecond()) : 1f;
        long safeBufferMs = getSafeBufferMs();
        float previousEffectiveSpeed = player.getPlaybackParameters().speed;
        ExoNetworkGuardController.State previousState = state;
        ExoNetworkGuardController.ProtectionTier previousTier = tier;
        ExoNetworkGuardController.Decision decision = controller.evaluate(new ExoNetworkGuardController.Input(
                nowMs,
                eligible,
                ready,
                playing,
                loading,
                bufferedMs,
                trendSnapshot.known(),
                trendSnapshot.slopeMsPerSecond(),
                trendSnapshot.fastSlopeMsPerSecond(),
                trendSnapshot.slowSlopeMsPerSecond(),
                trendSnapshot.windowMs(),
                analytics.rebufferCount(),
                previousEffectiveSpeed,
                ExoPerformanceSetting.getNetworkProtectionMinimumSpeed(),
                safeBufferMs,
                networkEstimateKnown,
                networkSupportedSpeed));
        log(String.format(Locale.US,
                "evaluate eligible=%s ready=%s playing=%s loading=%s buffered=%d safe=%d trendKnown=%s slope=%d fast=%d slow=%d window=%d rebuffer=%d current=%.3f networkKnown=%s networkSupported=%.3f decision=%s tier=%s reason=%s changed=%s target=%.3f supported=%.3f raw=%.3f calculated=%.3f tte=%d ttr=%d requiredSlew=%.4f appliedSlew=%.4f feasible=%s",
                eligible, ready, playing, loading, bufferedMs, safeBufferMs, trendSnapshot.known(), trendSnapshot.slopeMsPerSecond(),
                trendSnapshot.fastSlopeMsPerSecond(), trendSnapshot.slowSlopeMsPerSecond(), trendSnapshot.windowMs(), analytics.rebufferCount(),
                previousEffectiveSpeed, networkEstimateKnown, networkSupportedSpeed, decision.state(), decision.tier(), decision.reason(),
                decision.changed(), decision.targetSpeed(), decision.supportedSpeed(), decision.rawTargetSpeed(),
                decision.calculatedTargetSpeed(), decision.timeToEmptyMs(), decision.timeToReserveMs(),
                decision.requiredSlewPerSecond(), decision.appliedSlewPerSecond(), decision.rampFeasible()));
        state = decision.state();
        tier = decision.tier();
        reason = decision.reason();
        speed = decision.targetSpeed();
        supportedSpeed = decision.supportedSpeed();
        mediaBitrate = media.bitrateBitsPerSecond();
        if (decision.changed()) applyEffectiveSpeed(speed, "guard-" + decision.reason());
        PlaybackTelemetry.DecisionOutcome telemetryOutcome = decision.changed()
                ? PlaybackTelemetry.DecisionOutcome.APPLIED
                : !eligible || !ready || !playing
                ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        host.publishTelemetryDecision(host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.NETWORK_PROTECTION,
                        telemetryOutcome,
                        previousState.name().toLowerCase(Locale.US),
                        decision.state().name().toLowerCase(Locale.US),
                        state.name().toLowerCase(Locale.US),
                        decision.reason(),
                        telemetryOutcome == PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                                ? eligibility.reason() : telemetryOutcome == PlaybackTelemetry.DecisionOutcome.HELD ? "no-change" : "none",
                        List.of(
                                PlaybackTelemetry.DecisionInput.bool("eligible", eligible, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("ready", ready, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("playing", playing, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("loading", loading, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("buffered_ms", bufferedMs, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("safe_buffer_ms", safeBufferMs, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                networkEstimateKnown ? PlaybackTelemetry.DecisionInput.number("bandwidth_bps", analytics.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("bandwidth_bps"),
                                media.bitrateBitsPerSecond() > 0 ? PlaybackTelemetry.DecisionInput.number("media_bitrate_bps", media.bitrateBitsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, telemetryConfidence(media.confidence())) : PlaybackTelemetry.DecisionInput.unknown("media_bitrate_bps"),
                                PlaybackTelemetry.DecisionInput.number("rebuffer_count", analytics.rebufferCount(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                trendSnapshot.known() ? PlaybackTelemetry.DecisionInput.number("buffer_slope_msps", trendSnapshot.slopeMsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("buffer_slope_msps"),
                                PlaybackTelemetry.DecisionInput.decimal("current_speed", previousEffectiveSpeed, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.decimal("target_speed", decision.targetSpeed(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                nowMs);
        if (decision.changed() || previousState != state || previousTier != tier) {
            PlaybackTrace trace = host.getPlaybackTrace();
            if (trace != null) {
                PlaybackTrace.log("exo-network-protection", trace.current(), "state=%s tier=%s reason=%s speed=%.3f supported=%.3f rawTarget=%.3f target=%.3f floor=%.2f buffered=%d safe=%d tte=%d ttr=%d requiredSlew=%.4f appliedSlew=%.4f feasible=%s loading=%s slope=%d fast=%d slow=%d window=%d rebuffer=%d networkKnown=%s networkSupported=%.3f route=%s",
                        state, tier, reason, speed, decision.supportedSpeed(), decision.rawTargetSpeed(), decision.calculatedTargetSpeed(), ExoPerformanceSetting.getNetworkProtectionMinimumSpeed(),
                        player.getTotalBufferedDuration(), decision.safeBufferMs(), decision.timeToEmptyMs(), decision.timeToReserveMs(), decision.requiredSlewPerSecond(), decision.appliedSlewPerSecond(), decision.rampFeasible(),
                        player.isLoading(), trendSnapshot.slopeMsPerSecond(), trendSnapshot.fastSlopeMsPerSecond(), trendSnapshot.slowSlopeMsPerSecond(), trendSnapshot.windowMs(), analytics.rebufferCount(), networkEstimateKnown, networkSupportedSpeed, host.getEffectivePlaybackRoute().route());
            }
        }
        if (eligible && player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) {
            schedule(getEvaluationDelayMs());
        }
    }

    private void applyEffectiveSpeed(float targetSpeed, String applyReason) {
        Player player = host.getPlayer();
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            log("apply skipped reason=" + applyReason + " player=" + (player != null)
                    + " command=" + (player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)));
            return;
        }
        float userSpeed = host.getUserPlaybackSpeed();
        float current = player.getPlaybackParameters().speed;
        if (Math.abs(targetSpeed - userSpeed) >= 0.001f) {
            host.invalidatePlaybackProfileAssessments(PlaybackProfileAbCoordinator.InvalidationReason.SPEED_RESCUE_CONFOUND);
        }
        log(String.format(Locale.US,
                "apply request reason=%s requested=%.3f current=%.3f user=%.3f state=%d playing=%s loading=%s",
                applyReason, targetSpeed, current, userSpeed, player.getPlaybackState(), player.isPlaying(), player.isLoading()));
        if (Math.abs(current - targetSpeed) < 0.001f) return;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(targetSpeed));
        log(String.format(Locale.US,
                "apply result reason=%s requested=%.3f actual=%.3f", applyReason, targetSpeed, player.getPlaybackParameters().speed));
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("exo-network-protection", trace.current(), "speed %.3f->%.3f reason=%s user=%.2f", current, targetSpeed, applyReason, userSpeed);
        }
    }

    private long getEvaluationDelayMs() {
        return switch (state) {
            case WARNING, PROTECT, RECOVERY -> ExoNetworkGuardController.CONTROL_INTERVAL_MS;
            case NORMAL, UNSUSTAINABLE -> ExoNetworkGuardController.OBSERVE_INTERVAL_MS;
        };
    }

    private long getSafeBufferMs() {
        PlaybackRoute.Resolution route = host.getEffectivePlaybackRoute();
        return ExoNetworkGuardBufferPolicy.resolve(route.loopback(), ExoPerformanceSetting.getRebufferMs());
    }

    private boolean isTrustedNetworkEstimate(PlaybackAnalyticsListener.Snapshot analytics, PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media) {
        if (analytics.bandwidthEstimate() <= 0 || media.bitrateBitsPerSecond() <= 0) return false;
        if ("unknown".equals(media.source()) || "low".equals(media.confidence()) || "unknown".equals(media.confidence())) return false;
        return host.getEffectivePlaybackRoute().route() == PlaybackRoute.DIRECT_REMOTE_HTTP;
    }

    private static PlaybackAutoContext.Confidence telemetryConfidence(String value) {
        if (value == null) return PlaybackAutoContext.Confidence.UNKNOWN;
        return switch (value) {
            case "high" -> PlaybackAutoContext.Confidence.HIGH;
            case "medium" -> PlaybackAutoContext.Confidence.MEDIUM;
            case "low" -> PlaybackAutoContext.Confidence.LOW;
            default -> PlaybackAutoContext.Confidence.UNKNOWN;
        };
    }

    private static void log(String message) {
        if (SpiderDebug.isEnabled()) Log.d(TAG, message);
    }
}
