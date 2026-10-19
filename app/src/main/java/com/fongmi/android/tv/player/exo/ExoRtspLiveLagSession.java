package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.ArrayList;
import java.util.List;

public class ExoRtspLiveLagSession {

    public interface Host {
        @Nullable
        Player getPlayer();
        @Nullable
        PlayerEngine getEngine();
        boolean isExo();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        PlaybackResourceClassifier.Classification currentResourceClassification();
        PlaybackTrace getPlaybackTrace();
        boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action);
        boolean isStartupComplete();
        boolean restartExoRtspLiveSession();
        void publishTelemetryDecision(PlaybackAutoContext.SessionToken session, PlaybackTelemetry.DecisionEvent event, long nowMs);
    }

    private final Host host;
    private final ExoRtspLiveLagController controller;

    public ExoRtspLiveLagSession(@NonNull Host host) {
        this.host = host;
        this.controller = new ExoRtspLiveLagController();
    }

    public void beginSession(PlaybackAutoContext.SessionToken session) {
        controller.beginSession(session);
    }

    public void endSession(PlaybackAutoContext.SessionToken session) {
        controller.endSession(session);
    }

    public void onUserSeek(PlaybackAutoContext.SessionToken session, long nowMs) {
        controller.onUserSeek(session, nowMs);
    }

    public void onPositionDiscontinuity(PlaybackAutoContext.SessionToken session) {
        controller.onPositionDiscontinuity(session);
    }

    public void onPlaybackError(PlaybackAutoContext.SessionToken session) {
        controller.onPlaybackError(session);
    }

    public void evaluate(PlaybackTelemetry.RuntimeObservation observation, long nowMs) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        if (!session.active()) return;
        boolean automatic = PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO)
                && host.isExperimentAllowed(PlaybackExperimentPolicy.Action.EXO_RTSP_RECOVERY);
        boolean exo = host.isExo() && host.getEngine() instanceof ExoPlayerEngine;
        PlaybackResourceClassifier.Classification classification = host.currentResourceClassification();
        boolean rtsp = classification.protocol() == PlaybackAutoContext.Protocol.RTSP;
        boolean classifiedLive = classification.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || classification.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        boolean mediaItemLive = false;
        boolean active = false;
        boolean loading = false;
        boolean seekAvailable = false;
        Player player = host.getPlayer();
        if (player != null) {
            try {
                mediaItemLive = player.isCurrentMediaItemLive();
            } catch (Throwable ignored) {
            }
            try {
                int state = player.getPlaybackState();
                active = player.getPlayWhenReady()
                        && (player.isPlaying()
                        || state == Player.STATE_READY
                        || state == Player.STATE_BUFFERING);
            } catch (Throwable ignored) {
            }
            try {
                loading = player.isLoading();
            } catch (Throwable ignored) {
            }
            try {
                seekAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION);
            } catch (Throwable ignored) {
            }
        }
        boolean live = classifiedLive && mediaItemLive;
        boolean startupComplete = host.isStartupComplete();
        long liveLagMs = metricLong(observation == null ? null : observation.liveLagMs());
        long bufferedMs = metricLong(observation == null ? null : observation.bufferedDurationMs());
        boolean liveEdgeReliable = isReliableDynamicLiveEdge(live, seekAvailable, liveLagMs);
        ExoRtspLiveLagPolicy.Decision decision = controller.evaluate(
                new ExoRtspLiveLagController.Input(
                        session,
                        automatic,
                        exo,
                        rtsp,
                        live,
                        active,
                        startupComplete,
                        false,
                        loading,
                        liveEdgeReliable,
                        seekAvailable,
                        liveLagMs,
                        bufferedMs,
                        nowMs));
        ExoRtspLiveLagController.Snapshot stateAtDecision = controller.snapshot();

        // Do not fill the shared decision log with inapplicable HLS/DASH/native observations.
        if (!automatic || !exo || !rtsp) return;
        if (!decision.requestsRecovery()) {
            publishDecision(
                    decision,
                    PlaybackTelemetry.DecisionOutcome.HELD,
                    false,
                    active,
                    loading,
                    seekAvailable,
                    liveEdgeReliable,
                    stateAtDecision,
                    nowMs);
            return;
        }

        boolean started = controller.beginAction(session, decision.action(), nowMs);
        boolean succeeded = started && executeRecovery(decision.action());
        if (started) {
            controller.completeAction(session, decision.action(), succeeded);
        }
        publishDecision(
                decision,
                succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : PlaybackTelemetry.DecisionOutcome.FAILED,
                succeeded,
                active,
                loading,
                seekAvailable,
                liveEdgeReliable,
                stateAtDecision,
                nowMs);
    }

    private boolean executeRecovery(ExoRtspLiveLagPolicy.Action action) {
        try {
            return switch (action) {
                case SEEK_LIVE_EDGE -> host.getEngine() instanceof ExoPlayerEngine exo
                        && exo.recoverRtspLiveEdge();
                case REBUILD_SESSION -> host.restartExoRtspLiveSession();
                case HOLD -> false;
            };
        } catch (Throwable error) {
            PlaybackTrace trace = host.getPlaybackTrace();
            if (trace != null) {
                PlaybackTrace.log("exo-rtsp-live", trace.current(),
                        "action=%s result=failed errorType=%s",
                        action.label(), error.getClass().getSimpleName());
            }
            return false;
        }
    }

    private boolean isReliableDynamicLiveEdge(boolean live, boolean seekAvailable, long liveLagMs) {
        Player player = host.getPlayer();
        if (!live || !seekAvailable || liveLagMs < 0 || player == null) return false;
        try {
            Timeline timeline = player.getCurrentTimeline();
            int index = player.getCurrentMediaItemIndex();
            if (timeline == null || timeline.isEmpty()
                    || index < 0 || index >= timeline.getWindowCount()) {
                return false;
            }
            Timeline.Window window = timeline.getWindow(index, new Timeline.Window());
            return window.isLive() && window.isDynamic;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void publishDecision(
            ExoRtspLiveLagPolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            boolean succeeded,
            boolean active,
            boolean loading,
            boolean seekAvailable,
            boolean liveEdgeReliable,
            ExoRtspLiveLagController.Snapshot stateAtDecision,
            long nowMs) {
        ExoRtspLiveLagController.Snapshot snapshot = controller.snapshot();
        ExoRtspLiveLagController.Snapshot decisionState = stateAtDecision == null
                ? snapshot : stateAtDecision;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "trigger", decision.trigger().label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.liveLagMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("live_lag_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "live_lag_ms", decision.liveLagMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.lagGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("lag_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "lag_growth_msps", decision.lagGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decision.bufferedMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("buffered_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "buffered_ms", decision.bufferedMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.bufferGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("buffer_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "buffer_growth_msps", decision.bufferGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "active", active,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "loading", loading,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "edge_reliable", liveEdgeReliable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "seek_available", seekAvailable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", decisionState.consecutiveRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "recoveries", snapshot.recoveryAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        host.publishTelemetryDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.RTSP_LIVE_RECOVERY,
                        outcome,
                        decisionState.lastAction().label(),
                        decision.action().label(),
                        succeeded ? decision.action().label() : "hold",
                        decision.reason().label(),
                        decision.requestsRecovery() ? succeeded ? "none" : "action-failed"
                                : decision.reason().label(),
                        inputs),
                nowMs);
    }

    private static long metricLong(PlaybackTelemetry.Metric<Long> metric) {
        return metric == null || !metric.known() || metric.value() < 0
                ? -1 : metric.value();
    }
}
