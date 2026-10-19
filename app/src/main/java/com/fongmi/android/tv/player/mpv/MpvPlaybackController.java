package com.fongmi.android.tv.player.mpv;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
import com.fongmi.android.tv.player.PlaybackMemoryCoordinator;
import com.fongmi.android.tv.player.PlaybackSystemConditionCoordinator;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTelemetryCoordinator;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPVLib;

public class MpvPlaybackController {

    public interface Host {
        MpvPlayerEngine getMpvEngine();
        boolean isMpv();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        PlaybackAutoContextStore getAutoContextStore();
        PlaybackTelemetryCoordinator getTelemetryCoordinator();
        PlaybackTrace getPlaybackTrace();
        PlaySpec getSpec();
        Player getPlayer();
        float getSpeed();
        int getRebufferCount();
        boolean isRepeatOne();
        boolean isExperimentAllowed(PlaybackExperimentPolicy.Action action);
        boolean restartForMpvHlsVariant(long position, boolean wasPlayWhenReady, float speed, boolean repeat);
    }

    private final Host host;
    private final MpvAutoController mpvAutoController;
    private final MpvForwardCacheController mpvForwardCacheController;
    private final MpvBackCacheController mpvBackCacheController;
    private final MpvCacheTargetCoordinator mpvCacheTargetCoordinator;
    private final MpvHlsVariantController mpvHlsVariantController;
    private final MpvResourcePressureController mpvResourcePressureController;
    private final MpvPreloadController mpvPreloadController;
    private final PlaybackMemoryCoordinator.Registration mpvResourceMemoryRegistration;
    private final PlaybackSystemConditionCoordinator.Registration mpvResourceSystemRegistration;
    private boolean mpvHlsManagedReload;

    public MpvPlaybackController(Host host) {
        this.host = host;
        this.mpvAutoController = new MpvAutoController();
        this.mpvForwardCacheController = new MpvForwardCacheController();
        this.mpvBackCacheController = new MpvBackCacheController();
        this.mpvCacheTargetCoordinator = new MpvCacheTargetCoordinator();
        this.mpvHlsVariantController = new MpvHlsVariantController();
        this.mpvResourcePressureController = new MpvResourcePressureController();
        this.mpvPreloadController = new MpvPreloadController();
        this.mpvResourceMemoryRegistration = PlaybackMemoryCoordinator.process().addListener(update ->
                App.post(() -> onMpvResourceMemoryUpdate(update)));
        this.mpvResourceSystemRegistration = PlaybackSystemConditionCoordinator.process().addListener(update ->
                App.post(() -> onMpvResourceSystemUpdate(update)));
    }

    public boolean isManagedReload() {
        return mpvHlsManagedReload;
    }

    public void setManagedReload(boolean managedReload) {
        this.mpvHlsManagedReload = managedReload;
    }

    public void release() {
        if (mpvResourceMemoryRegistration != null) mpvResourceMemoryRegistration.close();
        if (mpvResourceSystemRegistration != null) mpvResourceSystemRegistration.close();
    }

    public void beginSession(PlaybackAutoContext.SessionToken session, long now) {
        mpvAutoController.beginSession(session);
        mpvForwardCacheController.beginSession(session);
        mpvBackCacheController.beginSession(session);
        mpvCacheTargetCoordinator.beginSession(session);
        mpvHlsVariantController.beginSession(session);
        mpvResourcePressureController.beginSession(session);
        mpvPreloadController.beginSession(session);
        mpvHlsManagedReload = false;
    }

    public void endSession(PlaybackAutoContext.SessionToken session) {
        mpvForwardCacheController.endSession(session);
        mpvBackCacheController.endSession(session);
        mpvCacheTargetCoordinator.endSession(session);
        mpvHlsVariantController.endSession(session);
        mpvResourcePressureController.endSession(session);
        mpvPreloadController.endSession(session);
        mpvHlsManagedReload = false;
        mpvAutoController.endSession(session);
    }

    public void disruptPreload(PlaybackAutoContext.SessionToken session) {
        mpvPreloadController.disrupt(session);
    }

