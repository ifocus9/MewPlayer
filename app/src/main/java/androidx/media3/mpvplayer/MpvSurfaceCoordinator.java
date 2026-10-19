package androidx.media3.mpvplayer;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.lut.MpvLutShader;
import com.fongmi.android.tv.setting.MpvPerformanceSetting;
import com.github.catvod.crawler.SpiderDebug;

import is.xyz.mpv.MPVLib;

/**
 * Coordinator responsible for video output surface management, dual-layer OSD overlays,
 * window resize events, dynamic frame rate synchronization, and surface lifecycle.
 */
public final class MpvSurfaceCoordinator {

    private static final String TAG = "TV-mpv";
    private static final String SIZE_TAG = "MPV_SIZE";
    private static final float FRAME_RATE_REQUEST_EPSILON = 0.001f;

    public interface Host {
        MpvPlayerConfig getConfig();
        boolean isInitialized();
        boolean isReleased();
        @Nullable
        String getCurrentIsoUri();
        boolean isAndroidFelActive();
        boolean isObservedCurrentVo();
        @Nullable
        String getCachedCurrentVo();
        @Nullable
        String getEffectiveVo();
        boolean shouldBindSurface();
        boolean shouldDetachSurface();
        void attachNativeSurface(Surface target);
        void detachNativeSurface();
        void detachNativeOsdSurface();
        int enqueueNativeOsdSurface(long requestId, @Nullable Surface target);
        boolean enqueueMpvCommand(String... command);
        void resumePendingSurfaceLoad();
        void onVideoOutputFailed(Throwable error);
        void rememberLog(String message);
        void markFailureSignal(String message);
        boolean booleanProperty(String name, boolean defaultValue);
        String propertyStringOrInt(String name);
        String currentTrackId(int trackType);
        boolean isDisabledTrackChoice(String choice);
    }

    private final Host host;

    private Object videoOutput;
    private SurfaceHolder surfaceHolder;
    private SurfaceHolder osdSurfaceHolder;
    private SurfaceView osdSurfaceView;
    private Surface surface;
    private Surface osdSurface;
    private Surface attachedSurface;
    private Surface attachedOsdSurface;
    private Surface lastFrameRateSurface;
    private boolean ownsSurface;
    private boolean surfaceAttached;
    private boolean osdSurfaceAttached;
    private boolean osdSurfaceRequested;
    private boolean osdSurfaceUsedForCurrentMedia;
    private boolean initialOsdSurfaceRequested;

    private int surfaceWidth;
    private int surfaceHeight;
    private int osdSurfaceWidth;
    private int osdSurfaceHeight;
    private String appliedAndroidSurfaceSize;
    private String appliedAndroidOsdSurfaceSize;
    private String attachedVo;

    private float cachedContentFrameRate;
    private float lastRequestedFrameRate = Float.NaN;
    private int lastFrameRateCompatibility = -1;
    private int lastFrameRateStrategy = -1;

    private long pendingOsdSurfaceRequestId;
    private boolean pendingOsdSurfaceAttach;
    private Surface pendingOsdSurface;

    private MpvLutShader lutShader;
    private String appliedLutShaderPath;

    public MpvSurfaceCoordinator(Host host) {
        this.host = host;
    }

    public synchronized void setInitialOsdSurfaceRequested(boolean requested) {
        this.initialOsdSurfaceRequested = requested;
    }

    public synchronized boolean isInitialOsdSurfaceRequested() {
        return initialOsdSurfaceRequested;
    }

    public synchronized void onNewMediaStarting() {
        osdSurfaceUsedForCurrentMedia = initialOsdSurfaceRequested;
        setOsdSurfaceRequested(initialOsdSurfaceRequested);
    }

