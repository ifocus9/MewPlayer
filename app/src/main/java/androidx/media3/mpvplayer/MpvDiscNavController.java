package androidx.media3.mpvplayer;

import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.iso.IsoSessionManager;

import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPVLib;

/**
 * Controller responsible for optical disc (DVD / Blu-ray / ISO) interactive menus,
 * remote navigation events, pointer input dispatch, and disc rebuffering telemetry.
 */
public final class MpvDiscNavController {

    private static final String TAG = "TV-mpv";

    public interface Host {
        int executeMpvCommand(String[] command);
        Handler getMainHandler();
        boolean isInitialized();
        boolean isReleased();
        boolean isStopping();
        boolean isFileLoaded();
        boolean isPlaybackRestarted();
        boolean isPlayWhenReady();
        int getPlaybackState();
        boolean hasPlayerError();
        boolean isInitialTrackSelectionGateActive();
        boolean isSeekPending();
        @Nullable
        String getCurrentIsoUri();
        long getCachedPositionMs();
        long getCachedDurationMs();
        long getCachedCacheDurationMs();
        String getPlaybackTraceId();
        int getVideoWidth();
        int getVideoHeight();
        boolean isSurfaceAttached();
        boolean isSurfaceValid();
        void requestIsoOsdSurface();
    }

    private final Host host;
    private final MpvDiscRebufferTracker discRebufferTracker = new MpvDiscRebufferTracker();
    private final List<Runnable> discMenuStateListeners = new ArrayList<>();
    private final Runnable discRebufferRunnable = this::sampleDiscRebuffer;

    private boolean discMenuActive;
    private boolean discMenuAvailable;
    private boolean discNavigationActive;
    private long discInputQuietUntilMs;
    private long discDebugRequest;
    private long discDebugLastHoverMs;

    public MpvDiscNavController(Host host) {
        this.host = host;
    }

    public boolean isDiscMenuActive() {
        return discMenuActive;
    }

    public boolean isDiscMenuAvailable() {
        return host.isInitialized() && discMenuAvailable && !TextUtils.isEmpty(host.getCurrentIsoUri());
    }

    public boolean hasDiscNavigationTimeline() {
        return !MpvDiscMenuPolicy.hasSinglePlaybackTimeline(
                host.getCurrentIsoUri(), host.isFileLoaded(), discNavigationActive);
    }

    public boolean hasStartedDiscNavigation() {
        return discNavigationActive && host.isFileLoaded() && host.isPlaybackRestarted();
    }

    public int getDiscRebufferCount() {
        return discRebufferTracker.count();
    }

    public long getDiscRebufferTotalMs(long nowMs) {
        return discRebufferTracker.totalMs(nowMs);
    }

    public void addDiscMenuStateListener(Runnable listener) {
        if (!host.isReleased() && !discMenuStateListeners.contains(listener)) {
            discMenuStateListeners.add(listener);
        }
    }

    public void removeDiscMenuStateListener(Runnable listener) {
        discMenuStateListeners.remove(listener);
    }

    public void setDiscMenuActive(boolean active) {
        discMenuAvailable |= active;
        if (discMenuActive == active) return;
        discMenuActive = active;
        for (Runnable listener : List.copyOf(discMenuStateListeners)) {
            listener.run();
        }
    }