    public void onPositionDiscontinuity(int reason, Player.PositionInfo oldPosition, Player.PositionInfo newPosition) {
        if (!host.isMpv()) return;
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            disruptPreload(host.getPlaybackAutoSession());
        }
        MpvBackCachePolicy.SeekObservation seek = MpvBackCachePolicy.observeSeek(
                reason == Player.DISCONTINUITY_REASON_SEEK,
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex,
                oldPosition.positionMs,
                newPosition.positionMs);
        evaluateMpvCaches(
                null,
                MpvBackCacheController.Trigger.SEEK,
                seek,
                SystemClock.elapsedRealtime());
    }

    public void evaluateRuntime(PlaybackTelemetry.RuntimeObservation telemetry, boolean evaluateHlsVariant, long now) {
        if (!host.isMpv()) return;
        if (evaluateHlsVariant) {
            evaluateMpvHlsVariant(telemetry, now);
        }
        evaluateMpvCaches(
                MpvForwardCacheController.Trigger.RUNTIME,
                MpvBackCacheController.Trigger.RUNTIME,
                MpvBackCachePolicy.SeekObservation.none(),
                now);
    }

    private void applyMpvHlsInitialControl(
            MpvPlayerEngine mpv,
            PlaybackAutoContext context,
            boolean automatic,
            long now) {
        String networkIdentity = PlaybackSystemConditionMonitor.process()
                .currentNetworkIdentityDigest();
        PlaybackThroughputHistory.Match history = PlaybackThroughputHistory
                .process().lookup(context, networkIdentity, now);
        MpvHlsVariantPolicy.InitialAssessment assessment =
                MpvHlsVariantPolicy.resolveInitial(
                        automatic,
                        host.isMpv(),
                        MpvPerformanceSetting.isPerformancePriority(),
                        history);
        MpvHlsVariantController.Snapshot before =
                mpvHlsVariantController.snapshot();
        MpvHlsVariantController.Decision decision =
                mpvHlsVariantController.evaluateInitial(
                        host.getPlaybackAutoSession(),
                        context == null ? PlaybackAutoContext.SessionToken.none()
                                : context.session(),
                        assessment);
        boolean pendingContextRestore = !decision.requestsApply()
                && decision.reason()
                == MpvHlsVariantController.Reason.ACTION_PENDING
                && before.state() != MpvHlsVariantController.State.APPLYING
                && assessment.active()
                && !TextUtils.isEmpty(before.targetOption());
        String requestedOption = pendingContextRestore
                ? before.targetOption() : decision.targetOption();
        boolean started = false;
        MpvPlayer.AutoHlsBitrateResult result =
                MpvPlayer.AutoHlsBitrateResult.REJECTED;
        if (pendingContextRestore) {
            result = mpv.applyAutoHlsBitrate(
                    host.getPlaybackTrace().current(), requestedOption);
        } else if (decision.requestsApply()) {
            started = mpvHlsVariantController.beginApply(
                    host.getPlaybackAutoSession(), decision, now);
            if (started) {
                result = mpv.applyAutoHlsBitrate(
                        host.getPlaybackTrace().current(), decision.targetOption());
                mpvHlsVariantController.completeApply(
                        host.getPlaybackAutoSession(),
                        decision,
                        result.accepted(),
                        result.staged(),
                        now);
            }
        } else if (decision.reason()
                != MpvHlsVariantController.Reason.ACTION_PENDING) {
            mpv.clearAutoHlsBitrate();
            if (!automatic
                    || assessment.reason()
                    == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY) {
                mpvHlsVariantController.suppress(host.getPlaybackAutoSession());
            }
        }
        PlaybackTelemetry.DecisionOutcome outcome =
                !automatic
                        || assessment.reason()
                        == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                        ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                        : pendingContextRestore && !result.accepted()
                        ? PlaybackTelemetry.DecisionOutcome.FAILED
                        : pendingContextRestore && result.staged()
                        ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                        : pendingContextRestore
                        ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : !decision.requestsApply()
                        ? PlaybackTelemetry.DecisionOutcome.HELD
                        : !started || !result.accepted()
                        ? PlaybackTelemetry.DecisionOutcome.FAILED
                        : result.staged()
                        ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                        : PlaybackTelemetry.DecisionOutcome.APPLIED;
        String suppression = !automatic
                ? "not-automatic"
                : assessment.reason() == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                ? "mpv-conf-priority"
                : pendingContextRestore && !result.accepted()
                ? "native-restore-failed"
                : pendingContextRestore
                ? "none"
                : decision.requestsApply() && !started
                ? "action-rejected"
                : decision.requestsApply() && !result.accepted()
                ? "native-apply-failed"
                : "none";
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "ceiling_bps", assessment.ceilingBitsPerSecond(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(assessment.trustedThroughputBitsPerSecond() > 0
                ? PlaybackTelemetry.DecisionInput.number(
                "trusted_throughput_bps",
                assessment.trustedThroughputBitsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                assessment.confidence())
                : PlaybackTelemetry.DecisionInput.unknown(
                "trusted_throughput_bps"));
        inputs.add(history.usable()
                ? PlaybackTelemetry.DecisionInput.number(
                "evidence_age_ms", history.ageMs(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                history.confidence())
                : PlaybackTelemetry.DecisionInput.unknown("evidence_age_ms"));
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "history_reason", history.reason().label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(assessment.pathKind() == PlaybackAutoContext.PathKind.UNKNOWN
                ? PlaybackTelemetry.DecisionInput.unknown("path")
                : PlaybackTelemetry.DecisionInput.text(
                "path", assessment.pathKind().label(),
                PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                assessment.confidence()));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "native_readbacks",
                mpv.getAutoHlsRuntimeSnapshot().observedCount(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.HIGH));
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_HLS_VARIANT,
                        outcome,
                        hlsOptionLabel(before.targetOption()),
                        hlsOptionLabel(requestedOption),
                        result.accepted()
                                ? hlsOptionLabel(requestedOption)
                                : hlsOptionLabel(before.targetOption()),
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
    }

    public void applyMpvAutoInitialControl() {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !host.getPlaybackAutoSession().active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext context = host.getAutoContextStore().snapshot();
        boolean automaticForward = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV,
                PlaybackPerformanceCatalog.BUFFER_BYTES);
        boolean automaticBack = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV,
                PlaybackPerformanceCatalog.BACK_BUFFER);
        boolean automatic = automaticForward || automaticBack;
        applyMpvHlsInitialControl(
                mpv,
                context,
                PlaybackPerformanceSetting.isAuto(
                        PlayerSetting.MPV,
                        PlaybackPerformanceCatalog.MPV_HLS_BITRATE),
                now);
        MpvAutoControlPolicy.Request request = MpvAutoControlPolicy.requestFrom(
                context,
                automatic,
                host.isMpv(),
                MpvPerformanceSetting.isPerformancePriority(),
                now);
        MpvAutoControlPolicy.Decision decision = mpvAutoController.evaluate(
                host.getPlaybackAutoSession(),
                context.session(),
                request);
        MpvAutoController.Snapshot before = mpvAutoController.snapshot();
        if (!automatic) {
            mpv.clearAutoCacheBaseline();
            mpvForwardCacheController.suppress(host.getPlaybackAutoSession());
            mpvBackCacheController.suppress(host.getPlaybackAutoSession());
            mpvCacheTargetCoordinator.suppress(host.getPlaybackAutoSession());
            evaluateMpvCaches(
                    null,
                    null,
                    MpvBackCachePolicy.SeekObservation.none(),
                    now);
            return;
        }

        boolean started = false;
        boolean applied = false;
        boolean staged = false;
        String applyResult = "not-requested";
        MpvForwardCacheController.Trigger forwardTrigger = null;
        MpvBackCacheController.Trigger backTrigger = null;
        long targetForwardBytes = automaticForward
                ? decision.forwardBytes() : mpv.getConfiguredForwardCacheBytes();
        long targetBackBytes = automaticBack
                ? decision.backBytes() : mpv.getConfiguredBackCacheBytes();
        if (targetBackBytes > targetForwardBytes) {
            if (automaticForward && !automaticBack) {
                targetForwardBytes = targetBackBytes;
            } else if (!automaticForward && automaticBack) {
                targetBackBytes = targetForwardBytes;
            }
        }
        if (decision.requestsApply()) {
            started = mpvAutoController.beginApply(host.getPlaybackAutoSession(), decision);
            MpvPlayer.AutoCacheBaselineResult result = started
                    ? mpv.applyAutoCacheBaseline(
                    host.getPlaybackTrace().current(), targetForwardBytes, targetBackBytes)
                    : MpvPlayer.AutoCacheBaselineResult.REJECTED;
            applied = result.accepted();
            staged = result.staged();
            applyResult = result.label();
            if (started) {
                mpvAutoController.completeApply(
                        host.getPlaybackAutoSession(), decision, applied, staged);
            }
        } else {
            mpv.clearAutoCacheBaseline();
            mpvForwardCacheController.suppress(host.getPlaybackAutoSession());
            mpvBackCacheController.suppress(host.getPlaybackAutoSession());
            mpvCacheTargetCoordinator.suppress(host.getPlaybackAutoSession());
        }
        if (applied) {
            MpvForwardCacheController.Snapshot forwardBefore = mpvForwardCacheController.snapshot();
            MpvBackCacheController.Snapshot backBefore = mpvBackCacheController.snapshot();
            boolean preserveForwardTarget = forwardBefore.baselineInitialized();
            boolean preserveBackTarget = backBefore.baselineInitialized();
            mpvForwardCacheController.recordBaseline(
                    host.getPlaybackAutoSession(), targetForwardBytes, preserveForwardTarget);
            mpvBackCacheController.recordBaseline(
                    host.getPlaybackAutoSession(), targetBackBytes, preserveBackTarget);
            mpvCacheTargetCoordinator.recordBaseline(
                    host.getPlaybackAutoSession(), targetForwardBytes, targetBackBytes);
            forwardTrigger = !automaticForward ? null : preserveForwardTarget
                    ? MpvForwardCacheController.Trigger.REBUILD
                    : MpvForwardCacheController.Trigger.BASELINE;
            backTrigger = !automaticBack ? null : preserveBackTarget
                    ? MpvBackCacheController.Trigger.REBUILD
                    : MpvBackCacheController.Trigger.BASELINE;
        }
        MpvAutoController.Snapshot after = mpvAutoController.snapshot();
        PlaybackTelemetry.DecisionOutcome outcome =
                decision.reason() == MpvAutoControlPolicy.Reason.CONFIG_PRIORITY
                        ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                        : !decision.requestsApply()
                        ? PlaybackTelemetry.DecisionOutcome.HELD
                        : staged
                        ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                        : applied
                        ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : PlaybackTelemetry.DecisionOutcome.FAILED;
        String oldValue = before.appliedForwardBytes() >= 0
                ? "forward-" + before.appliedForwardBytes()
                + "-back-" + before.appliedBackBytes()
                : "startup-baseline";
        String resultValue = applied ? decision.targetLabel() : oldValue;
        String suppression = decision.reason() == MpvAutoControlPolicy.Reason.CONFIG_PRIORITY
                ? "mpv-conf-priority"
                : decision.requestsApply() && !started
                ? "action-rejected"
                : decision.requestsApply() && !applied
                ? "native-apply-failed"
                : "none";

        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(decision.requestsApply()
                ? PlaybackTelemetry.DecisionInput.number(
                "forward_bytes", decision.forwardBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("forward_bytes"));
        inputs.add(decision.requestsApply()
                ? PlaybackTelemetry.DecisionInput.number(
                "back_bytes", decision.backBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("back_bytes"));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "performance_priority", request.performancePriority(),
                PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(request.pressureUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "memory_pressure", request.memoryPressure().label(),
                context.device().memoryPressure().source(),
                context.device().memoryPressure().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("memory_pressure"));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "memory_snapshot_usable", request.snapshotUsable(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(request.snapshotUsable()
                && request.memorySnapshot().lowRamDevice() != null
                ? PlaybackTelemetry.DecisionInput.bool(
                "low_ram", request.memorySnapshot().lowRamDevice(),
                context.device().memorySnapshot().source(),
                context.device().memorySnapshot().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("low_ram"));
        inputs.add(request.protocolUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "protocol", request.protocol().label(),
                context.resource().protocol().source(),
                context.resource().protocol().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("protocol"));
        inputs.add(request.streamKindUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "stream", request.streamKind().label(),
                context.resource().streamKind().source(),
                context.resource().streamKind().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("stream"));
        inputs.add(request.playerPathUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "player_path", request.playerPath().label(),
                context.path().playerPath().source(),
                context.path().playerPath().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("player_path"));
        inputs.add(request.upstreamPathUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "upstream_path", request.upstreamPath().label(),
                context.path().upstreamPath().source(),
                context.path().upstreamPath().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("upstream_path"));
        inputs.add(request.upstreamStateUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "upstream_state", request.upstreamState().label(),
                context.path().upstreamState().source(),
                context.path().upstreamState().confidence())
                : PlaybackTelemetry.DecisionInput.unknown("upstream_state"));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "apply_attempts", after.applyAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));

        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_CACHE,
                        outcome,
                        oldValue,
                        decision.targetLabel(),
                        resultValue,
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
        PlaybackTrace.log("mpv-auto", host.getPlaybackTrace().current(),
                "state=%s action=%s reason=%s forwardBytes=%d backBytes=%d capped=%s attempts=%d result=%s",
                after.state().label(),
                decision.action().label(),
                decision.reason().label(),
                decision.forwardBytes(),
                decision.backBytes(),
                decision.capped(),
                after.applyAttempts(),
                decision.requestsApply() ? applyResult : outcome.label());
        if (forwardTrigger != null || backTrigger != null) {
            evaluateMpvCaches(forwardTrigger, backTrigger,
                    MpvBackCachePolicy.SeekObservation.none(), now);
        } else {
            evaluateMpvCaches(
                    null,
                    null,
                    MpvBackCachePolicy.SeekObservation.none(),
                    now);
        }
    }

    private void onMpvResourceMemoryUpdate(PlaybackMemoryCoordinator.Update update) {
        if (update == null || !host.getPlaybackAutoSession().active()
                || !host.getPlaybackAutoSession().equals(update.session())) return;
        evaluateMpvCaches(
                MpvForwardCacheController.Trigger.MEMORY,
                MpvBackCacheController.Trigger.MEMORY,
                MpvBackCachePolicy.SeekObservation.none(),
                SystemClock.elapsedRealtime());
    }

    private void onMpvResourceSystemUpdate(
            PlaybackSystemConditionCoordinator.Update update) {
        if (update == null || !host.getPlaybackAutoSession().active()
                || !host.getPlaybackAutoSession().equals(update.session())) return;
        evaluateMpvCaches(
                MpvForwardCacheController.Trigger.RESOURCE,
                MpvBackCacheController.Trigger.RESOURCE,
                MpvBackCachePolicy.SeekObservation.none(),
                SystemClock.elapsedRealtime());
    }

    public void evaluateMpvCaches(
            MpvForwardCacheController.Trigger forwardTrigger,
            MpvBackCacheController.Trigger backTrigger,
            MpvBackCachePolicy.SeekObservation seekObservation,
            long nowElapsedMs) {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !host.getPlaybackAutoSession().active()) return;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext context = host.getAutoContextStore().snapshot();
        boolean automaticForward = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV,
                PlaybackPerformanceCatalog.BUFFER_BYTES);
        boolean automaticBack = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV,
                PlaybackPerformanceCatalog.BACK_BUFFER);
        boolean automaticPreload = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.MPV,
                PlaybackPerformanceCatalog.PRELOAD);
        boolean automatic = automaticForward || automaticBack || automaticPreload;
        boolean performancePriority = MpvPerformanceSetting.isPerformancePriority();
        MpvForwardCacheController.Snapshot forwardBefore = mpvForwardCacheController.snapshot();
        MpvBackCacheController.Snapshot backBefore = mpvBackCacheController.snapshot();
        long baseline = forwardBefore.initialBaselineBytes() > 0
                ? forwardBefore.initialBaselineBytes() : MpvForwardCachePolicy.MIN_FORWARD_BYTES;
        long resourceForward = forwardBefore.controlledTargetBytes() > 0
                ? forwardBefore.controlledTargetBytes() : baseline;
        long resourceBack = backBefore.controlledTargetBytes() >= 0
                ? backBefore.controlledTargetBytes() : 0;
        MpvResourcePressurePolicy.Assessment resourceAssessment =
                MpvResourcePressurePolicy.assess(
                        context,
                        automatic,
                        host.isMpv(),
                        performancePriority,
                        now);
        MpvResourcePressureController.Snapshot resourceBefore =
                mpvResourcePressureController.snapshot();
        MpvResourcePressureController.Trigger resourceTrigger =
                mpvResourceTrigger(forwardTrigger, backTrigger);
        MpvResourcePressureController.Decision resourceDecision =
                mpvResourcePressureController.evaluate(
                        host.getPlaybackAutoSession(),
                        context.session(),
                        resourceAssessment,
                        resourceTrigger,
                        resourceForward,
                        resourceBack,
                        now);
        boolean resourcePreloadChanged = resourceBefore.preloadAllowed()
                != resourceDecision.preloadAllowed();
        publishMpvResourcePressureDecision(
                resourceBefore,
                resourceAssessment,
                resourceDecision,
                resourcePreloadChanged,
                now);
        evaluateMpvPreload(mpv, context, resourceDecision, automaticPreload,
                performancePriority, now);
        MpvForwardCachePolicy.Assessment forwardAssessment = MpvForwardCachePolicy.assess(
                context,
                automaticForward,
                host.isMpv(),
                performancePriority,
                baseline,
                now);
        MpvForwardCacheController.Decision forwardDecision = !automaticForward
                || forwardTrigger == null
                ? null : mpvForwardCacheController.evaluate(
                host.getPlaybackAutoSession(), context.session(), forwardAssessment, resourceDecision,
                forwardTrigger, now);
        MpvForwardCacheController.Snapshot forwardEvaluated =
                mpvForwardCacheController.snapshot();
        long currentForward = !automaticForward
                ? mpv.getConfiguredForwardCacheBytes()
                : forwardEvaluated.controlledTargetBytes() > 0
                ? forwardEvaluated.controlledTargetBytes() : baseline;
        long targetForward = forwardDecision != null && forwardDecision.requestsApply()
                ? forwardDecision.targetBytes() : currentForward;

        boolean seekableForBack = isCurrentMpvMediaSeekable()
                || backTrigger == MpvBackCacheController.Trigger.REBUILD
                && backBefore.controlledTargetBytes() > 0;
        MpvBackCachePolicy.Request backRequest = MpvBackCachePolicy.requestFrom(
                context,
                automaticBack,
                host.isMpv(),
                performancePriority,
                seekableForBack,
                isCurrentMpvMediaLive(),
                targetForward,
                forwardAssessment,
                now);
        MpvBackCachePolicy.Assessment backAssessment =
                MpvBackCachePolicy.resolve(backRequest);
        MpvBackCacheController.Decision backDecision = !automaticBack
                || backTrigger == null
                ? null : mpvBackCacheController.evaluate(
                host.getPlaybackAutoSession(), context.session(), backAssessment, resourceDecision,
                backTrigger, seekObservation, now);
        MpvBackCacheController.Snapshot backEvaluated = mpvBackCacheController.snapshot();
        long currentBack = !automaticBack
                ? mpv.getConfiguredBackCacheBytes()
                : backEvaluated.controlledTargetBytes() >= 0
                ? backEvaluated.controlledTargetBytes() : 0;
        long targetBack = backDecision != null && backDecision.requestsApply()
                ? backDecision.targetBytes() : currentBack;
        if (automaticForward && !automaticBack && targetBack > targetForward) {
            targetForward = targetBack;
        } else if (!automaticForward && automaticBack
                && targetBack > targetForward) {
            targetBack = targetForward;
        }

        boolean cacheExpansionAllowed = host.isExperimentAllowed(
                PlaybackExperimentPolicy.Action.MPV_CACHE_EXPANSION);
        boolean forwardPolicyRequested = forwardDecision != null
                && forwardDecision.requestsApply();
        boolean backPolicyRequested = backDecision != null
                && backDecision.requestsApply();
        boolean forwardRequested = forwardPolicyRequested
                && (targetForward <= currentForward || cacheExpansionAllowed);
        boolean backRequested = backPolicyRequested
                && (targetBack <= currentBack || cacheExpansionAllowed);
        boolean expansionSuppressed = forwardPolicyRequested && !forwardRequested
                || backPolicyRequested && !backRequested;
        if (forwardPolicyRequested && !forwardRequested) {
            targetForward = currentForward;
        }
        if (backPolicyRequested && !backRequested) {
            targetBack = currentBack;
        }
        boolean applyRequested = forwardRequested || backRequested;
        boolean forwardStarted = !forwardRequested;
        boolean backStarted = !backRequested;
        boolean coordinatorStarted = false;
        boolean adopted = false;
        boolean accepted = false;
        boolean staged = false;
        String applyResult = expansionSuppressed
                ? "experiment-disabled" : "not-requested";
        MpvCacheTargetCoordinator.Decision combinedDecision = null;
        if (applyRequested) {
            combinedDecision = mpvCacheTargetCoordinator.evaluate(
                    host.getPlaybackAutoSession(), targetForward, targetBack);
            if (forwardRequested) {
                forwardStarted = mpvForwardCacheController.beginApply(
                        host.getPlaybackAutoSession(), forwardDecision);
            }
            if (backRequested) {
                backStarted = mpvBackCacheController.beginApply(
                        host.getPlaybackAutoSession(), backDecision);
            }
            boolean controllersStarted = forwardStarted && backStarted;
            if (controllersStarted && combinedDecision.requestsApply()) {
                coordinatorStarted = mpvCacheTargetCoordinator.beginApply(
                        host.getPlaybackAutoSession(), combinedDecision);
                MpvPlayer.AutoCacheBaselineResult result = coordinatorStarted
                        ? mpv.applyAutoCacheBaseline(
                        host.getPlaybackTrace().current(), targetForward, targetBack)
                        : MpvPlayer.AutoCacheBaselineResult.REJECTED;
                accepted = result.accepted();
                staged = result.staged();
                applyResult = result.label();
                if (coordinatorStarted) {
                    mpvCacheTargetCoordinator.completeApply(
                            host.getPlaybackAutoSession(), combinedDecision, accepted, staged);
                }
            } else if (controllersStarted
                    && combinedDecision.reason()
                    == MpvCacheTargetCoordinator.Reason.TARGET_STABLE) {
                adopted = true;
                accepted = true;
                applyResult = "already-applied";
            } else if (!controllersStarted) {
                applyResult = "controller-rejected";
            } else {
                applyResult = combinedDecision.reason().label();
            }
            boolean controllerAccepted = accepted && (coordinatorStarted || adopted);
            if (forwardRequested && forwardStarted) {
                mpvForwardCacheController.completeApply(
                        host.getPlaybackAutoSession(), forwardDecision,
                        controllerAccepted, staged, now);
            }
            if (backRequested && backStarted) {
                mpvBackCacheController.completeApply(
                        host.getPlaybackAutoSession(), backDecision,
                        controllerAccepted, staged, now);
            }
            if (controllerAccepted) {
                mpvForwardCacheController.syncNativeTarget(
                        host.getPlaybackAutoSession(), targetForward);
                mpvBackCacheController.syncNativeTarget(
                        host.getPlaybackAutoSession(), targetBack);
            }
        }

        MpvForwardCacheController.Snapshot forwardAfter = mpvForwardCacheController.snapshot();
        MpvBackCacheController.Snapshot backAfter = mpvBackCacheController.snapshot();
        PlayerCacheState cache = mpv.getAutoCacheSnapshot();
        boolean commitStarted = coordinatorStarted || adopted;
        String forwardApplyResult = forwardPolicyRequested && !forwardRequested
                ? "experiment-disabled" : applyResult;
        String backApplyResult = backPolicyRequested && !backRequested
                ? "experiment-disabled" : applyResult;
        if (forwardDecision != null) {
            publishMpvForwardCacheDecision(
                    forwardTrigger,
                    forwardAssessment,
                    forwardDecision,
                    forwardAfter,
                    cache,
                    context,
                    now,
                    forwardRequested && forwardStarted && commitStarted,
                    accepted,
                    staged,
                    forwardApplyResult);
        }
        if (backDecision != null) {
            publishMpvBackCacheDecision(
                    backTrigger,
                    backAssessment,
                    backDecision,
                    backAfter,
                    cache,
                    context,
                    now,
                    backRequested && backStarted && commitStarted,
                    accepted,
                    staged,
                    backApplyResult);
        }
        if (applyRequested || expansionSuppressed) {
            PlaybackTrace.log("mpv-cache-target", host.getPlaybackTrace().current(),
                    "forward=%d back=%d totalBudget=%d forwardChanged=%s backChanged=%s coordinator=%s result=%s",
                    targetForward, targetBack, backAssessment.totalBudgetBytes(),
                    forwardRequested, backRequested,
                    combinedDecision == null ? "none" : combinedDecision.reason().label(),
                    applyResult);
        }
    }

    private void evaluateMpvPreload(
            MpvPlayerEngine mpv,
            PlaybackAutoContext context,
            MpvResourcePressureController.Decision resourceDecision,
            boolean automatic,
            boolean performancePriority,
            long now) {
        boolean experimentalAutomatic = automatic && host.isExperimentAllowed(
                PlaybackExperimentPolicy.Action.MPV_AUTO_PRELOAD);
        MpvPlayer.AutoHlsRuntimeSnapshot hls = mpv.getAutoHlsRuntimeSnapshot();
        MpvPlayer.AutoHlsPreloadRuntimeSnapshot proxy =
                mpv.getAutoHlsPreloadRuntimeSnapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                context.resource().protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                context.resource().streamKind();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPathFact =
                context.path().playerPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstreamPathFact =
                context.path().upstreamPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamStateFact =
                context.path().upstreamState();
        PlaybackAutoContext.Fact<Long> bufferFact =
                context.runtime().bufferedDurationMs();
        PlaybackAutoContext.Fact<Integer> rebufferFact =
                context.runtime().rebufferCount();
        PlaybackAutoContext.Fact<Long> positionFact =
                context.runtime().positionMs();
        boolean protocolUsable = protocolFact.isUsable(now);
        boolean streamUsable = streamFact.isUsable(now);
        PlaybackAutoContext.StreamKind streamKind = streamUsable
                ? streamFact.value() : PlaybackAutoContext.StreamKind.UNKNOWN;
        if (streamKind == PlaybackAutoContext.StreamKind.VOD && !proxy.vod()) {
            streamUsable = false;
            streamKind = PlaybackAutoContext.StreamKind.UNKNOWN;
        }
        boolean bufferUsable = bufferFact.isUsable(now);
        long selectedBits = hls.selectedVariant() == null
                ? 0 : hls.selectedVariant().selectionBitsPerSecond();
        if (selectedBits <= 0 && hls.variants().size() == 1) {
            selectedBits = hls.variants().get(0).selectionBitsPerSecond();
        }
        if (selectedBits <= 0) {
            PlaybackAutoContext.Fact<Long> mediaBitrateFact =
                    context.runtime().mediaBitrateBitsPerSecond();
            if (mediaBitrateFact.isUsable(now) && mediaBitrateFact.value() > 0) {
                selectedBits = mediaBitrateFact.value();
            }
        }
        boolean buffering = host.getPlayer() != null
                && host.getPlayer().getPlaybackState() == Player.STATE_BUFFERING;
        MpvPreloadPolicy.Request request = new MpvPreloadPolicy.Request(
                experimentalAutomatic,
                host.isMpv(),
                performancePriority,
                proxy.preloadConfigured(),
                protocolUsable ? protocolFact.value()
                        : PlaybackAutoContext.Protocol.UNKNOWN,
                protocolUsable,
                streamKind,
                streamUsable,
                playerPathFact.isUsable(now) ? playerPathFact.value()
                        : PlaybackAutoContext.PathKind.UNKNOWN,
                playerPathFact.isUsable(now),
                upstreamPathFact.isUsable(now) ? upstreamPathFact.value()
                        : PlaybackAutoContext.PathKind.UNKNOWN,
                upstreamPathFact.isUsable(now),
                upstreamStateFact.isUsable(now) ? upstreamStateFact.value()
                        : PlaybackAutoContext.UpstreamState.UNKNOWN,
                upstreamStateFact.isUsable(now),
                resourceDecision.preloadAllowed(),
                proxy.cacheEnabled(),
                proxy.cacheStorageKnown(),
                proxy.cacheBudgetAvailable(),
                proxy.cacheCircuitOpen(),
                proxy.upstreamBitsPerSecond(),
                proxy.throughputKnown(),
                proxy.throughputFresh(),
                proxy.throughputSampleAtElapsedMs(),
                selectedBits,
                bufferUsable,
                bufferUsable ? bufferFact.value() : 0,
                bufferUsable ? bufferFact.sampledAtElapsedMs() : -1,
                rebufferFact.hasValue() ? rebufferFact.value() : 0,
                buffering,
                false,
                false,
                proxy.foregroundRequests(),
                context.revision());
        MpvPreloadController.Snapshot before = mpvPreloadController.snapshot();
        MpvPreloadController.Decision decision = mpvPreloadController.evaluate(
                host.getPlaybackAutoSession(), context.session(), request, now);
        boolean automaticPreloadManaged = MpvPreloadPolicy.ownsProxyControl(
                automatic, performancePriority);
        boolean gateChanged = mpv.updateAutomaticPreloadControl(
                automaticPreloadManaged,
                experimentalAutomatic && resourceDecision.preloadAllowed(),
                experimentalAutomatic && decision.preloadAllowed());
        boolean scheduled = false;
        if (experimentalAutomatic && automaticPreloadManaged
                && decision.preloadAllowed()
                && positionFact.isUsable(now)) {
            mpv.requestAutomaticHlsPreload(Math.max(0, positionFact.value()));
            scheduled = true;
        }
        publishMpvPreloadDecision(
                before, decision, request, proxy, gateChanged, scheduled, now);
    }

    private void publishMpvPreloadDecision(
            MpvPreloadController.Snapshot before,
            MpvPreloadController.Decision decision,
            MpvPreloadPolicy.Request request,
            MpvPlayer.AutoHlsPreloadRuntimeSnapshot proxy,
            boolean gateChanged,
            boolean scheduled,
            long now) {
        PlaybackTelemetry.DecisionOutcome outcome;
        if (decision.policyReason() == MpvPreloadPolicy.Reason.CONFIG_PRIORITY
                || decision.policyReason() == MpvPreloadPolicy.Reason.NOT_AUTOMATIC) {
            outcome = PlaybackTelemetry.DecisionOutcome.SUPPRESSED;
        } else if (gateChanged || decision.cancellationRequested()
                || decision.action() == MpvPreloadController.Action.ALLOW) {
            outcome = PlaybackTelemetry.DecisionOutcome.REQUESTED;
        } else if (decision.changed()) {
            outcome = PlaybackTelemetry.DecisionOutcome.OBSERVED;
        } else {
            outcome = PlaybackTelemetry.DecisionOutcome.HELD;
        }
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        addNumberInput(inputs, "upstream_bps",
                request.throughputKnown() ? request.upstreamBitsPerSecond() : -1,
                PlaybackAutoContext.ValueSource.PROXY,
                request.throughputKnown()
                        ? PlaybackAutoContext.Confidence.MEDIUM
                        : PlaybackAutoContext.Confidence.UNKNOWN);
        addNumberInput(inputs, "throughput_age_ms",
                request.throughputKnown() ? proxy.throughputAgeMs() : -1,
                PlaybackAutoContext.ValueSource.PROXY,
                request.throughputKnown()
                        ? PlaybackAutoContext.Confidence.MEDIUM
                        : PlaybackAutoContext.Confidence.UNKNOWN);
        addNumberInput(inputs, "selected_bps",
                request.selectedBitsPerSecond() > 0
                        ? request.selectedBitsPerSecond() : -1,
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                request.selectedBitsPerSecond() > 0
                        ? PlaybackAutoContext.Confidence.HIGH
                        : PlaybackAutoContext.Confidence.UNKNOWN);
        addNumberInput(inputs, "ratio_permille", decision.ratioPermille(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "buffer_ms",
                request.bufferUsable() ? request.bufferedDurationMs() : -1,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                request.bufferUsable()
                        ? PlaybackAutoContext.Confidence.HIGH
                        : PlaybackAutoContext.Confidence.UNKNOWN);
        addNumberInput(inputs, "foreground", request.foregroundRequests(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cache_physical", proxy.cachePhysicalBytes(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cache_reserved", proxy.cacheReservedBytes(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cache_write_budget",
                proxy.cacheNewWriteBudgetBytes(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cache_effective",
                proxy.cacheEffectiveCapacityBytes(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "recovery_samples", decision.recoverySamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "recovery_ms", decision.recoveryRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "throughput_filter",
                proxy.lastThroughputRejectReason(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "resource_allowed", request.resourcePreloadAllowed(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "cache_budget", request.cacheBudgetAvailable(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "cache_circuit", request.cacheCircuitOpen(),
                PlaybackAutoContext.ValueSource.PROXY,
                PlaybackAutoContext.Confidence.HIGH));
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_PRELOAD,
                        outcome,
                        before.lastDecision().targetLabel(),
                        decision.targetLabel(),
                        scheduled ? "scheduled" : gateChanged
                                ? "gate-updated" : "held",
                        decision.policyReason().label(),
                        decision.reason().label(),
                        inputs),
                now);
        PlaybackTrace.log("mpv-preload", host.getPlaybackTrace().current(),
                "state=%s action=%s reason=%s policy=%s threads=%d ratio=%d upstream=%d selected=%d buffer=%d foreground=%d cachePhysical=%d cacheReserved=%d cacheBudget=%d cacheEffective=%d circuit=%s recoverySamples=%d recoveryMs=%d gateChanged=%s scheduled=%s",
                decision.state().label(), decision.action().label(),
                decision.reason().label(), decision.policyReason().label(),
                decision.concurrency(), decision.ratioPermille(),
                request.upstreamBitsPerSecond(), request.selectedBitsPerSecond(),
                request.bufferedDurationMs(), request.foregroundRequests(),
                proxy.cachePhysicalBytes(), proxy.cacheReservedBytes(),
                proxy.cacheNewWriteBudgetBytes(),
                proxy.cacheEffectiveCapacityBytes(), proxy.cacheCircuitOpen(),
                decision.recoverySamples(), decision.recoveryRemainingMs(),
                gateChanged, scheduled);
    }

    private static MpvResourcePressureController.Trigger mpvResourceTrigger(
            MpvForwardCacheController.Trigger forwardTrigger,
            MpvBackCacheController.Trigger backTrigger) {
        if (forwardTrigger == MpvForwardCacheController.Trigger.RESOURCE
                || backTrigger == MpvBackCacheController.Trigger.RESOURCE) {
            return MpvResourcePressureController.Trigger.SYSTEM;
        }
        if (forwardTrigger == MpvForwardCacheController.Trigger.MEMORY
                || backTrigger == MpvBackCacheController.Trigger.MEMORY) {
            return MpvResourcePressureController.Trigger.MEMORY;
        }
        if (forwardTrigger == MpvForwardCacheController.Trigger.REBUILD
                || backTrigger == MpvBackCacheController.Trigger.REBUILD) {
            return MpvResourcePressureController.Trigger.REBUILD;
        }
        if (forwardTrigger == MpvForwardCacheController.Trigger.BASELINE
                || backTrigger == MpvBackCacheController.Trigger.BASELINE) {
            return MpvResourcePressureController.Trigger.BASELINE;
        }
        return MpvResourcePressureController.Trigger.RUNTIME;
    }

    private void publishMpvResourcePressureDecision(
            MpvResourcePressureController.Snapshot before,
            MpvResourcePressurePolicy.Assessment assessment,
            MpvResourcePressureController.Decision decision,
            boolean preloadGateChanged,
            long now) {
        MpvResourcePressureController.Snapshot previous = before == null
                ? mpvResourcePressureController.snapshot() : before;
        PlaybackTelemetry.DecisionOutcome outcome = !assessment.active()
                && assessment.reason() == MpvResourcePressurePolicy.Reason.CONFIG_PRIORITY
                ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : decision.changed() || preloadGateChanged
                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                : decision.action() == MpvResourcePressureController.Action.STABLE
                ? PlaybackTelemetry.DecisionOutcome.OBSERVED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        String oldValue = mpvResourceTargetLabel(
                previous.forwardCeilingBytes(),
                previous.backCeilingBytes(),
                previous.preloadAllowed());
        String targetValue = decision.targetLabel();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "level",
                assessment.level().label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(assessment.memoryUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "memory_pressure",
                assessment.memoryPressure().label(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("memory_pressure"));
        inputs.add(assessment.thermalUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "thermal",
                assessment.thermal().label(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("thermal"));
        inputs.add(assessment.powerUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "power",
                assessment.power().label(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("power"));
        inputs.add(assessment.networkCostUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "network_cost",
                assessment.networkCost().label(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("network_cost"));
        inputs.add(assessment.networkSnapshotUsable()
                ? PlaybackTelemetry.DecisionInput.text(
                "data_saver",
                assessment.networkSnapshot().dataSaverState().label(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("data_saver"));
        addNumberInput(inputs, "forward_ceiling", decision.forwardCeilingBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "back_ceiling", decision.backCeilingBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "expansion_allowed",
                decision.expansionAllowed(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "preload_allowed",
                decision.preloadAllowed(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "preload_gate_changed",
                preloadGateChanged,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        addNumberInput(inputs, "normal_samples", decision.normalSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_RESOURCE_PRESSURE,
                        outcome,
                        oldValue,
                        targetValue,
                        targetValue,
                        decision.policyReason().label(),
                        decision.reason().label(),
                        inputs),
                now);
        PlaybackTrace.log("mpv-resource-pressure", host.getPlaybackTrace().current(),
                "state=%s trigger=%s input=%s action=%s reason=%s policy=%s forwardCeiling=%d backCeiling=%d expansion=%s preload=%s gateChanged=%s normalSamples=%d cooldown=%d",
                mpvResourcePressureController.snapshot().state().label(),
                decision.trigger().label(),
                decision.inputLevel().label(),
                decision.action().label(),
                decision.reason().label(),
                decision.policyReason().label(),
                decision.forwardCeilingBytes(),
                decision.backCeilingBytes(),
                decision.expansionAllowed(),
                decision.preloadAllowed(),
                preloadGateChanged,
                decision.normalSamples(),
                decision.cooldownRemainingMs());
    }

    private static String mpvResourceTargetLabel(
            long forwardBytes,
            long backBytes,
            boolean preloadAllowed) {
        return "forward-" + Math.max(0, forwardBytes)
                + "-back-" + Math.max(0, backBytes)
                + "-preload-" + preloadAllowed;
    }

    private void publishMpvForwardCacheDecision(
            MpvForwardCacheController.Trigger trigger,
            MpvForwardCachePolicy.Assessment assessment,
            MpvForwardCacheController.Decision decision,
            MpvForwardCacheController.Snapshot after,
            PlayerCacheState cache,
            PlaybackAutoContext context,
            long now,
            boolean started,
            boolean accepted,
            boolean staged,
            String applyResult) {
        PlaybackTelemetry.DecisionOutcome outcome;
        if (!assessment.active()
                && assessment.inactiveReason() == MpvForwardCachePolicy.Reason.CONFIG_PRIORITY) {
            outcome = PlaybackTelemetry.DecisionOutcome.SUPPRESSED;
        } else if ("experiment-disabled".equals(applyResult)) {
            outcome = PlaybackTelemetry.DecisionOutcome.SUPPRESSED;
        } else if (!decision.requestsApply()) {
            outcome = PlaybackTelemetry.DecisionOutcome.OBSERVED;
        } else if (!started || !accepted) {
            outcome = PlaybackTelemetry.DecisionOutcome.FAILED;
        } else if (staged) {
            outcome = PlaybackTelemetry.DecisionOutcome.REQUESTED;
        } else {
            outcome = PlaybackTelemetry.DecisionOutcome.APPLIED;
        }
        String oldValue = forwardTargetLabel(decision.oldNativeTargetBytes());
        String targetValue = forwardTargetLabel(decision.targetBytes());
        String resultValue = forwardTargetLabel(after.nativeTargetBytes());
        String suppression = "experiment-disabled".equals(applyResult)
                ? "experiment-disabled"
                : !decision.requestsApply()
                ? decision.reason().label()
                : !started
                ? "action-rejected"
                : !accepted
                ? "native-apply-failed"
                : "none";
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        addBitrateInput(inputs, "average_bps", assessment.averageBitrate());
        addBitrateInput(inputs, "peak_bps", assessment.peakBitrate());
        addNumberInput(inputs, "media_target", assessment.mediaReliable()
                ? assessment.mediaTargetBytes() : -1,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "safe_capacity", assessment.safeTargetBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "initial_baseline", after.initialBaselineBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "actual_fw", cache.forwardBytes(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "actual_total", cache.totalBytes(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> memoryFact =
                context.device().memorySnapshot();
        addNumberInput(inputs, "native_heap",
                memoryFact.isUsable(now) && memoryFact.value().nativeHeapAllocatedBytes() != null
                        ? memoryFact.value().nativeHeapAllocatedBytes() : -1,
                memoryFact.isUsable(now) ? memoryFact.source() : PlaybackAutoContext.ValueSource.UNKNOWN,
                memoryFact.isUsable(now) ? memoryFact.confidence() : PlaybackAutoContext.Confidence.UNKNOWN);
        PlaybackAutoContext.Fact<Long> pssFact = context.device().diagnosticPssBytes();
        addNumberInput(inputs, "diagnostic_pss",
                pssFact.isUsable(now) ? pssFact.value() : -1,
                pssFact.isUsable(now) ? pssFact.source() : PlaybackAutoContext.ValueSource.UNKNOWN,
                pssFact.isUsable(now) ? pssFact.confidence() : PlaybackAutoContext.Confidence.UNKNOWN);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                context.device().memoryPressure();
        if (pressureFact.isUsable(now)) {
            inputs.add(PlaybackTelemetry.DecisionInput.text(
                    "memory_pressure", pressureFact.value().label(),
                    pressureFact.source(), pressureFact.confidence()));
        } else {
            inputs.add(PlaybackTelemetry.DecisionInput.unknown("memory_pressure"));
        }
        addNumberInput(inputs, "stable_samples", after.demandSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_FORWARD_CACHE,
                        outcome,
                        oldValue,
                        targetValue,
                        resultValue,
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
        PlaybackTrace.log("mpv-forward-cache", host.getPlaybackTrace().current(),
                "state=%s trigger=%s action=%s reason=%s old=%s target=%s result=%s fw=%d total=%d stable=%d cooldown=%d apply=%s",
                after.state().label(), trigger == null ? "runtime" : trigger.label(),
                decision.action().label(), decision.reason().label(), oldValue,
                targetValue, resultValue, cache.forwardBytes(), cache.totalBytes(),
                after.demandSamples(), decision.cooldownRemainingMs(), applyResult);
    }

    private void publishMpvBackCacheDecision(
            MpvBackCacheController.Trigger trigger,
            MpvBackCachePolicy.Assessment assessment,
            MpvBackCacheController.Decision decision,
            MpvBackCacheController.Snapshot after,
            PlayerCacheState cache,
            PlaybackAutoContext context,
            long now,
            boolean started,
            boolean accepted,
            boolean staged,
            String applyResult) {
        PlaybackTelemetry.DecisionOutcome outcome;
        if (!assessment.active()
                && assessment.reason() == MpvBackCachePolicy.Reason.CONFIG_PRIORITY) {
            outcome = PlaybackTelemetry.DecisionOutcome.SUPPRESSED;
        } else if ("experiment-disabled".equals(applyResult)) {
            outcome = PlaybackTelemetry.DecisionOutcome.SUPPRESSED;
        } else if (!decision.requestsApply()) {
            outcome = PlaybackTelemetry.DecisionOutcome.OBSERVED;
        } else if (!started || !accepted) {
            outcome = PlaybackTelemetry.DecisionOutcome.FAILED;
        } else if (staged) {
            outcome = PlaybackTelemetry.DecisionOutcome.REQUESTED;
        } else {
            outcome = PlaybackTelemetry.DecisionOutcome.APPLIED;
        }
        String oldValue = backTargetLabel(decision.oldNativeTargetBytes());
        String targetValue = backTargetLabel(decision.targetBytes());
        String resultValue = backTargetLabel(after.nativeTargetBytes());
        String suppression = "experiment-disabled".equals(applyResult)
                ? "experiment-disabled"
                : !decision.requestsApply()
                ? decision.reason().label()
                : !started
                ? "action-rejected"
                : !accepted
                ? "native-apply-failed"
                : "none";
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        addNumberInput(inputs, "seek_distance_ms",
                decision.lastBackwardSeekDistanceMs() > 0
                        ? decision.lastBackwardSeekDistanceMs() : -1,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                decision.lastBackwardSeekDistanceMs() > 0
                        ? PlaybackAutoContext.Confidence.HIGH
                        : PlaybackAutoContext.Confidence.UNKNOWN);
        addNumberInput(inputs, "seek_evidence", decision.backwardSeekEvidence(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "learned_target", decision.learnedTargetBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "safe_back", assessment.safeBackBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "total_budget", assessment.totalBudgetBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "forward_target", assessment.forwardBytes(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "actual_fw", cache.forwardBytes(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "actual_total", cache.totalBytes(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                context.device().memoryPressure();
        if (pressureFact.isUsable(now)) {
            inputs.add(PlaybackTelemetry.DecisionInput.text(
                    "memory_pressure", pressureFact.value().label(),
                    pressureFact.source(), pressureFact.confidence()));
        } else {
            inputs.add(PlaybackTelemetry.DecisionInput.unknown("memory_pressure"));
        }
        addNumberInput(inputs, "normal_samples", after.normalSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "apply_attempts", after.applyAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_BACK_CACHE,
                        outcome,
                        oldValue,
                        targetValue,
                        resultValue,
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
        PlaybackTrace.log("mpv-back-cache", host.getPlaybackTrace().current(),
                "state=%s trigger=%s action=%s reason=%s old=%s target=%s result=%s seekDistance=%d seekEvidence=%d learned=%d safe=%d forward=%d totalBudget=%d fw=%d total=%d cooldown=%d apply=%s",
                after.state().label(), trigger == null ? "runtime" : trigger.label(),
                decision.action().label(), decision.reason().label(), oldValue,
                targetValue, resultValue, decision.lastBackwardSeekDistanceMs(),
                decision.backwardSeekEvidence(), decision.learnedTargetBytes(),
                assessment.safeBackBytes(), assessment.forwardBytes(),
                assessment.totalBudgetBytes(), cache.forwardBytes(), cache.totalBytes(),
                decision.cooldownRemainingMs(), applyResult);
    }

    private boolean isCurrentMpvMediaSeekable() {
        if (host.getPlayer() == null
                || !host.getPlayer().isCommandAvailable(host.getPlayer().COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return false;
        }
        try {
            Timeline timeline = host.getPlayer().getCurrentTimeline();
            int index = host.getPlayer().getCurrentMediaItemIndex();
            if (timeline.isEmpty() || index < 0 || index >= timeline.getWindowCount()) return false;
            Timeline.Window window = new Timeline.Window();
            timeline.getWindow(index, window);
            return window.isSeekable;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCurrentMpvMediaLive() {
        if (host.getPlayer() == null) return false;
        try {
            return host.getPlayer().isCurrentMediaItemLive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String forwardTargetLabel(long bytes) {
        return bytes < 0 ? "unknown" : "forward-" + bytes;
    }

    private static String backTargetLabel(long bytes) {
        return bytes < 0 ? "unknown" : "back-" + bytes;
    }

    private static void addBitrateInput(
            List<PlaybackTelemetry.DecisionInput> inputs,
            String name,
            MpvForwardCachePolicy.BitrateEvidence evidence) {
        if (evidence != null && evidence.reliable()) {
            inputs.add(PlaybackTelemetry.DecisionInput.number(
                    name, evidence.bitsPerSecond(), evidence.valueSource(), evidence.confidence()));
        } else {
            inputs.add(PlaybackTelemetry.DecisionInput.unknown(name));
        }
    }

    private static void addNumberInput(
            List<PlaybackTelemetry.DecisionInput> inputs,
            String name,
            long value,
            PlaybackAutoContext.ValueSource source,
            PlaybackAutoContext.Confidence confidence) {
        if (value < 0 || source == PlaybackAutoContext.ValueSource.UNKNOWN
                || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
            inputs.add(PlaybackTelemetry.DecisionInput.unknown(name));
        } else {
            inputs.add(PlaybackTelemetry.DecisionInput.number(name, value, source, confidence));
        }
    }

    private static List<MpvHlsVariantPolicy.Variant> toPolicyVariants(
            List<MpvPlayer.HlsVariant> variants) {
        if (variants == null || variants.isEmpty()) return List.of();
        List<MpvHlsVariantPolicy.Variant> result =
                new ArrayList<>(variants.size());
        for (MpvPlayer.HlsVariant variant : variants) {
            MpvHlsVariantPolicy.Variant mapped = toPolicyVariant(variant);
            if (mapped != null) result.add(mapped);
        }
        return List.copyOf(result);
    }

    @Nullable
    private static MpvHlsVariantPolicy.Variant toPolicyVariant(
            @Nullable MpvPlayer.HlsVariant variant) {
        if (variant == null) return null;
        return new MpvHlsVariantPolicy.Variant(
                variant.bandwidthBitsPerSecond(),
                variant.averageBandwidthBitsPerSecond(),
                variant.width(),
                variant.height());
    }

    private static String hlsOptionLabel(String option) {
        if (TextUtils.isEmpty(option)) return "unset";
        String value = option.trim();
        if ("min".equals(value) || "max".equals(value)
                || "no".equals(value)) return value;
        long bits = optionBits(value);
        return bits > 0 ? "bps-" + bits : "invalid";
    }

    private static long optionBits(String option) {
        if (TextUtils.isEmpty(option)) return 0;
        try {
            return Math.max(0, Long.parseLong(option.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record MpvHlsApplyResult(
            boolean started,
            boolean optionAccepted,
            boolean reloadStarted,
            boolean succeeded,
            String optionResult) {

        private MpvHlsApplyResult {
            optionResult = optionResult == null ? "unknown" : optionResult;
        }

        private static MpvHlsApplyResult notRequested() {
            return new MpvHlsApplyResult(
                    false, false, false, false, "not-requested");
        }

        private static MpvHlsApplyResult rejected() {
            return new MpvHlsApplyResult(
                    false, false, false, false, "rejected");
        }
    }

    private void evaluateMpvHlsVariant(
            PlaybackTelemetry.RuntimeObservation telemetry,
            long now) {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !host.getPlaybackAutoSession().active()) return;
        PlaybackAutoContext context = host.getAutoContextStore().snapshot();
        MpvPlayer.AutoHlsRuntimeSnapshot runtime =
                mpv.getAutoHlsRuntimeSnapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                context.resource().protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                context.resource().streamKind();
        boolean protocolUsable = protocolFact.isUsable(now);
        boolean streamUsable = streamFact.isUsable(now);
        PlaybackTelemetry.Metric<Long> bufferedMetric = telemetry == null
                ? PlaybackTelemetry.Metric.unknown()
                : telemetry.bufferedDurationMs();
        PlaybackTelemetry.Metric<Long> positionMetric = telemetry == null
                ? PlaybackTelemetry.Metric.unknown() : telemetry.positionMs();
        PlaybackTelemetry.Metric<Integer> rebufferMetric = telemetry == null
                ? PlaybackTelemetry.Metric.unknown() : telemetry.rebufferCount();
        boolean buffering = host.getPlayer() != null
                && host.getPlayer().getPlaybackState() == Player.STATE_BUFFERING;
        MpvHlsVariantController.RuntimeObservation observation =
                new MpvHlsVariantController.RuntimeObservation(
                        PlaybackPerformanceSetting.isAuto(
                                PlayerSetting.MPV,
                                PlaybackPerformanceCatalog.MPV_HLS_BITRATE)
                                && host.isExperimentAllowed(
                                PlaybackExperimentPolicy.Action.MPV_HLS_RUNTIME_RELOAD),
                        host.isMpv(),
                        MpvPerformanceSetting.isPerformancePriority(),
                        protocolUsable ? protocolFact.value()
                                : PlaybackAutoContext.Protocol.UNKNOWN,
                        protocolUsable,
                        streamUsable ? streamFact.value()
                                : PlaybackAutoContext.StreamKind.UNKNOWN,
                        streamUsable,
                        toPolicyVariants(runtime.variants()),
                        toPolicyVariant(runtime.selectedVariant()),
                        runtime.underrun(),
                        runtime.underrunCount(),
                        rebufferMetric.known()
                                ? Math.max(0, rebufferMetric.value())
                                : host.getRebufferCount(),
                        buffering,
                        bufferedMetric.known(),
                        bufferedMetric.known()
                                ? Math.max(0, bufferedMetric.value()) : 0,
                        runtime.rawInputBitsPerSecond(),
                        runtime.rawInputRateUsable(),
                        positionMetric.known()
                                ? Math.max(0, positionMetric.value()) : 0);
        MpvHlsVariantController.Decision decision =
                mpvHlsVariantController.evaluateRuntime(
                        host.getPlaybackAutoSession(), context.session(), observation, now);
        MpvHlsApplyResult apply = decision.requestsApply()
                ? executeMpvHlsVariantDecision(mpv, decision, now)
                : MpvHlsApplyResult.notRequested();
        MpvHlsVariantController.Snapshot snapshot =
                mpvHlsVariantController.snapshot();
        if (decision.reason() == MpvHlsVariantController.Reason.ROLLBACK_TIMEOUT
                || decision.requestsApply() && !apply.succeeded()) {
            mpvHlsManagedReload = false;
        }
        PlaybackTelemetry.DecisionOutcome outcome =
                decision.policyReason() == MpvHlsVariantPolicy.Reason.NOT_AUTOMATIC
                        || decision.policyReason()
                        == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                        ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                        : !decision.requestsApply()
                        ? PlaybackTelemetry.DecisionOutcome.HELD
                        : apply.succeeded()
                        ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                        : PlaybackTelemetry.DecisionOutcome.FAILED;
        String suppression = decision.policyReason()
                == MpvHlsVariantPolicy.Reason.NOT_AUTOMATIC
                ? "not-automatic"
                : decision.policyReason()
                == MpvHlsVariantPolicy.Reason.CONFIG_PRIORITY
                ? "mpv-conf-priority"
                : decision.requestsApply() && !apply.started()
                ? "action-rejected"
                : decision.requestsApply() && !apply.optionAccepted()
                ? "native-apply-failed"
                : decision.requestsApply() && !apply.reloadStarted()
                ? "reload-start-failed"
                : decision.reason().label();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        addNumberInput(inputs, "selected_bps",
                decision.targetBitsPerSecond() > 0
                        ? optionBits(decision.oldOption())
                        : runtime.selectedVariant() == null
                        ? -1 : runtime.selectedVariant().selectionBitsPerSecond(),
                PlaybackAutoContext.ValueSource.MANIFEST,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "target_bps", decision.targetBitsPerSecond(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH);
        addNumberInput(inputs, "raw_input_bps",
                runtime.rawInputRateUsable()
                        ? runtime.rawInputBitsPerSecond() : -1,
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "buffered_ms",
                bufferedMetric.known() ? bufferedMetric.value() : -1,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "underrun_count", runtime.underrunCount(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "rebuffer_count", observation.rebufferCount(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", decision.riskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "hard_risk_samples", decision.hardRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "throughput_risk_samples", decision.throughputRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "buffer_risk_samples", decision.bufferRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts", snapshot.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_HLS_VARIANT,
                        outcome,
                        hlsOptionLabel(decision.oldOption()),
                        hlsOptionLabel(decision.targetOption()),
                        apply.succeeded()
                                ? hlsOptionLabel(decision.targetOption())
                                : hlsOptionLabel(decision.oldOption()),
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
    }

    private MpvHlsApplyResult executeMpvHlsVariantDecision(
            MpvPlayerEngine mpv,
            MpvHlsVariantController.Decision decision,
            long now) {
        boolean started = mpvHlsVariantController.beginApply(
                host.getPlaybackAutoSession(), decision, now);
        if (!started) return MpvHlsApplyResult.rejected();
        MpvPlayer.AutoHlsBitrateResult option = mpv.applyAutoHlsBitrate(
                host.getPlaybackTrace().current(), decision.targetOption());
        boolean reloadStarted = option.accepted()
                && restartMpvHlsVariant(decision);
        boolean succeeded = option.accepted() && reloadStarted;
        if (!succeeded && !TextUtils.isEmpty(decision.oldOption())) {
            mpv.applyAutoHlsBitrate(
                    host.getPlaybackTrace().current(), decision.oldOption());
        }
        mpvHlsVariantController.completeApply(
                host.getPlaybackAutoSession(),
                decision,
                succeeded,
                option.staged(),
                SystemClock.elapsedRealtime());
        return new MpvHlsApplyResult(
                true, option.accepted(), reloadStarted, succeeded,
                option.label());
    }

    private boolean restartMpvHlsVariant(
            MpvHlsVariantController.Decision decision) {
        if (!decision.reloadsMedia()
                || host.getSpec() == null
                || TextUtils.isEmpty(host.getSpec().getUrl())
                || host.getMpvEngine() == null
                || host.getPlayer() == null) return false;
        boolean wasPlayWhenReady = host.getPlayer().getPlayWhenReady();
        float speed = host.getSpeed();
        boolean repeat = host.isRepeatOne();
        long position = decision.preservesVodPosition()
                ? decision.resumePositionMs() : C.TIME_UNSET;
        try {
            mpvHlsManagedReload = true;
            PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTrace().current(),
                    "action=%s stream=%s target=%d resume=%d play=%s",
                    decision.action().label(), decision.streamKind().label(),
                    decision.targetBitsPerSecond(),
                    position == C.TIME_UNSET ? 0 : position,
                    wasPlayWhenReady);
            boolean restarted = host.restartForMpvHlsVariant(
                    position, wasPlayWhenReady, speed, repeat);
            if (!restarted) {
                mpvHlsManagedReload = false;
            }
            return restarted;
        } catch (Throwable error) {
            mpvHlsManagedReload = false;
            PlaybackTrace.log("mpv-hls-variant", host.getPlaybackTrace().current(),
                    "action=%s result=failed errorType=%s",
                    decision.action().label(),
                    error.getClass().getSimpleName());
            return false;
        }
    }

    public void onPlaybackReady(long now) {
        onMpvHlsPlaybackReady(now);
    }

    public void onMpvHlsPlaybackReady(long now) {
        if (!host.isMpv() || !host.getPlaybackAutoSession().active()) return;
        MpvHlsVariantController.Completion completion =
                mpvHlsVariantController.onPlaybackReady(
                        host.getPlaybackAutoSession(), now);
        MpvHlsVariantController.Snapshot snapshot =
                mpvHlsVariantController.snapshot();
        if (snapshot.pendingMode()
                == MpvHlsVariantController.PendingMode.NONE) {
            mpvHlsManagedReload = false;
        }
        if (!completion.changed()) return;
        MpvHlsVariantController.Decision action = snapshot.lastDecision();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts", snapshot.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "successful_downgrades", snapshot.successfulDowngrades(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "rollback_count", snapshot.rollbackCount(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "failed_actions", snapshot.failedActions(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        MpvPlayerEngine mpv = host.getMpvEngine(); if (mpv != null) {
            MpvPlayer.AutoHlsRuntimeSnapshot runtime =
                    mpv.getAutoHlsRuntimeSnapshot();
            inputs.add(PlaybackTelemetry.DecisionInput.number(
                    "native_readbacks", runtime.observedCount(),
                    PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                    PlaybackAutoContext.Confidence.HIGH));
        }
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_HLS_VARIANT,
                        PlaybackTelemetry.DecisionOutcome.APPLIED,
                        hlsOptionLabel(action.oldOption()),
                        hlsOptionLabel(action.targetOption()),
                        hlsOptionLabel(completion.option()),
                        completion.reason().label(),
                        "none",
                        inputs),
                now);
    }

    public boolean recoverMpvHlsVariantError() {
        MpvPlayerEngine mpv = host.getMpvEngine();
        if (mpv == null || !host.getPlaybackAutoSession().active()) return false;
        MpvHlsVariantController.Decision decision =
                mpvHlsVariantController.requestRollbackOnError(
                        host.getPlaybackAutoSession());
        if (!decision.requestsApply()) {
            if (mpvHlsVariantController.snapshot().pendingMode()
                    == MpvHlsVariantController.PendingMode.ROLLBACK) {
                mpvHlsManagedReload = false;
            }
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        MpvHlsApplyResult apply = executeMpvHlsVariantDecision(
                mpv, decision, now);
        PlaybackTelemetry.DecisionOutcome outcome = apply.succeeded()
                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                : PlaybackTelemetry.DecisionOutcome.FAILED;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "resume_position_ms", decision.resumePositionMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "stream", decision.streamKind().label(),
                PlaybackAutoContext.ValueSource.MANIFEST,
                PlaybackAutoContext.Confidence.HIGH));
        host.getTelemetryCoordinator().publishDecision(
                host.getPlaybackAutoSession(),
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.MPV_HLS_VARIANT,
                        outcome,
                        hlsOptionLabel(decision.oldOption()),
                        hlsOptionLabel(decision.targetOption()),
                        apply.succeeded()
                                ? hlsOptionLabel(decision.targetOption())
                                : hlsOptionLabel(decision.oldOption()),
                        decision.reason().label(),
                        apply.succeeded() ? "none" : "rollback-start-failed",
                        inputs),
                now);
        if (!apply.succeeded()) mpvHlsManagedReload = false;
        return apply.succeeded();
    }
}