    public synchronized void setVideoOutput(@Nullable Object output) {
        clearSurfaceFrameRate();
        resetSurfaceFrameRateRequest();
        detachMpvSurface();
        detachSurfaceHolder();
        removeOsdSurfaceView();
        this.videoOutput = output;
        surfaceWidth = 0;
        surfaceHeight = 0;
        Log.d(SIZE_TAG, "mpv setVideoOutput output=" + surfaceOutputName(output));
        if (output instanceof SurfaceView view) {
            updateSurfaceSize(view);
            setSurfaceHolderInternal(view.getHolder());
        } else if (output instanceof TextureView view && view.getSurfaceTexture() != null) {
            updateSurfaceSize(view);
            releaseOwnedSurface();
            surface = new Surface(view.getSurfaceTexture());
            ownsSurface = true;
        } else if (output instanceof SurfaceHolder holder) {
            setSurfaceHolderInternal(holder);
        } else if (output instanceof Surface s) {
            releaseOwnedSurface();
            surface = s;
            ownsSurface = false;
        }
        reconcileOsdSurface();
        bindVideoOutput();
    }

    public synchronized void clearVideoOutput() {
        clearSurfaceFrameRate();
        resetSurfaceFrameRateRequest();
        detachSurfaceHolder();
        detachMpvSurface();
        removeOsdSurfaceView();
        releaseOwnedSurface();
        this.videoOutput = null;
        surface = null;
        surfaceWidth = 0;
        surfaceHeight = 0;
        osdSurfaceWidth = 0;
        osdSurfaceHeight = 0;
        resetAppliedSurfaceSizes();
    }

    @Nullable
    public synchronized Object getVideoOutput() {
        return videoOutput;
    }

    @Nullable
    public synchronized Surface getSurface() {
        return surface;
    }

    @Nullable
    public synchronized Surface getOsdSurface() {
        return osdSurface;
    }

    public synchronized boolean isSurfaceAttached() {
        return surfaceAttached;
    }

    public synchronized boolean isOsdSurfaceAttached() {
        return osdSurfaceAttached;
    }

    public synchronized boolean isOsdSurfaceRequested() {
        return osdSurfaceRequested;
    }

    public synchronized long getPendingOsdSurfaceRequestId() {
        return pendingOsdSurfaceRequestId;
    }

    public synchronized boolean isAnySurfaceAttached() {
        return surfaceAttached || osdSurfaceAttached;
    }

    public synchronized void onNativeContextReleased() {
        clearMpvSurfaceAttachmentState();
        osdSurfaceWidth = 0;
        osdSurfaceHeight = 0;
        resetAppliedSurfaceSizes();
    }

    public synchronized boolean isSurfaceValid() {
        return surface != null && surface.isValid();
    }

    public synchronized boolean isSurfaceReadyForLoad() {
        if (videoOutput instanceof SurfaceView view && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE
                && (surface == null || !surface.isValid() || !surfaceAttached
                || attachedSurface != surface)) return false;
        return !requiresOsdSurface() || !(videoOutput instanceof SurfaceView)
                || (pendingOsdSurfaceRequestId == 0
                && osdSurfaceAttached == osdSurfaceRequested);
    }

    public synchronized void requestIsoOsdSurface() {
        if (!TextUtils.isEmpty(host.getCurrentIsoUri())) {
            setOsdSurfaceRequested(true);
        }
    }

    public synchronized boolean requiresOsdSurface() {
        return "mediacodec_embed".equals(videoOutputVo()) || !TextUtils.isEmpty(host.getCurrentIsoUri());
    }

    public synchronized void setOsdSurfaceRequested(boolean requested) {
        if (requested) osdSurfaceUsedForCurrentMedia = true;
        requested = MpvOsdSurfacePolicy.shouldKeepSurface(requested, osdSurfaceUsedForCurrentMedia);
        requested = requested && requiresOsdSurface();
        if (osdSurfaceRequested != requested) {
            osdSurfaceRequested = requested;
            String primary = host.isInitialized() ? host.propertyStringOrInt("sid") : "pending";
            String secondary = host.isInitialized() ? host.propertyStringOrInt("secondary-sid") : "pending";
            SpiderDebug.log("mpv", "OSD surface requested=%s sid=%s secondarySid=%s visible=%s",
                    requested, primary, secondary,
                    host.isInitialized() && host.booleanProperty("sub-visibility", true));
        }
        reconcileOsdSurface();
    }

