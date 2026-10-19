package androidx.media3.mpvplayer;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

import is.xyz.mpv.MPVLib;

/**
 * Manages native MPV context lifecycle, locking, native library bridging,
 * and high-frequency property access/conversion for MpvPlayer.
 */
public final class MpvNativeBridge {

    private static final String TAG = "TV-mpv";
    private static final double SECONDS_TO_MS = 1000.0;
    public static final Object NATIVE_CONTEXT_LOCK = new Object();
    public static final AtomicLong NATIVE_REQUEST_IDS = new AtomicLong(1);

    public static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) return 0;
        return value > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : value * multiplier;
    }

    @Nullable
    private static Object nativeContextOwner;

    public interface Host {
        boolean isInitialized();
        MpvMainThreadWatchdog getMainThreadWatchdog();
        MpvDiagnosticCollector getDiagnostics();
        MpvPropertySnapshot getPropertySnapshot();
        void onBaseMsgLevelOptionSet(String value);
    }

    private final Host host;

    public MpvNativeBridge(Host host) {
        this.host = host;
    }

    public static Object getNativeContextOwner() {
        return nativeContextOwner;
    }

    public static void setNativeContextOwner(@Nullable Object owner) {
        nativeContextOwner = owner;
    }

    public static boolean isNativeContextOwner(Object candidate) {
        return nativeContextOwner == candidate;
    }

    public static void clearNativeContextOwnerIf(Object owner) {
        if (nativeContextOwner == owner) {
            nativeContextOwner = null;
        }
    }

    public static long nextRequestId() {
        long requestId = NATIVE_REQUEST_IDS.getAndIncrement();
        if (requestId <= 0) {
            NATIVE_REQUEST_IDS.compareAndSet(requestId + 1, 2);
            requestId = 1;
        }
        return requestId;
    }

    public long beginMpvNativeCall(String kind, String target) {
        return host.getMainThreadWatchdog().beginNativeCall(kind, target);
    }

    public void endMpvNativeCall(long startedAtMs, String kind, String target) {
        host.getMainThreadWatchdog().endNativeCall(startedAtMs, kind, target);
    }

    public boolean mpvTryCreate(Context appContext) {
        long startedAtMs = beginMpvNativeCall("lifecycle", "create");
        try {
            return MPVLib.tryCreate(appContext);
        } finally {
            endMpvNativeCall(startedAtMs, "lifecycle", "create");
        }
    }

    public void mpvInit() {
        long startedAtMs = beginMpvNativeCall("lifecycle", "init");
        try {
            MPVLib.init();
        } finally {
            endMpvNativeCall(startedAtMs, "lifecycle", "init");
        }
    }

    public void mpvDestroyCreatedContext() {
        long startedAtMs = beginMpvNativeCall("lifecycle", "destroy");
        try {
            MPVLib.destroyCreatedContext();
        } finally {
            endMpvNativeCall(startedAtMs, "lifecycle", "destroy");
        }
    }

    public int mpvSetOptionString(String property, String value) {
        long startedAtMs = beginMpvNativeCall("set-option", property);
        try {
            int result = MPVLib.setOptionString(property, value);
            if ("msg-level".equals(property) && result >= 0) {
                host.onBaseMsgLevelOptionSet(value);
            }
            host.getDiagnostics().option(property, value, result, false);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "set-option", property);
        }
    }

    @Nullable
    public Integer mpvGetPropertyInt(String property) {
        MpvPropertySnapshot snapshot = host.getPropertySnapshot();
        if (snapshot.contains(property)) return snapshot.integer(property);
        long startedAtMs = beginMpvNativeCall("get-int", property);
        try {
            return MPVLib.getPropertyInt(property);
        } finally {
            endMpvNativeCall(startedAtMs, "get-int", property);
        }
    }

    @Nullable
    public Double mpvGetPropertyDouble(String property) {
        MpvPropertySnapshot snapshot = host.getPropertySnapshot();
        if (snapshot.contains(property)) return snapshot.decimal(property);
        long startedAtMs = beginMpvNativeCall("get-double", property);
        try {
            return MPVLib.getPropertyDouble(property);
        } finally {
            endMpvNativeCall(startedAtMs, "get-double", property);
        }
    }

    @Nullable
    public Boolean mpvGetPropertyBoolean(String property) {
        MpvPropertySnapshot snapshot = host.getPropertySnapshot();
        if (snapshot.contains(property)) return snapshot.flag(property);
        long startedAtMs = beginMpvNativeCall("get-boolean", property);
        try {
            return MPVLib.getPropertyBoolean(property);
        } finally {
            endMpvNativeCall(startedAtMs, "get-boolean", property);
        }
    }

    @Nullable
    public String mpvGetPropertyString(String property) {
        MpvPropertySnapshot snapshot = host.getPropertySnapshot();
        if (snapshot.contains(property)) return snapshot.string(property);
        long startedAtMs = beginMpvNativeCall("get-string", property);
        try {
            return MPVLib.getPropertyString(property);
        } finally {
            endMpvNativeCall(startedAtMs, "get-string", property);
        }
    }

    public int mpvSetPropertyInt(String property, int value) {
        long startedAtMs = beginMpvNativeCall("set-int", property);
        try {
            int result = MPVLib.setPropertyInt(property, value);
            if (result >= 0) host.getPropertySnapshot().acceptedWrite(property, value);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "set-int", property);
        }
    }

    public int mpvSetPropertyDouble(String property, double value) {
        long startedAtMs = beginMpvNativeCall("set-double", property);
        try {
            int result = MPVLib.setPropertyDouble(property, value);
            if (result >= 0) host.getPropertySnapshot().acceptedWrite(property, value);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "set-double", property);
        }
    }

    public int mpvSetPropertyBoolean(String property, boolean value) {
        long startedAtMs = beginMpvNativeCall("set-boolean", property);
        try {
            int result = MPVLib.setPropertyBoolean(property, value);
            if (result >= 0) host.getPropertySnapshot().acceptedWrite(property, value);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "set-boolean", property);
        }
    }

    public int mpvSetPropertyString(String property, String value) {
        long startedAtMs = beginMpvNativeCall("set-string", property);
        try {
            int result = MPVLib.setPropertyString(property, value);
            host.getDiagnostics().option(property, value, result, true);
            if (result >= 0) host.getPropertySnapshot().acceptedWrite(property, value);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "set-string", property);
        }
    }

    public int mpvCommand(String[] command) {
        String target = command == null || command.length == 0 ? "unknown" : command[0];
        long startedAtMs = beginMpvNativeCall("command", target);
        long diagnosticLoadId = "loadfile".equals(target) ? host.getDiagnostics().loadRequested() : 0;
        int result = -1;
        try {
            result = MPVLib.command(command);
            host.getDiagnostics().command(target, 0, result, "returned", startedAtMs < 0 ? -1 : SystemClock.elapsedRealtime() - startedAtMs);
            return result;
        } finally {
            if (diagnosticLoadId != 0) host.getDiagnostics().loadReturned(diagnosticLoadId, result);
            endMpvNativeCall(startedAtMs, "command", target);
        }
    }

    public int mpvEnqueueCommand(long requestId, String[] command) {
        String target = command == null || command.length == 0 ? "unknown" : command[0];
        long startedAtMs = beginMpvNativeCall("enqueue-command", target);
        try {
            int result = MPVLib.enqueueCommand(requestId, command);
            host.getDiagnostics().command(target, requestId, result, "submitted", startedAtMs < 0 ? -1 : SystemClock.elapsedRealtime() - startedAtMs);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "enqueue-command", target);
        }
    }

    public int mpvObserveProperty(String property, int format) {
        long startedAtMs = beginMpvNativeCall("observe", property);
        try {
            int result = MPVLib.observeProperty(property, format);
            host.getPropertySnapshot().diagnosticRegister(property, result);
            host.getDiagnostics().registration(property, format, result);
            return result;
        } finally {
            endMpvNativeCall(startedAtMs, "observe", property);
        }
    }

    public void mpvAttachSurface(Surface target) {
        long startedAtMs = beginMpvNativeCall("surface", "attach-video");
        try {
            MPVLib.attachSurface(target);
        } finally {
            endMpvNativeCall(startedAtMs, "surface", "attach-video");
        }
    }

    public void mpvDetachSurface() {
        long startedAtMs = beginMpvNativeCall("surface", "detach-video");
        try {
            MPVLib.detachSurface();
        } finally {
            endMpvNativeCall(startedAtMs, "surface", "detach-video");
        }
    }

    public void mpvDetachOsdSurface() {
        long startedAtMs = beginMpvNativeCall("surface", "detach-osd");
        try {
            MPVLib.detachOsdSurface();
        } finally {
            endMpvNativeCall(startedAtMs, "surface", "detach-osd");
        }
    }

    public int mpvEnqueueOsdSurface(long requestId, @Nullable Surface target) {
        String operation = target == null ? "detach-osd-async" : "attach-osd-async";
        long startedAtMs = beginMpvNativeCall("surface", operation);
        try {
            return MPVLib.enqueueOsdSurface(requestId, target);
        } finally {
            endMpvNativeCall(startedAtMs, "surface", operation);
        }
    }

    public void setOption(String name, String value) {
        if (value == null) value = "";
        try {
            mpvSetOptionString(name, value);
        } catch (Throwable ignored) {
        }
    }

    public void setRuntimeString(String name, String value) {
        setRuntimeStringChecked(name, value);
    }

    public boolean setRuntimeStringChecked(String name, String value) {
        return setRuntimeStringChecked(host.isInitialized(), name, value);
    }

    public boolean setRuntimeStringChecked(boolean initialized, String name, String value) {
        if (value == null) value = "";
        if (!initialized) return false;
        try {
            return mpvSetPropertyString(name, value) >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void observe(String property, int format) {
        host.getPropertySnapshot().register(property);
        try {
            mpvObserveProperty(property, format);
        } catch (Throwable ignored) {
        }
    }

    public void safeSetPropertyBoolean(String property, boolean value) {
        try {
            mpvSetPropertyBoolean(property, value);
        } catch (Throwable ignored) {
        }
    }

    public void safeSetPropertyDouble(String property, double value) {
        try {
            mpvSetPropertyDouble(property, value);
        } catch (Throwable ignored) {
        }
    }

    public void safeSetPropertyInt(String property, int value) {
        try {
            mpvSetPropertyInt(property, value);
        } catch (Throwable ignored) {
        }
    }

    public void safeSetPropertyString(String property, String value) {
        try {
            mpvSetPropertyString(property, value);
        } catch (Throwable ignored) {
        }
    }

    public void safeSetPropertyLong(String property, long value) {
        try {
            mpvSetPropertyInt(property, (int) value);
        } catch (Throwable ignored) {
        }
    }

    public void safeCommand(String[] command) {
        try {
            mpvCommand(command);
        } catch (Throwable ignored) {
        }
    }

    public boolean enqueueMpvCommand(String... command) {
        return enqueueMpvCommand(host.isInitialized(), command);
    }

    public boolean enqueueMpvCommand(boolean initialized, String... command) {
        if (!initialized) return false;
        long requestId = nextRequestId();
        try {
            int result = mpvEnqueueCommand(requestId, command);
            if (result >= MPVLib.MpvError.MPV_ERROR_SUCCESS) return true;
            Log.e(TAG, "Unable to enqueue mpv command request=" + requestId + " error=" + result);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to enqueue mpv command request=" + requestId, e);
        }
        return false;
    }

    public long doublePropertyMs(String property, long fallback) {
        try {
            Double value = mpvGetPropertyDouble(property);
            if (value == null || value.isNaN() || value.isInfinite()) return fallback;
            return Math.max(0, Math.round(value * SECONDS_TO_MS));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public double doubleProperty(String property, double fallback) {
        try {
            Double value = mpvGetPropertyDouble(property);
            if (value == null || value.isNaN() || value.isInfinite()) return fallback;
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public double doubleValue(@Nullable Object value, double fallback) {
        if (!(value instanceof Number number)) return fallback;
        double result = number.doubleValue();
        return Double.isNaN(result) || Double.isInfinite(result) ? fallback : result;
    }

    public long doubleSecondsToMs(@Nullable Object value, long fallback) {
        if (value instanceof Number number) return Math.max(0, Math.round(number.doubleValue() * SECONDS_TO_MS));
        return fallback;
    }

    public long longValue(@Nullable Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        return fallback;
    }

    public long longProperty(String property, long fallback) {
        try {
            Integer value = mpvGetPropertyInt(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Nullable
    public Long nullableLongProperty(String property) {
        try {
            Integer value = mpvGetPropertyInt(property);
            return value == null ? null : value.longValue();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public int intProperty(String property, int fallback) {
        try {
            Integer value = mpvGetPropertyInt(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public boolean booleanProperty(String property, boolean fallback) {
        try {
            Boolean value = mpvGetPropertyBoolean(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public String stringProperty(String property, String fallback) {
        try {
            String value = mpvGetPropertyString(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void copySupportAssets(Context context, MpvPlayerConfig config) throws IOException {
        copyAsset(context, "cacert.pem", config.caFile());
        MpvFontConfig.write(config.configDir(), config.cacheDir());
    }

    public static void copyAsset(Context context, String name, File outFile) throws IOException {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(name, AssetManager.ACCESS_STREAMING)) {
            long size = in.available();
            if (outFile.length() == size && size > 0) return;
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create " + parent);
            }
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }
}