    public void onDiscMenuActiveChanged(boolean active) {
        setDiscMenuActive(active);
        Log.d(TAG, "disc menu active=" + discMenuActive + " available=" + discMenuAvailable);
        PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "disc menu active=%s available=%s",
                discMenuActive, discMenuAvailable);
        if (discMenuActive) {
            host.requestIsoOsdSurface();
        }
    }

    public void onDiscNavActiveChanged(boolean active) {
        discNavigationActive = active;
        discMenuAvailable |= discNavigationActive;
    }

    public boolean isDiscNavigationActive() {
        return discNavigationActive;
    }

    public void startRebufferSampling() {
        host.getMainHandler().removeCallbacks(discRebufferRunnable);
        if (discNavigationActive) {
            host.getMainHandler().postDelayed(discRebufferRunnable, 100);
        }
    }

    public void stopRebufferSampling() {
        host.getMainHandler().removeCallbacks(discRebufferRunnable);
        discRebufferTracker.interrupt(SystemClock.elapsedRealtime());
    }

    private void sampleDiscRebuffer() {
        long now = SystemClock.elapsedRealtime();
        boolean running = host.isInitialized() && !host.isReleased() && !host.isStopping()
                && discNavigationActive && host.getPlaybackState() != Player.STATE_IDLE
                && host.getPlaybackState() != Player.STATE_ENDED && !host.hasPlayerError();
        boolean eligible = running && host.isFileLoaded() && host.isPlaybackRestarted()
                && host.isPlayWhenReady() && host.getPlaybackState() == Player.STATE_READY
                && !host.isInitialTrackSelectionGateActive() && !host.isSeekPending()
                && now >= discInputQuietUntilMs;
        boolean wasActive = discRebufferTracker.active();
        long demandWait = running ? IsoSessionManager.demandWaitMs(host.getCurrentIsoUri()) : 0;
        discRebufferTracker.update(now, eligible, host.getCachedPositionMs(),
                host.getCachedCacheDurationMs(), demandWait);
        if (wasActive != discRebufferTracker.active()) {
            PlaybackTrace.log("mpv-disc-buffer", host.getPlaybackTraceId(),
                    "event=%s count=%d totalMs=%d positionMs=%d bufferedMs=%d demandWaitMs=%d",
                    discRebufferTracker.active() ? "start" : "end", discRebufferTracker.count(),
                    discRebufferTracker.totalMs(now), host.getCachedPositionMs(),
                    host.getCachedCacheDurationMs(), demandWait);
        }
        if (running) {
            host.getMainHandler().postDelayed(discRebufferRunnable, 100);
        }
    }

    public boolean sendDiscNav(String action) {
        if (!host.isInitialized() || TextUtils.isEmpty(action)) {
            if (BuildConfig.DEBUG) {
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "disc-debug rejected action=%s initialized=%s", action, host.isInitialized());
            }
            return false;
        }
        return sendDiscNavCommand(new String[]{"discnav", action});
    }

    public boolean sendDiscNavPointer(int pointerX, int pointerY, boolean activate) {
        if (!host.isInitialized() || !discMenuActive) {
            if (BuildConfig.DEBUG && activate) {
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "disc-debug pointer-rejected x=%d y=%d initialized=%s active=%s",
                        pointerX, pointerY, host.isInitialized(), discMenuActive);
            }
            return false;
        }
        return sendDiscNavCommand(MpvDiscMenuPolicy.pointerCommand(pointerX, pointerY, activate));
    }

    private boolean sendDiscNavCommand(String[] command) {
        String action = command[1];
        long debugStarted = SystemClock.elapsedRealtime();
        long debugRequest = ++discDebugRequest;
        boolean debug = BuildConfig.DEBUG && (!action.equals("mouse-move")
                || debugStarted - discDebugLastHoverMs >= 250);
        if (debug) {
            if (action.equals("mouse-move")) discDebugLastHoverMs = debugStarted;
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                    "disc-debug request=%d phase=begin t_ms=%d command=%s initialized=%s "
                            + "active=%s available=%s navigation=%s loaded=%s restarted=%s "
                            + "positionMs=%d durationMs=%d video=%dx%d surface=%s attached=%s",
                    debugRequest, debugStarted, String.join(" ", command), host.isInitialized(),
                    discMenuActive, discMenuAvailable, discNavigationActive, host.isFileLoaded(),
                    host.isPlaybackRestarted(), host.getCachedPositionMs(), host.getCachedDurationMs(),
                    host.getVideoWidth(), host.getVideoHeight(), host.isSurfaceValid(), host.isSurfaceAttached());
        }
        if (!action.equals("mouse-move") && !action.equals("up") && !action.equals("down")
                && !action.equals("left") && !action.equals("right")) {
            long now = SystemClock.elapsedRealtime();
            discRebufferTracker.interrupt(now);
            discInputQuietUntilMs = now + 1000;
        }
        try {
            int result = host.executeMpvCommand(command);
            Log.d(TAG, "disc navigation action=" + action + " result=" + result);
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "disc navigation action=%s result=%d",
                    action, result);
            if (debug) {
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "disc-debug request=%d phase=returned result=%d elapsedMs=%d active=%s",
                        debugRequest, result, SystemClock.elapsedRealtime() - debugStarted, discMenuActive);
                if (!action.equals("mouse-move")) {
                    String debugTrace = host.getPlaybackTraceId();
                    host.getMainHandler().postDelayed(() -> {
                        if (host.isReleased() || !debugTrace.equals(host.getPlaybackTraceId())) return;
                        PlaybackTrace.log("mpv", debugTrace,
                                "disc-debug request=%d phase=settled elapsedMs=%d active=%s "
                                        + "available=%s positionMs=%d loaded=%s restarted=%s",
                                debugRequest, SystemClock.elapsedRealtime() - debugStarted,
                                discMenuActive, discMenuAvailable, host.getCachedPositionMs(),
                                host.isFileLoaded(), host.isPlaybackRestarted());
                    }, 1000);
                }
            }
            return result >= MPVLib.MpvError.MPV_ERROR_SUCCESS;
        } catch (Throwable error) {
            if (debug) {
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "disc-debug request=%d phase=exception type=%s elapsedMs=%d",
                        debugRequest, error.getClass().getSimpleName(), SystemClock.elapsedRealtime() - debugStarted);
            }
            return false;
        }
    }

    public void reset() {
        discMenuAvailable = false;
        discNavigationActive = false;
        host.getMainHandler().removeCallbacks(discRebufferRunnable);
        discRebufferTracker.reset();
        discInputQuietUntilMs = 0;
        setDiscMenuActive(false);
    }

    public void release() {
        reset();
        discMenuStateListeners.clear();
    }
}