    public synchronized void syncOsdSurfaceRequirementFromMpv() {
        if (!host.isInitialized()) return;
        boolean subtitlesVisible = host.booleanProperty("sub-visibility", true);
        String primarySelection = host.propertyStringOrInt("sid");
        String secondarySelection = host.propertyStringOrInt("secondary-sid");
        if (!MpvOsdSurfacePolicy.needsCurrentTrackQuery(subtitlesVisible, primarySelection, secondarySelection)) {
            setOsdSurfaceRequested(false);
            return;
        }
        String primaryCurrent = host.isDisabledTrackChoice(primarySelection)
                ? "" : host.currentTrackId(androidx.media3.common.C.TRACK_TYPE_TEXT);
        String secondaryCurrent = host.isDisabledTrackChoice(secondarySelection)
                ? "" : host.propertyStringOrInt("current-tracks/sub2/id");
        boolean requested = requiresOsdSurface() && MpvOsdSurfacePolicy.requiresSurface(
                subtitlesVisible, primaryCurrent, primarySelection, secondaryCurrent, secondarySelection);
        setOsdSurfaceRequested(requested);
    }

    public synchronized void setCachedContentFrameRate(float frameRate) {
        this.cachedContentFrameRate = frameRate;
        applySurfaceFrameRate();
    }

    public synchronized float getCachedContentFrameRate() {
        return cachedContentFrameRate;
    }

    public synchronized void bindVideoOutput() {
        if (!host.isInitialized() || !host.shouldBindSurface() || surface == null || !surface.isValid()) return;
        try {
            boolean sameVideoSurface = surfaceAttached && attachedSurface == surface;
            String targetVo = videoOutputVo();
            if (sameVideoSurface) {
                applyAndroidSurfaceSize();
                applyAndroidOsdSurfaceSize();
                applySurfaceFrameRate();
                if (!TextUtils.equals(attachedVo, targetVo)) {
                    if (host.enqueueMpvCommand("set", "vo", targetVo)) attachedVo = targetVo;
                }
                Log.d(SIZE_TAG, "mpv resize attached surface cached=" + surfaceWidth + "x" + surfaceHeight + " vo=" + targetVo);
                SpiderDebug.log("mpv", "surface resized surface=%s size=%dx%d vo=%s", surface, surfaceWidth, surfaceHeight, targetVo);
                reconcileOsdSurface();
                host.resumePendingSurfaceLoad();
                return;
            }
            if (surfaceAttached || osdSurfaceAttached) detachMpvSurface();
            host.attachNativeSurface(surface);
            surfaceAttached = true;
            attachedSurface = surface;
            applyAndroidSurfaceSize();
            applyAndroidOsdSurfaceSize();
            applySurfaceFrameRate();
            if (host.enqueueMpvCommand("set", "vo", targetVo)) attachedVo = targetVo;
            Log.d(SIZE_TAG, "mpv bind surface valid=" + surface.isValid() + " cached=" + surfaceWidth + "x" + surfaceHeight + " vo=" + targetVo);
            SpiderDebug.log("mpv", "surface attached video=%s osd=%s size=%dx%d vo=%s", surface, osdSurface, surfaceWidth, surfaceHeight, targetVo);
            reconcileOsdSurface();
            host.resumePendingSurfaceLoad();
        } catch (Throwable e) {
            host.onVideoOutputFailed(e);
        }
    }

    public synchronized void detachMpvSurface() {
        detachMpvSurface(true);
    }

