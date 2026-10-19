package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;

/**
 * Manages MediaCodec resource reclamation (ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
 * and deferred foreground recovery for ExoPlayer.
 */
public final class ExoDecoderResourceSession {

    private static final long RECOVERY_DELAY_MS = 500;

    public record Recovery(
            PlaySpec target,
            long positionMs,
            float speed,
            boolean repeat,
            boolean playWhenReady,
            long textOffsetMs,
            long audioOffsetMs) {
    }

    public interface Host {
        boolean isExoEngine();
        ExoPlayerEngine getExoEngine();
        Player getPlayer();
        PlaySpec getSpec();
        long getPosition();
        float getSpeed();
        boolean isRepeatOne();
        long getTextOffsetMs();
        long getAudioOffsetMs();
        PlaybackTrace getPlaybackTrace();
        int getPrepareSeq();
        int nextPrepareSeq();
        void resetNetworkGuard(String reason);
        void rebuildPlayer(boolean resetVideoSurface);
        void setPlayWhenReady(boolean playWhenReady);
        void setInitTrack(boolean initTrack);
        void applySubtitleStyle();
        void setSpeed(float speed);
        void setRepeatOne(boolean repeat);
        void setTextOffsetMs(long offsetMs);
        void setAudioOffsetMs(long offsetMs);
        void resetPlaybackTimeout();
        void schedulePlaybackTimeout(long timeoutMs);
        void onPrepareCallback();
        void disarmHardDecodeSwitchRetry();
    }

    private final Host host;
    private final ExoDecoderResourceRecoveryLimiter limiter;

    private Recovery pendingRecovery;
    private boolean recoveryInProgress;
    private boolean playbackForeground;

    public ExoDecoderResourceSession(Host host) {
        this.host = host;
        this.limiter = new ExoDecoderResourceRecoveryLimiter();
    }

    public void setPlaybackForeground(boolean foreground) {
        playbackForeground = foreground;
        if (!foreground || pendingRecovery == null) return;
        Recovery recovery = pendingRecovery;
        pendingRecovery = null;
        scheduleRecovery(recovery, "foreground");
    }

    public boolean isPlaybackForeground() {
        return playbackForeground;
    }

    public boolean hasPendingRecovery() {
        return pendingRecovery != null;
    }

    public void clear(boolean resetBudget) {
        pendingRecovery = null;
        recoveryInProgress = false;
        if (resetBudget) limiter.reset();
    }

    public boolean handleExoDecoderResourcesReclaimed(PlaybackException error) {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            return false;
        }
        if (pendingRecovery != null || recoveryInProgress) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    host.getPlaybackTrace().current(),
                    "action=suppress-duplicate pending=%s inProgress=%s",
                    pendingRecovery != null,
                    recoveryInProgress);
            return true;
        }
        Recovery recovery = captureRecovery();
        ExoDecoderResourceRecoveryLimiter.Action action = limiter.request(
                error.errorCode,
                host.isExoEngine(),
                host.isExoEngine() && host.getPlayer() != null,
                recovery != null,
                playbackForeground);
        if (action == ExoDecoderResourceRecoveryLimiter.Action.DEFER_UNTIL_FOREGROUND) {
            host.disarmHardDecodeSwitchRetry();
            pendingRecovery = recovery;
            host.resetNetworkGuard("exo-resource-reclaimed-deferred");
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    host.getPlaybackTrace().current(),
                    "action=defer position=%d play=%s",
                    recovery != null ? recovery.positionMs() : -1,
                    recovery != null && recovery.playWhenReady());
            return true;
        }
        if (action != ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    host.getPlaybackTrace().current(),
                    "action=pass-through reason=%s",
                    action);
            return false;
        }
        host.disarmHardDecodeSwitchRetry();
        scheduleRecovery(recovery, "foreground-error");
        return true;
    }

    @Nullable
    public Recovery captureRecovery() {
        if (!host.isExoEngine() || host.getPlayer() == null || host.getSpec() == null || host.getSpec().getUrl() == null) {
            return null;
        }
        return new Recovery(
                host.getSpec(),
                Math.max(0, host.getPosition()),
                host.getSpeed(),
                host.isRepeatOne(),
                host.getPlayer().getPlayWhenReady(),
                host.getTextOffsetMs(),
                host.getAudioOffsetMs());
    }

    public void scheduleRecovery(Recovery recovery, String reason) {
        if (recovery == null) return;
        if (!playbackForeground) {
            pendingRecovery = recovery;
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    host.getPlaybackTrace().current(),
                    "action=defer-before-rebuild reason=%s",
                    reason);
            return;
        }
        ExoPlayerEngine exo = host.getExoEngine();
        if (exo == null || host.getPlayer() == null || host.getSpec() != recovery.target()) {
            return;
        }
        recoveryInProgress = true;
        int seq = host.nextPrepareSeq();
        host.resetPlaybackTimeout();
        host.resetNetworkGuard("exo-resource-reclaimed");
        host.rebuildPlayer(true);
        host.setPlayWhenReady(recovery.playWhenReady());
        host.setInitTrack(false);
        PlaybackTrace.log(
                "exo-decoder-resource",
                host.getPlaybackTrace().current(),
                "action=rebuild delay=%d position=%d play=%s reason=%s",
                RECOVERY_DELAY_MS,
                recovery.positionMs(),
                recovery.playWhenReady(),
                reason);
        App.post(() -> {
            if (seq != host.getPrepareSeq()
                    || host.getSpec() != recovery.target()
                    || host.getExoEngine() != exo
                    || host.getPlayer() == null) {
                recoveryInProgress = false;
                return;
            }
            if (!playbackForeground) {
                pendingRecovery = recovery;
                recoveryInProgress = false;
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        host.getPlaybackTrace().current(),
                        "action=defer-before-prepare");
                return;
            }
            try {
                host.applySubtitleStyle();
                exo.start(
                        recovery.target().checkUa(),
                        recovery.positionMs(),
                        recovery.playWhenReady());
                host.setSpeed(recovery.speed());
                host.setRepeatOne(recovery.repeat());
                host.setTextOffsetMs(recovery.textOffsetMs());
                host.setAudioOffsetMs(recovery.audioOffsetMs());
                host.schedulePlaybackTimeout(Constant.TIMEOUT_PLAY);
                host.onPrepareCallback();
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        host.getPlaybackTrace().current(),
                        "action=prepare position=%d play=%s",
                        recovery.positionMs(),
                        recovery.playWhenReady());
            } finally {
                recoveryInProgress = false;
            }
        }, RECOVERY_DELAY_MS);
    }
}
