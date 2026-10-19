package androidx.media3.mpvplayer;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.mpv.PlaybackRecoveryMonitor;
import com.github.catvod.crawler.SpiderDebug;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watchdog thread monitoring main-thread liveness during native libmpv operations
 * to capture slow native calls, main-thread stalls, and ANR diagnostics.
 */
public final class MpvMainThreadWatchdog {

    private static final String TAG = "TV-mpv";
    private static final long MAIN_THREAD_WATCHDOG_INTERVAL_MS = 500;
    private static final long MAIN_THREAD_STALL_THRESHOLD_MS = 1500;
    private static final long MAIN_THREAD_STALL_LOG_INTERVAL_MS = 5000;
    private static final long SLOW_MPV_NATIVE_CALL_THRESHOLD_MS = 16;

    public interface Host {
        Context getContext();
        Handler getMainHandler();
        boolean isReleased();
        String getPlaybackTraceId();
        MpvDiagnosticCollector getDiagnostics();
        void onSyncDiagnosticLogLevelRequested();
    }

    private final Host host;
    private final Handler mainHandler;
    private final Runnable mainThreadHeartbeatRunnable;
    private final Runnable mainThreadWatchdogRunnable;
    private final AtomicBoolean mainThreadHeartbeatPending;
    private final AtomicBoolean diagnosticLogUpdatePending;

    private HandlerThread mainThreadWatchdogThread;
    private Handler mainThreadWatchdogHandler;
    private volatile boolean mainThreadWatchdogRunning;
    private volatile PlaybackRecoveryMonitor playbackRecoveryMonitor;
    private volatile String activeMpvNativeCallKind = "";
    private volatile String activeMpvNativeCallTarget = "";
    private volatile long activeMpvNativeCallStartedAtMs;
    private long mainThreadHeartbeatPostedAtMs;
    private long lastMainThreadStallLogAtMs;
    private volatile boolean diagnosticLogLevelApplied;
    private volatile boolean diagnosticDepthApplied;
    private volatile int diagnosticCategoriesApplied = -1;

    public MpvMainThreadWatchdog(Host host) {
        this.host = host;
        this.mainHandler = host.getMainHandler();
        this.mainThreadHeartbeatPending = new AtomicBoolean();
        this.diagnosticLogUpdatePending = new AtomicBoolean();
        this.mainThreadHeartbeatRunnable = () -> {
            mainThreadHeartbeatPostedAtMs = 0;
            mainThreadHeartbeatPending.set(false);
        };
        this.mainThreadWatchdogRunnable = this::runMainThreadWatchdog;
    }