    public synchronized void detachMpvSurface(boolean resetVideoOutput) {
        if (!host.isInitialized()) return;
        if (!surfaceAttached && !osdSurfaceAttached && pendingOsdSurfaceRequestId == 0) return;
        if (!host.shouldDetachSurface()) {
            clearMpvSurfaceAttachmentState();
            return;
        }
        try {
            boolean detachDirectVideoFirst = surfaceAttached && "mediacodec_embed".equals(activeVideoOutputVo());
            if (detachDirectVideoFirst) host.detachNativeSurface();
            if (resetVideoOutput) {
                host.enqueueMpvCommand("set", "vo", "null");
                host.enqueueMpvCommand("set", "force-window", "no");
            }
            if (osdSurfaceAttached || pendingOsdSurfaceRequestId != 0) {
                host.detachNativeOsdSurface();
            }
            if (surfaceAttached && !detachDirectVideoFirst) host.detachNativeSurface();
        } catch (Throwable ignored) {
        }
        clearMpvSurfaceAttachmentState();
    }

    public synchronized void handleOsdSurfaceReply(long requestId, int error) {
        if (requestId != pendingOsdSurfaceRequestId) return;
        boolean attach = pendingOsdSurfaceAttach;
        Surface requestedSurface = pendingOsdSurface;
        pendingOsdSurfaceRequestId = 0;
        pendingOsdSurfaceAttach = false;
        pendingOsdSurface = null;
        if (error >= MPVLib.MpvError.MPV_ERROR_SUCCESS) {
            osdSurfaceAttached = attach;
            attachedOsdSurface = attach ? requestedSurface : null;
            SpiderDebug.log("mpv", "OSD surface update applied request=%d attach=%s surface=%s",
                    requestId, attach, requestedSurface);
        } else {
            String message = "OSD surface update failed request=" + requestId + " attach=" + attach + " error=" + error;
            Log.e(TAG, message);
            host.rememberLog(message);
            host.markFailureSignal(message);
        }
        reconcileOsdSurface();
        if (error >= MPVLib.MpvError.MPV_ERROR_SUCCESS) {
            host.resumePendingSurfaceLoad();
        }
    }

    public synchronized int getSurfaceWidth() {
        return surfaceWidth;
    }

    public synchronized int getSurfaceHeight() {
        return surfaceHeight;
    }

    public synchronized boolean isPendingOsdSurfaceRequestId(long requestId) {
        return pendingOsdSurfaceRequestId != 0 && pendingOsdSurfaceRequestId == requestId;
    }

    public synchronized void applyLutShaderToNative() {
        if (!host.isInitialized()) return;
        MpvLutShader target = lutShader;
        String path = target == null ? "" : target.getPath();
        if (TextUtils.equals(appliedLutShaderPath, path)) return;
        appliedLutShaderPath = path;
        host.enqueueMpvCommand("change-list", "glsl-shaders", "set", path);
    }

    @Nullable
    public synchronized MpvLutShader getLutShader() {
        return lutShader;
    }

    public synchronized void setLutShader(@Nullable MpvLutShader shader) {
        this.lutShader = shader;
        applyLutShaderToNative();
    }

    public synchronized void setLutPreviewProgress(float progress) {
        if (lutShader != null && host.isInitialized()) {
            host.enqueueMpvCommand("set", "glsl-shader-opts", "preview_split=" + progress);
        }
    }

    public synchronized String videoOutputVo() {
        String effective = host.getEffectiveVo();
        return TextUtils.isEmpty(effective) ? host.getConfig().vo() : effective;
    }

    public synchronized String activeVideoOutputVo() {
        if (host.isAndroidFelActive()) return "gpu-next";
        String cached = host.getCachedCurrentVo();
        return host.isObservedCurrentVo() && !TextUtils.isEmpty(cached) ? cached : videoOutputVo();
    }

    private void setSurfaceHolderInternal(SurfaceHolder holder) {
        surfaceHolder = holder;
        updateSurfaceSize(holder);
        Log.d(SIZE_TAG, "mpv setSurfaceHolder frame=" + surfaceFrame(holder) + " cached=" + surfaceWidth + "x" + surfaceHeight);
        surfaceHolder.addCallback(surfaceCallback);
        surface = surfaceHolder.getSurface();
        ownsSurface = false;
    }

