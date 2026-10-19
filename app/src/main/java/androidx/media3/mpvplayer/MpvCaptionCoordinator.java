package androidx.media3.mpvplayer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.Locale;

/**
 * Coordinates subtitle style, font rendering, text offset, and audio offset options for MpvPlayer.
 */
public final class MpvCaptionCoordinator {

    private static final double SECONDS_TO_MS = 1000.0;
    private static final double DEFAULT_SUBTITLE_TEXT_SIZE_FRACTION = 0.0533;

    public interface Host {
        Context getContext();
        boolean isInitialized();
        void safeSetPropertyDouble(String property, double value);
        void safeSetPropertyString(String property, String value);
    }

    private final Host host;
    private long textOffsetMs;
    private long audioOffsetMs;
    private float subtitleTextSize;
    private float subtitlePosition;

    public MpvCaptionCoordinator(Host host) {
        this.host = host;
    }

    public long getTextOffsetMs() {
        return textOffsetMs;
    }

    public void setTextOffsetMs(long textOffsetMs) {
        this.textOffsetMs = textOffsetMs;
    }

    public long getAudioOffsetMs() {
        return audioOffsetMs;
    }

    public void setAudioOffsetMs(long audioOffsetMs) {
        this.audioOffsetMs = audioOffsetMs;
    }

    public float getSubtitleTextSize() {
        return subtitleTextSize;
    }

    public float getSubtitlePosition() {
        return subtitlePosition;
    }

    public void setSubtitleStyle(float textSize, float position) {
        this.subtitleTextSize = textSize;
        this.subtitlePosition = position;
        applySubtitleStyle();
    }

    public void applyTextOffset() {
        if (host.isInitialized()) host.safeSetPropertyDouble("sub-delay", textOffsetMs / SECONDS_TO_MS);
    }

    public void applyAudioOffset() {
        if (host.isInitialized()) host.safeSetPropertyDouble("audio-delay", audioOffsetMs / SECONDS_TO_MS);
    }

    public void applySubtitleStyle() {
        if (!host.isInitialized()) return;
        CaptionStyle style = captionStyle();
        host.safeSetPropertyDouble("sub-scale", subtitleScale());
        host.safeSetPropertyDouble("sub-pos", subtitlePosition());
        host.safeSetPropertyString("sub-font", style.font);
        host.safeSetPropertyString("sub-bold", style.bold ? "yes" : "no");
        host.safeSetPropertyString("sub-italic", style.italic ? "yes" : "no");
        host.safeSetPropertyString("sub-color", mpvColor(style.foreground));
        host.safeSetPropertyString("sub-border-color", mpvColor(style.edge));
        host.safeSetPropertyString("sub-shadow-color", mpvColor(style.back));
        host.safeSetPropertyString("sub-back-color", mpvColor(style.back));
        host.safeSetPropertyString("sub-border-style", style.borderStyle);
        host.safeSetPropertyDouble("sub-border-size", style.borderSize);
        host.safeSetPropertyDouble("sub-shadow-offset", style.shadowOffset);
    }

    private double subtitleScale() {
        if (subtitleTextSize <= 0) return 1.0;
        return Math.max(0.5, Math.min(2.5, subtitleTextSize / DEFAULT_SUBTITLE_TEXT_SIZE_FRACTION));
    }

    private double subtitlePosition() {
        return Math.max(0, Math.min(150, 100.0 - subtitlePosition * 100.0));
    }

    private CaptionStyle captionStyle() {
        if (!PlayerSetting.isCaption()) return defaultCaptionStyle();
        try {
            CaptioningManager manager = (CaptioningManager) host.getContext().getSystemService(Context.CAPTIONING_SERVICE);
            CaptioningManager.CaptionStyle style = manager == null ? null : manager.getUserStyle();
            if (style == null) return defaultCaptionStyle();
            int foreground = style.hasForegroundColor() ? style.foregroundColor : Color.WHITE;
            int background = style.hasBackgroundColor() ? style.backgroundColor : Color.TRANSPARENT;
            int edge = style.hasEdgeColor() ? style.edgeColor : Color.BLACK;
            int edgeType = style.hasEdgeType() ? style.edgeType : CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE;
            Typeface typeface = style.getTypeface();
            String font = captionFont(typeface);
            boolean bold = typeface != null && typeface.isBold();
            boolean italic = typeface != null && typeface.isItalic();
            return switch (edgeType) {
                case CaptioningManager.CaptionStyle.EDGE_TYPE_NONE -> captionStyle(font, bold, italic, foreground, edge, background, Color.TRANSPARENT, 0.0, 0.0);
                case CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> captionStyle(font, bold, italic, foreground, edge, background, edge, 0.0, 2.0);
                case CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED, CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> captionStyle(font, bold, italic, foreground, edge, background, edge, 1.0, 1.0);
                default -> captionStyle(font, bold, italic, foreground, edge, background, Color.TRANSPARENT, 3.0, 0.0);
            };
        } catch (Throwable ignored) {
            return defaultCaptionStyle();
        }
    }

    private CaptionStyle captionStyle(String font, boolean bold, boolean italic, int foreground, int edge, int background, int shadow, double borderSize, double shadowOffset) {
        boolean hasBackground = Color.alpha(background) > 0;
        return new CaptionStyle(font, bold, italic, foreground, edge, hasBackground ? background : shadow, hasBackground ? "background-box" : "outline-and-shadow", borderSize, hasBackground ? 2.0 : shadowOffset);
    }

    private CaptionStyle defaultCaptionStyle() {
        return new CaptionStyle("sans-serif", false, false, Color.WHITE, Color.BLACK, Color.TRANSPARENT, "outline-and-shadow", 3.0, 0.0);
    }

    private String captionFont(@Nullable Typeface typeface) {
        if (typeface == null || Typeface.DEFAULT.equals(typeface) || Typeface.SANS_SERIF.equals(typeface)) return "sans-serif";
        if (Typeface.SERIF.equals(typeface)) return "serif";
        if (Typeface.MONOSPACE.equals(typeface)) return "monospace";
        String value = typeface.toString().toLowerCase(Locale.US);
        if (value.contains("mono")) return "monospace";
        if (value.contains("serif")) return "serif";
        return "sans-serif";
    }

    private String mpvColor(int color) {
        return String.format(Locale.US, "%.4f/%.4f/%.4f/%.4f", Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0, Color.alpha(color) / 255.0);
    }

    public record CaptionStyle(String font, boolean bold, boolean italic, int foreground, int edge, int back, String borderStyle, double borderSize, double shadowOffset) {
    }
}
