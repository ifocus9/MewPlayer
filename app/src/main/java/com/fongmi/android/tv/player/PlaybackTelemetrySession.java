package com.fongmi.android.tv.player;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.mpvplayer.MpvPlayer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.setting.PlaybackExperimentSetting;
import com.fongmi.android.tv.setting.PlaybackLightweightAssessmentSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackProfileAbSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;

public class PlaybackTelemetrySession {

    public static final long PLAYBACK_TELEMETRY_INTERVAL_MS = 5000L;
    private static final long MPV_FRAME_TIMING_LOG_INTERVAL_MS = 10000L;

    public interface Host {
        @Nullable
        Player getPlayer();
        @Nullable
        PlayerEngine getEngine();
        int getPlayerType();
        boolean hasSpec();
        boolean isExo();
        boolean isMpv();
        float getUserPlaybackSpeed();
        PlaybackAutoContext.SessionToken getPlaybackAutoSession();
        PlaybackAutoContextStore getAutoContextStore();
        PlaybackTrace getPlaybackTrace();
        @Nullable
        Format getVideoFormat();
        int getRebufferCount();
        long getRebufferTotalMs(long now);
        void onMpvEvaluateRuntime(PlaybackTelemetry.RuntimeObservation observation, boolean evaluateMpvHlsVariant, long now);
        void onExoRtspEvaluate(PlaybackTelemetry.RuntimeObservation observation, long now);
    }

    private final Host host;
    private final Runnable runnable;
    private final PlaybackTelemetryCoordinator telemetryCoordinator;
    private final PlaybackProfileAbCoordinator profileAbCoordinator;
    private final PlaybackProfileAbCoordinator lightweightAssessmentCoordinator;
    private final PlaybackExperimentCoordinator experimentCoordinator;

    private long lastMpvFrameTimingLogMs;

    public PlaybackTelemetrySession(@NonNull Host host) {
        this.host = host;
        this.runnable = this::onTick;
        this.telemetryCoordinator = PlaybackTelemetryCoordinator.process();
        this.profileAbCoordinator = PlaybackProfileAbCoordinator.process();
        this.lightweightAssessmentCoordinator = PlaybackLightweightAssessmentSetting.coordinator();
        this.experimentCoordinator = PlaybackExperimentCoordinator.process();
    }

    public PlaybackTelemetryCoordinator getCoordinator() {
        return telemetryCoordinator;
    }

    public void release() {
        App.removeCallbacks(runnable);
    }