    public synchronized void start(Object playerIdentity) {
        if (mainThreadWatchdogRunning) return;
        playbackRecoveryMonitor = new PlaybackRecoveryMonitor(host.getContext());
        HandlerThread thread = new HandlerThread("mpv-main-watchdog", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        mainThreadWatchdogThread = thread;
        mainThreadWatchdogHandler = handler;
        mainThreadWatchdogRunning = true;
        mainThreadHeartbeatPending.set(false);
        mainThreadHeartbeatPostedAtMs = 0;
        lastMainThreadStallLogAtMs = 0;
        handler.post(mainThreadWatchdogRunnable);
        SpiderDebug.log("mpv-anr", "watchdog start player=%s@%h",
                playerIdentity.getClass().getSimpleName(), playerIdentity);
    }

    public synchronized void stop() {
        mainThreadWatchdogRunning = false;
        Handler handler = mainThreadWatchdogHandler;
        if (handler != null) handler.removeCallbacks(mainThreadWatchdogRunnable);
        PlaybackRecoveryMonitor recovery = playbackRecoveryMonitor;
        playbackRecoveryMonitor = null;
        if (handler != null && recovery != null) handler.post(recovery::close);
        mainHandler.removeCallbacks(mainThreadHeartbeatRunnable);
        mainThreadHeartbeatPending.set(false);
        mainThreadHeartbeatPostedAtMs = 0;
        activeMpvNativeCallStartedAtMs = 0;
        activeMpvNativeCallKind = "";
        activeMpvNativeCallTarget = "";
        HandlerThread thread = mainThreadWatchdogThread;
        mainThreadWatchdogHandler = null;
        mainThreadWatchdogThread = null;
        if (thread != null) thread.quitSafely();
    }

    public boolean isRunning() {
        return mainThreadWatchdogRunning;
    }

    public long beginNativeCall(String kind, String target) {
        if (!mainThreadWatchdogRunning || Looper.myLooper() != Looper.getMainLooper()) return -1;
        long startedAtMs = SystemClock.elapsedRealtime();
        activeMpvNativeCallKind = kind;
        activeMpvNativeCallTarget = target == null ? "" : target;
        activeMpvNativeCallStartedAtMs = startedAtMs;
        return startedAtMs;
    }

    public void endNativeCall(long startedAtMs, String kind, String target) {
        if (startedAtMs < 0) return;
        long elapsedMs = Math.max(0, SystemClock.elapsedRealtime() - startedAtMs);
        if (activeMpvNativeCallStartedAtMs == startedAtMs) {
            activeMpvNativeCallStartedAtMs = 0;
            activeMpvNativeCallKind = "";
            activeMpvNativeCallTarget = "";
        }
        if (elapsedMs >= SLOW_MPV_NATIVE_CALL_THRESHOLD_MS) {
            String operation = kind + (TextUtils.isEmpty(target) ? "" : ":" + target);
            Log.w(TAG, "SLOW_MPV_CALL operation=" + operation + " elapsed=" + elapsedMs + "ms");
            SpiderDebug.log("mpv-anr", "slow-native operation=%s elapsed=%dms", operation, elapsedMs);
        }
    }

    public void setDiagnosticTracking(boolean logLevelApplied, boolean depthApplied, int categoriesApplied) {
        this.diagnosticLogLevelApplied = logLevelApplied;
        this.diagnosticDepthApplied = depthApplied;
        this.diagnosticCategoriesApplied = categoriesApplied;
    }

    private void runMainThreadWatchdog() {
        if (!mainThreadWatchdogRunning) return;
        MpvDiagnosticCollector diagnostics = host.getDiagnostics();
        if (diagnostics != null) {
            diagnostics.tick(false);
            if ((SpiderDebug.isEnabled() != diagnosticLogLevelApplied
                    || diagnosticLogLevelApplied && (diagnosticDepthApplied != diagnostics.depthActive()
                    || diagnosticCategoriesApplied != com.github.catvod.crawler.DebugLogStore.categories()))
                    && diagnosticLogUpdatePending.compareAndSet(false, true)) {
                mainHandler.post(() -> {
                    diagnosticLogUpdatePending.set(false);
                    if (!host.isReleased()) host.onSyncDiagnosticLogLevelRequested();
                });
            }
        }
        long nowMs = SystemClock.elapsedRealtime();
        long postedAtMs = mainThreadHeartbeatPostedAtMs;
        if (mainThreadHeartbeatPending.get() && postedAtMs > 0) {
            long stalledMs = nowMs - postedAtMs;
            PlaybackRecoveryMonitor recovery = playbackRecoveryMonitor;
            if (recovery != null) recovery.onStall(postedAtMs, nowMs, host.getPlaybackTraceId());
            if (SpiderDebug.isEnabled() && stalledMs >= MAIN_THREAD_STALL_THRESHOLD_MS
                    && (lastMainThreadStallLogAtMs == 0
                    || nowMs - lastMainThreadStallLogAtMs >= MAIN_THREAD_STALL_LOG_INTERVAL_MS)) {
                lastMainThreadStallLogAtMs = nowMs;
                logMainThreadStall(stalledMs);
            }
        } else if (mainThreadHeartbeatPending.compareAndSet(false, true)) {
            PlaybackRecoveryMonitor recovery = playbackRecoveryMonitor;
            if (recovery != null) recovery.onHeartbeat();
            mainThreadHeartbeatPostedAtMs = nowMs;
            if (!mainHandler.post(mainThreadHeartbeatRunnable)) {
                mainThreadHeartbeatPending.set(false);
                mainThreadHeartbeatPostedAtMs = 0;
            }
        }
        Handler handler = mainThreadWatchdogHandler;
        if (mainThreadWatchdogRunning && handler != null) {
            handler.postDelayed(mainThreadWatchdogRunnable, MAIN_THREAD_WATCHDOG_INTERVAL_MS);
        }
    }

    private void logMainThreadStall(long stalledMs) {
        long nativeStartedAtMs = activeMpvNativeCallStartedAtMs;
        String nativeCall = TextUtils.isEmpty(activeMpvNativeCallKind)
                ? "none"
                : activeMpvNativeCallKind + (TextUtils.isEmpty(activeMpvNativeCallTarget)
                ? "" : ":" + activeMpvNativeCallTarget);
        long nativeElapsedMs = nativeStartedAtMs > 0
                ? Math.max(0, SystemClock.elapsedRealtime() - nativeStartedAtMs) : 0;
        String stack = formatMainThreadStack(Looper.getMainLooper().getThread().getStackTrace());
        String message = "main stalled=" + stalledMs + "ms native=" + nativeCall
                + " nativeElapsed=" + nativeElapsedMs + "ms stack=" + stack;
        Log.w(TAG, "ANR_DIAGNOSTIC " + message);
        SpiderDebug.log("mpv-anr", "%s", message);
    }

    public static String formatMainThreadStack(@Nullable StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) return "empty";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(stack.length, 32);
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(" <- ");
            builder.append(stack[i].toString());
        }
        return builder.toString();
    }
}
