package com.fongmi.android.tv.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;

import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.diagnostics.DiagnosticEvent;

/**
 * Manages native audio focus and headphone/peripheral unplug events (AUDIO_BECOMING_NOISY)
 * for non-Exo player backends (e.g. MPV).
 */
public final class PlaybackAudioSession {

    public interface Host {
        boolean isNativePlayer();
        Player getPlayer();
        int getPlayerType();
        String getPlaybackTraceId();
    }

    private final Host host;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private final BroadcastReceiver noisyReceiver;

    private boolean audioFocusHeld;
    private boolean noisyReceiverRegistered;
    private boolean resumeOnAudioFocusGain;
    private Object audioFocusRequest;

    public PlaybackAudioSession(Host host) {
        this.host = host;
        this.audioFocusChangeListener = this::onNativeAudioFocusChanged;
        this.noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                onNativeAudioBecomingNoisy();
            }
        };
    }

    public void start(boolean shouldPlay) {
        if (!shouldPlay || !host.isNativePlayer()) return;
        requestNativeAudioFocus();
        registerNoisyReceiver();
    }

    public void stop() {
        unregisterNoisyReceiver();
        abandonNativeAudioFocus();
        resumeOnAudioFocusGain = false;
    }

    public void abandon() {
        abandonNativeAudioFocus();
    }

    public boolean isAudioFocusHeld() {
        return audioFocusHeld;
    }

    private void requestNativeAudioFocus() {
        AudioManager manager = audioManager();
        if (manager == null || audioFocusHeld) return;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusApi26.request(manager, audioFocusChangeListener, raw -> diagnosticNativeFocus("request", raw, 0));
            result = audioFocusRequest == null ? AudioManager.AUDIOFOCUS_REQUEST_FAILED : AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            result = manager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            diagnosticNativeFocus("request", result, 0);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!audioFocusHeld && SpiderDebug.isEnabled()) {
            SpiderDebug.log("player", "native audio focus request denied type=%d", host.getPlayerType());
        }
    }

    private void abandonNativeAudioFocus() {
        if (!audioFocusHeld) return;
        AudioManager manager = audioManager();
        if (manager != null) {
            int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? AudioFocusApi26.abandon(manager, audioFocusRequest)
                    : manager.abandonAudioFocus(audioFocusChangeListener);
            diagnosticNativeFocus("abandon", result, 0);
        }
        audioFocusRequest = null;
        audioFocusHeld = false;
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        try {
            App.get().registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "register noisy receiver failed error=%s", e.getMessage());
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            App.get().unregisterReceiver(noisyReceiver);
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "unregister noisy receiver failed error=%s", e.getMessage());
        }
        noisyReceiverRegistered = false;
    }

    private void onNativeAudioBecomingNoisy() {
        if (!host.isNativePlayer()) return;
        Player player = host.getPlayer();
        if (player == null) return;
        boolean wasPlaying = player.isPlaying();
        player.pause();
        stop();
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("player", "native audio noisy pause type=%d wasPlaying=%s", host.getPlayerType(), wasPlaying);
        }
    }

    private void onNativeAudioFocusChanged(int focusChange) {
        if (!host.isNativePlayer()) return;
        Player player = host.getPlayer();
        if (player == null) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false;
                    start(true);
                    player.play();
                }
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnAudioFocusGain = player.isPlaying();
                player.pause();
            }
            case AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnAudioFocusGain = false;
                player.pause();
                stop();
            }
        }
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("player", "native audio focus changed type=%d change=%d resume=%s",
                    host.getPlayerType(), focusChange, resumeOnAudioFocusGain);
        }
        diagnosticNativeFocus("callback", 0, focusChange);
    }

    private void diagnosticNativeFocus(String action, int result, int change) {
        if (!DebugLogStore.isEnabled()) return;
        Player player = host.getPlayer();
        DebugLogStore.event(new DiagnosticEvent("audio.focus", host.getPlaybackTraceId(),
                "native-focus-controller", 0, 0)
                .observed("focusOwner", "App/native-player")
                .observed("focusAction", action)
                .observed("focusGain", AudioManager.AUDIOFOCUS_GAIN)
                .observed("focusResult", result)
                .observed("focusChange", change)
                .observed("usage", android.media.AudioAttributes.USAGE_MEDIA)
                .observed("contentType", android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .observed("playWhenReady", player != null && player.getPlayWhenReady())
                .observed("active", audioFocusHeld)
                .observed("metricScope", "controller focus session; callback result is a focus change, not a new request"));
    }

    private AudioManager audioManager() {
        return (AudioManager) App.get().getSystemService(Context.AUDIO_SERVICE);
    }

    private static final class AudioFocusApi26 {

        private static Object request(AudioManager manager, AudioManager.OnAudioFocusChangeListener listener, java.util.function.IntConsumer observe) {
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .build();
            int result = manager.requestAudioFocus(request);
            observe.accept(result);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? request : null;
        }

        private static int abandon(AudioManager manager, Object request) {
            return request instanceof android.media.AudioFocusRequest
                    ? manager.abandonAudioFocusRequest((android.media.AudioFocusRequest) request)
                    : AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
    }
}