    private void detachSurfaceHolder() {
        if (surfaceHolder == null) return;
        try {
            surfaceHolder.removeCallback(surfaceCallback);
        } catch (Throwable ignored) {
        }
        surfaceHolder = null;
    }

    private void createOsdSurfaceView() {
        if (!requiresOsdSurface() || osdSurfaceView != null || !(videoOutput instanceof SurfaceView videoView)) return;
        if (!(videoView.getParent() instanceof ViewGroup parent)) {
            Log.e(TAG, "Unable to create direct-output OSD surface without a ViewGroup parent");
            return;
        }

        SurfaceView overlay = new SurfaceView(videoView.getContext());
        overlay.setZOrderMediaOverlay(true);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int videoIndex = parent.indexOfChild(videoView);
        int overlayIndex = videoIndex < 0 ? parent.getChildCount() : Math.min(parent.getChildCount(), videoIndex + 1);
        osdSurfaceView = overlay;
        osdSurfaceHolder = overlay.getHolder();
        osdSurfaceHolder.addCallback(osdSurfaceCallback);
        parent.addView(overlay, overlayIndex);
        osdSurface = osdSurfaceHolder.getSurface();
        SpiderDebug.log("mpv", "created transparent OSD SurfaceView parent=%s index=%d",
                parent.getClass().getSimpleName(), overlayIndex);
    }

    private void removeOsdSurfaceView() {
        if (osdSurfaceHolder != null) {
            try {
                osdSurfaceHolder.removeCallback(osdSurfaceCallback);
            } catch (Throwable ignored) {
            }
        }
        if (osdSurfaceView != null && osdSurfaceView.getParent() instanceof ViewGroup parent) {
            try {
                parent.removeView(osdSurfaceView);
            } catch (Throwable ignored) {
            }
        }
        osdSurfaceHolder = null;
        osdSurfaceView = null;
        osdSurface = null;
        osdSurfaceWidth = 0;
        osdSurfaceHeight = 0;
        appliedAndroidOsdSurfaceSize = null;
    }

    private void reconcileOsdSurface() {
        if (osdSurfaceRequested) createOsdSurfaceView();
        if (!host.isInitialized() || !host.shouldBindSurface()) return;
        if (pendingOsdSurfaceRequestId != 0) return;

        Surface target = osdSurface;
        boolean targetValid = target != null && target.isValid();
        if (osdSurfaceRequested) {
            if (!surfaceAttached) return;
            if (!targetValid) {
                if (osdSurfaceAttached) enqueueOsdSurfaceUpdate(false, null);
                return;
            }
            if (osdSurfaceAttached && attachedOsdSurface == target) return;
            if (osdSurfaceAttached) {
                enqueueOsdSurfaceUpdate(false, null);
            } else {
                enqueueOsdSurfaceUpdate(true, target);
            }
            return;
        }

        if (osdSurfaceAttached) {
            enqueueOsdSurfaceUpdate(false, null);
        } else {
            removeOsdSurfaceView();
        }
    }

    private void enqueueOsdSurfaceUpdate(boolean attach, @Nullable Surface target) {
        if (pendingOsdSurfaceRequestId != 0) return;
        if (attach && (target == null || !target.isValid())) return;
        long requestId = System.identityHashCode(this) + System.currentTimeMillis();
        pendingOsdSurfaceRequestId = requestId;
        pendingOsdSurfaceAttach = attach;
        pendingOsdSurface = target;
        try {
            int result = host.enqueueNativeOsdSurface(requestId, attach ? target : null);
            if (result < MPVLib.MpvError.MPV_ERROR_SUCCESS) {
                pendingOsdSurfaceRequestId = 0;
                pendingOsdSurfaceAttach = false;
                pendingOsdSurface = null;
                SpiderDebug.log("mpv", "OSD surface queue failed attach=%s error=%d", attach, result);
                return;
            }
            SpiderDebug.log("mpv", "OSD surface update queued request=%d attach=%s surface=%s",
                    requestId, attach, target);
        } catch (Throwable e) {
            pendingOsdSurfaceRequestId = 0;
            pendingOsdSurfaceAttach = false;
            pendingOsdSurface = null;
            SpiderDebug.log("mpv", "OSD surface queue failed attach=%s error=%s", attach, e.getMessage());
        }
    }