    public void beginSession(long nowElapsedMs) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        telemetryCoordinator.beginSession(session, nowElapsedMs);
        beginProfileAbSession(nowElapsedMs);
    }

    public void endSession(String reason) {
        App.removeCallbacks(runnable);
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        if (!session.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation = collect(null, now);
        observeProfileAb(observation, now);
        finishProfileAbSession(reason, now);
        telemetryCoordinator.endSession(session, reason, observation, now);
    }

    public void schedule() {
        App.removeCallbacks(runnable);
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        Player player = host.getPlayer();
        if (!session.active() || player == null || player.getPlaybackState() == Player.STATE_ENDED) return;
        App.post(runnable, PLAYBACK_TELEMETRY_INTERVAL_MS);
    }

    public void publish() {
        publish(null, true);
    }

    public void publish(@Nullable PlaybackAutoContext.PlaybackPhase phaseOverride) {
        publish(phaseOverride, true);
    }

    public void publish(@Nullable PlaybackAutoContext.PlaybackPhase phaseOverride, boolean evaluateMpvHlsVariant) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        if (!session.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation = collect(phaseOverride, now);
        telemetryCoordinator.publishRuntime(session, observation, now);
        observeProfileAb(observation, now);
        host.onExoRtspEvaluate(observation, now);
        if (host.isMpv()) {
            host.onMpvEvaluateRuntime(observation, evaluateMpvHlsVariant, now);
        }
    }

    public void invalidate(PlaybackProfileAbCoordinator.InvalidationReason reason) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        profileAbCoordinator.invalidate(session, reason);
        lightweightAssessmentCoordinator.invalidate(session, reason);
    }

    public void finishProfileAbSession(String reason, long nowElapsedMs) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        profileAbCoordinator.endSession(
                session,
                new PlaybackProfileAbCoordinator.EndConfig(
                        PlaybackProfileAbSetting.isEnrolled()
                                && profileAbGateOpen(),
                        currentProfileAbArm(),
                        experimentCoordinator.generation(),
                        reason),
                nowElapsedMs,
                System.currentTimeMillis());
        lightweightAssessmentCoordinator.endSession(
                session,
                new PlaybackProfileAbCoordinator.EndConfig(
                        PlaybackLightweightAssessmentSetting.isEnrolled()
                                && profileAbGateOpen(),
                        currentLightweightAssessmentArm(),
                        experimentCoordinator.generation(),
                        reason),
                nowElapsedMs,
                System.currentTimeMillis());
    }

    private void onTick() {
        if (!host.getPlaybackAutoSession().active()) return;
        publish();
        schedule();
    }

    private void beginProfileAbSession(long nowElapsedMs) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        PlaybackProfileAbPolicy.EnrollmentResolution enrollment =
                PlaybackProfileAbSetting.getEnrollmentResolution();
        PlaybackProfileAbPolicy.Arm arm = currentProfileAbArm();
        profileAbCoordinator.beginSession(
                session,
                new PlaybackProfileAbCoordinator.StartConfig(
                        enrollment.active()
                                && profileAbGateOpen()
                                && arm != null,
                        arm,
                        enrollment.enrollment().deviceDigest(),
                        experimentCoordinator.generation(),
                        Math.abs(host.getUserPlaybackSpeed() - 1f) < 0.001f),
                nowElapsedMs);
        PlaybackProfileAbPolicy.EnrollmentResolution lightweightEnrollment =
                PlaybackLightweightAssessmentSetting.getEnrollmentResolution();
        PlaybackProfileAbPolicy.Arm lightweightArm = currentLightweightAssessmentArm();
        lightweightAssessmentCoordinator.beginSession(
                session,
                new PlaybackProfileAbCoordinator.StartConfig(
                        lightweightEnrollment.active()
                                && profileAbGateOpen()
                                && lightweightArm != null,
                        lightweightArm,
                        lightweightEnrollment.enrollment().deviceDigest(),
                        experimentCoordinator.generation(),
                        Math.abs(host.getUserPlaybackSpeed() - 1f) < 0.001f),
                nowElapsedMs);
    }

    private void observeProfileAb(PlaybackTelemetry.RuntimeObservation observation, long nowElapsedMs) {
        PlaybackAutoContext.SessionToken session = host.getPlaybackAutoSession();
        if (!session.active()) return;
        boolean playbackIntended = false;
        Player player = host.getPlayer();
        if (player != null) {
            try {
                playbackIntended = player.getPlayWhenReady()
                        && (player.isPlaying()
                        || player.getPlaybackState() == Player.STATE_BUFFERING);
            } catch (Throwable ignored) {
            }
        }
        boolean frameSchedulingExperimentActive = false;
        if (host.isExo()) {
            try {
                var frameScheduling = PlaybackAnalyticsListener.getFrameSchedulingExperimentSnapshot();
                PlaybackTrace trace = host.getPlaybackTrace();
                frameSchedulingExperimentActive = frameScheduling.active()
                        && trace != null
                        && trace.current().equals(frameScheduling.traceId());
            } catch (Throwable ignored) {
            }
        }
        profileAbCoordinator.observe(
                session,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        PlaybackProfileAbSetting.isEnrolled()
                                && profileAbGateOpen(),
                        currentProfileAbArm(),
                        experimentCoordinator.generation(),
                        host.getAutoContextStore().snapshot(),
                        observation,
                        playbackIntended,
                        frameSchedulingExperimentActive,
                        false),
                nowElapsedMs);
        lightweightAssessmentCoordinator.observe(
                session,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        PlaybackLightweightAssessmentSetting.isEnrolled()
                                && profileAbGateOpen(),
                        currentLightweightAssessmentArm(),
                        experimentCoordinator.generation(),
                        host.getAutoContextStore().snapshot(),
                        observation,
                        playbackIntended,
                        frameSchedulingExperimentActive,
                        false),
                nowElapsedMs);
    }

    private PlaybackProfileAbPolicy.Arm currentProfileAbArm() {
        return PlaybackProfileAbPolicy.armForProfile(
                PlaybackPerformanceSetting.getProfile(host.getPlayerType()));
    }

    private PlaybackProfileAbPolicy.Arm currentLightweightAssessmentArm() {
        return PlaybackLightweightAssessmentPolicy.armForProfile(
                PlaybackPerformanceSetting.getProfile(host.getPlayerType()));
    }

    private boolean profileAbGateOpen() {
        return PlaybackProfileAbPolicy.gateAllows(
                PlaybackExperimentSetting.getState(),
                playbackAutoKernel(host.getPlayerType()));
    }

    public static PlaybackAutoContext.Kernel playbackAutoKernel(int playerType) {
        return switch (PlayerSetting.sanitizePlayer(playerType)) {
            case PlayerSetting.IJK -> PlaybackAutoContext.Kernel.IJK;
            case PlayerSetting.MPV -> PlaybackAutoContext.Kernel.MPV;
            default -> PlaybackAutoContext.Kernel.EXO;
        };
    }

    public PlaybackTelemetry.RuntimeObservation collect(@Nullable PlaybackAutoContext.PlaybackPhase phaseOverride, long now) {
        PlaybackAutoContext.PlaybackPhase phaseValue = phaseOverride == null ? playbackPhaseSnapshot() : phaseOverride;
        PlaybackTelemetry.Metric<PlaybackAutoContext.PlaybackPhase> phase = phaseValue == PlaybackAutoContext.PlaybackPhase.UNKNOWN
                ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(phaseValue, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        PlaybackTelemetry.Metric<Boolean> loading = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> position = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> duration = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> buffered = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> bandwidth = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> mediaBitrate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Float> renderedFrameRate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> droppedFrames = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> liveLag = PlaybackTelemetry.Metric.unknown();
        Player player = host.getPlayer();
        if (player != null) {
            try {
                loading = PlaybackTelemetry.Metric.of(player.isLoading(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getCurrentPosition();
                if (value >= 0) position = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getDuration();
                if (value >= 0 && value != C.TIME_UNSET) duration = PlaybackTelemetry.Metric.of(value,
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getTotalBufferedDuration();
                if (value >= 0) buffered = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
        }
        PlaybackTrace trace = host.getPlaybackTrace();
        if (host.isExo() && trace != null && trace.current().equals(PlaybackAnalyticsListener.getPlaybackTraceId())) {
            PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
            if (analytics.bandwidthEstimate() > 0) {
                bandwidth = PlaybackTelemetry.Metric.of(analytics.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        PlaybackAutoContext.Confidence.MEDIUM);
            }
            PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media =
                    PlaybackAnalyticsListener.getDisplayMediaBitrateEstimate(host.getVideoFormat());
            if (media.bitrateBitsPerSecond() > 0) {
                PlaybackAutoContext.ValueSource source = "format".equals(media.source())
                        ? PlaybackAutoContext.ValueSource.PLAYER_CALLBACK : PlaybackAutoContext.ValueSource.ESTIMATOR;
                mediaBitrate = PlaybackTelemetry.Metric.of(media.bitrateBitsPerSecond(), source,
                        telemetryConfidence(media.confidence()));
            }
            PlaybackAnalyticsListener.DisplayFrameRateEstimate frameRate = PlaybackAnalyticsListener.getDisplayFrameRateEstimate();
            if (frameRate.frameRate() > 0 && frameRate.sampleCount() > 0) {
                renderedFrameRate = PlaybackTelemetry.Metric.of(frameRate.frameRate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        frameRate.sampleCount() >= 12 ? PlaybackAutoContext.Confidence.HIGH : PlaybackAutoContext.Confidence.MEDIUM);
            }
            if (analytics.everReady() || analytics.videoFormat() != null) {
                droppedFrames = PlaybackTelemetry.Metric.of(Math.max(0, analytics.droppedFrames()),
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            }
            try {
                if (player != null) {
                    long value = player.getCurrentLiveOffset();
                    if (player.isCurrentMediaItemLive() && value >= 0 && value != C.TIME_UNSET) {
                        liveLag = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                                PlaybackAutoContext.Confidence.HIGH);
                    }
                }
            } catch (Throwable ignored) {
            }
        } else {
            PlayerEngine engine = host.getEngine();
            if (engine != null) {
                try {
                    PlayerEngine.RuntimeMetrics metrics = engine.getRuntimeMetrics();
                    if (metrics.bandwidthBitsPerSecond() != null) bandwidth = PlaybackTelemetry.Metric.of(
                            metrics.bandwidthBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                            PlaybackAutoContext.Confidence.MEDIUM);
                    if (metrics.mediaBitrateBitsPerSecond() != null) mediaBitrate = PlaybackTelemetry.Metric.of(
                            metrics.mediaBitrateBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                            PlaybackAutoContext.Confidence.MEDIUM);
                    if (metrics.renderedFrameRate() != null) renderedFrameRate = PlaybackTelemetry.Metric.of(
                            metrics.renderedFrameRate(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                            PlaybackAutoContext.Confidence.MEDIUM);
                    if (metrics.droppedFrames() != null) droppedFrames = PlaybackTelemetry.Metric.of(
                            metrics.droppedFrames(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                            PlaybackAutoContext.Confidence.HIGH);
                } catch (Throwable error) {
                    if (trace != null) {
                        PlaybackTrace.log("playback-telemetry", trace.current(),
                                "native metrics unavailable type=%s action=keep-unknown", error.getClass().getSimpleName());
                    }
                }
            }
        }
        long firstFrameMs = trace != null ? trace.stageElapsedMs(PlaybackTrace.Stage.FIRST_FRAME) : -1;
        PlaybackTelemetry.Metric<Long> firstFrame = firstFrameMs < 0 ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(firstFrameMs, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        logMpvFrameTiming(now);
        return new PlaybackTelemetry.RuntimeObservation(
                phase,
                loading,
                position,
                duration,
                buffered,
                bandwidth,
                mediaBitrate,
                renderedFrameRate,
                droppedFrames,
                PlaybackTelemetry.Metric.of(host.getRebufferCount(),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                PlaybackTelemetry.Metric.of(host.getRebufferTotalMs(now),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                firstFrame,
                liveLag);
    }

    private void logMpvFrameTiming(long now) {
        if (!SpiderDebug.isEnabled()) return;
        PlayerEngine engine = host.getEngine();
        if (!(engine instanceof MpvPlayerEngine mpv)) return;
        if (now - lastMpvFrameTimingLogMs < MPV_FRAME_TIMING_LOG_INTERVAL_MS) return;
        lastMpvFrameTimingLogMs = now;
        MpvPlayer.FrameTimingSnapshot timing = mpv.getFrameTimingSnapshot();
        PlaybackTrace trace = host.getPlaybackTrace();
        if (trace != null) {
            PlaybackTrace.log("mpv-frame-timing", trace.current(),
                    "dec=%d out=%d mistimed=%d delayed=%d avSyncMs=%d contentFps=%.3f displayFps=%.3f direct=%s observed=%s",
                    timing.decoderDroppedFrames(),
                    timing.outputDroppedFrames(),
                    timing.mistimedFrames(),
                    timing.delayedFrames(),
                    Math.round(timing.avSyncSeconds() * 1000.0),
                    timing.contentFrameRate(),
                    timing.displayFrameRate(),
                    mpv.isSurfaceDirect(),
                    timing.observed());
        }
    }

    private PlaybackAutoContext.PlaybackPhase playbackPhaseSnapshot() {
        Player player = host.getPlayer();
        if (player == null) return PlaybackAutoContext.PlaybackPhase.IDLE;
        return switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING -> PlaybackAutoContext.PlaybackPhase.BUFFERING;
            case Player.STATE_READY -> PlaybackAutoContext.PlaybackPhase.READY;
            case Player.STATE_ENDED -> PlaybackAutoContext.PlaybackPhase.ENDED;
            case Player.STATE_IDLE -> host.hasSpec() ? PlaybackAutoContext.PlaybackPhase.PREPARING : PlaybackAutoContext.PlaybackPhase.IDLE;
            default -> PlaybackAutoContext.PlaybackPhase.UNKNOWN;
        };
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
}