    private void clearMpvSurfaceAttachmentState() {
        surfaceAttached = false;
        osdSurfaceAttached = false;
        pendingOsdSurfaceRequestId = 0;
        pendingOsdSurfaceAttach = false;
        pendingOsdSurface = null;
        attachedSurface = null;
        attachedOsdSurface = null;
        attachedVo = null;
    }

    private void updateSurfaceSize(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        surfaceWidth = view.getWidth();
        surfaceHeight = view.getHeight();
        Log.d(SIZE_TAG, "mpv updateSurfaceSize view=" + surfaceOutputName(view) + " size=" + surfaceWidth + "x" + surfaceHeight);
    }

    private void updateSurfaceSize(SurfaceHolder holder) {
        if (holder == null) return;
        Rect frame = holder.getSurfaceFrame();
        if (frame == null || frame.width() <= 0 || frame.height() <= 0) return;
        surfaceWidth = frame.width();
        surfaceHeight = frame.height();
        Log.d(SIZE_TAG, "mpv updateSurfaceSize holder frame=" + frame.width() + "x" + frame.height());
    }

    private void updateSurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        surfaceWidth = width;
        surfaceHeight = height;
        Log.d(SIZE_TAG, "mpv updateSurfaceSize changed=" + surfaceWidth + "x" + surfaceHeight);
    }

    private void updateOsdSurfaceSize(SurfaceHolder holder) {
        if (holder == null) return;
        Rect frame = holder.getSurfaceFrame();
        if (frame == null) return;
        updateOsdSurfaceSize(frame.width(), frame.height());
    }

    private void updateOsdSurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        osdSurfaceWidth = width;
        osdSurfaceHeight = height;
        Log.d(SIZE_TAG, "mpv updateOsdSurfaceSize changed=" + osdSurfaceWidth + "x" + osdSurfaceHeight);
    }

    private void applyAndroidSurfaceSize() {
        String targetVo = videoOutputVo();
        if (!MpvSurfaceSizePolicy.usesAndroidSurfaceSize(targetVo)) return;
        String value = MpvSurfaceSizePolicy.sizeValue(surfaceWidth, surfaceHeight);
        if (value == null || TextUtils.equals(appliedAndroidSurfaceSize, value)) return;
        if (!host.enqueueMpvCommand("set", "android-surface-size", value)) return;
        appliedAndroidSurfaceSize = value;
        Log.d(SIZE_TAG, "mpv android-surface-size queued=" + value + " vo=" + targetVo);
    }

    private void applyAndroidOsdSurfaceSize() {
        boolean validSurface = osdSurface != null && osdSurface.isValid();
        if (!MpvSurfaceSizePolicy.shouldApplyOsdSize(
                osdSurfaceRequested, validSurface, osdSurfaceWidth, osdSurfaceHeight)) return;
        String value = MpvSurfaceSizePolicy.sizeValue(osdSurfaceWidth, osdSurfaceHeight);
        if (TextUtils.equals(appliedAndroidOsdSurfaceSize, value)) return;
        if (!host.enqueueMpvCommand("set", "android-osd-surface-size", value)) return;
        appliedAndroidOsdSurfaceSize = value;
        Log.d(SIZE_TAG, "mpv android-osd-surface-size queued=" + value);
    }

    private void resetAppliedSurfaceSizes() {
        appliedAndroidSurfaceSize = null;
        appliedAndroidOsdSurfaceSize = null;
    }

    private void applySurfaceFrameRate() {
        Surface target = surface;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || target == null || !target.isValid()) return;
        float rate = MpvPerformanceSetting.getFrameRateMode() == MpvPerformanceSetting.FRAME_RATE_SEAMLESS ? cachedContentFrameRate : 0f;
        if (rate < 0) rate = 0f;
        int compatibility = rate > 0f ? Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE : Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;
        requestSurfaceFrameRate(target, rate, compatibility, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
    }

    private void requestSurfaceFrameRate(Surface target, float rate, int compatibility, int strategy) {
        if (target == lastFrameRateSurface
                && Math.abs(rate - lastRequestedFrameRate) <= FRAME_RATE_REQUEST_EPSILON
                && compatibility == lastFrameRateCompatibility
                && strategy == lastFrameRateStrategy) return;
        try {
            target.setFrameRate(rate, compatibility, strategy);
            lastFrameRateSurface = target;
            lastRequestedFrameRate = rate;
            lastFrameRateCompatibility = compatibility;
            lastFrameRateStrategy = strategy;
            SpiderDebug.log("mpv", "surface frame rate request=%.3f mode=%s", rate, MpvPerformanceSetting.getFrameRateText());
        } catch (Throwable e) {
            SpiderDebug.log("mpv", "surface frame rate request failed rate=%.3f error=%s", rate, e.getMessage());
        }
    }

    public synchronized void clearSurfaceFrameRate() {
        Surface target = surface;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && target != null && target.isValid() && target == lastFrameRateSurface) {
            requestSurfaceFrameRate(target, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
        }
    }

    private void resetSurfaceFrameRateRequest() {
        lastFrameRateSurface = null;
        lastRequestedFrameRate = Float.NaN;
        lastFrameRateCompatibility = -1;
        lastFrameRateStrategy = -1;
    }

    private String surfaceOutputName(Object output) {
        if (output == null) return "null";
        return output.getClass().getSimpleName();
    }

    private String surfaceFrame(SurfaceHolder holder) {
        if (holder == null || holder.getSurfaceFrame() == null) return "null";
        Rect frame = holder.getSurfaceFrame();
        return frame.width() + "x" + frame.height();
    }

    private void releaseOwnedSurface() {
        if (ownsSurface && surface != null) surface.release();
        ownsSurface = false;
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surface = holder.getSurface();
            updateSurfaceSize(holder);
            Log.d(SIZE_TAG, "mpv surfaceCreated frame=" + surfaceFrame(holder) + " valid=" + (surface != null && surface.isValid()));
            bindVideoOutput();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            surface = holder.getSurface();
            updateSurfaceSize(width, height);
            Log.d(SIZE_TAG, "mpv surfaceChanged format=" + format + " size=" + width + "x" + height + " frame=" + surfaceFrame(holder));
            bindVideoOutput();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(SIZE_TAG, "mpv surfaceDestroyed frame=" + surfaceFrame(holder));
            clearSurfaceFrameRate();
            resetSurfaceFrameRateRequest();
            surface = null;
            detachMpvSurface(false);
            host.resumePendingSurfaceLoad();
        }
    };

    private final SurfaceHolder.Callback osdSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            osdSurface = holder.getSurface();
            updateOsdSurfaceSize(holder);
            Log.d(SIZE_TAG, "mpv OSD surfaceCreated frame=" + surfaceFrame(holder)
                    + " valid=" + (osdSurface != null && osdSurface.isValid()));
            applyAndroidOsdSurfaceSize();
            reconcileOsdSurface();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            osdSurface = holder.getSurface();
            updateOsdSurfaceSize(width, height);
            applyAndroidOsdSurfaceSize();
            Log.d(SIZE_TAG, "mpv OSD surfaceChanged format=" + format + " size=" + width + "x" + height);
            reconcileOsdSurface();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(SIZE_TAG, "mpv OSD surfaceDestroyed frame=" + surfaceFrame(holder));
            osdSurface = null;
            osdSurfaceWidth = 0;
            osdSurfaceHeight = 0;
            appliedAndroidOsdSurfaceSize = null;
            if (MpvOsdSurfacePolicy.shouldDeferDestroyedSurfaceDetach(osdSurfaceRequested, surfaceAttached)) {
                SpiderDebug.log("mpv", "defer OSD detach until video Surface loss");
                return;
            }
            reconcileOsdSurface();
        }
    };
}
