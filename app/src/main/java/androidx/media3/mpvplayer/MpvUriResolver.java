package androidx.media3.mpvplayer;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles concatenating playlist (EDL) conversion, content URI resolution,
 * and media descriptor management for MpvPlayer.
 */
public final class MpvUriResolver {

    private static final String CONCAT_SOURCE_SEPARATOR = "***";
    private static final String CONCAT_SOURCE_SEPARATOR_REGEX = "\\*\\*\\*";
    private static final String CONCAT_DURATION_SEPARATOR = "|||";
    private static final String CONCAT_DURATION_SEPARATOR_REGEX = "\\|\\|\\|";
    private static final double MICROSECONDS_TO_SECONDS = 1_000_000.0;

    private final List<ParcelFileDescriptor> contentFds = new ArrayList<>();

    public String playableUri(Context context, MediaItem item) throws IOException {
        Uri requestUri = item.requestMetadata.mediaUri;
        if (requestUri != null && isConcatenatingUri(requestUri.toString())) {
            return edlUri(requestUri.toString());
        }
        return playableUri(context, item.localConfiguration.uri);
    }

    public String playableUri(Context context, Uri uri) throws IOException {
        String value = uri.toString();
        if (isConcatenatingUri(value)) return edlUri(value);
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new IOException("Unable to open content uri: " + uri);
            contentFds.add(fd);
            return "fd://" + fd.getFd();
        }
        return value;
    }

    public static boolean isImageUri(String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        String lower = uri.toLowerCase(Locale.US);
        int end = lower.length();
        int query = lower.indexOf('?');
        int fragment = lower.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        lower = lower.substring(0, end);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    public static boolean sameUri(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    public static boolean isConcatenatingUri(String uri) {
        return uri != null && uri.contains(CONCAT_SOURCE_SEPARATOR) && uri.contains(CONCAT_DURATION_SEPARATOR);
    }

    public static String edlUri(String uri) throws IOException {
        StringBuilder builder = new StringBuilder("edl://");
        int count = 0;
        for (String split : uri.split(CONCAT_SOURCE_SEPARATOR_REGEX)) {
            String[] info = split.split(CONCAT_DURATION_SEPARATOR_REGEX, 2);
            if (info.length < 2 || TextUtils.isEmpty(info[0])) continue;
            if (count++ > 0) builder.append(';');
            builder.append("file=").append(edlValue(info[0]));
            long durationUs = parseLong(info[1], C.TIME_UNSET);
            if (durationUs > 0) {
                builder.append(",length=").append(String.format(Locale.US, "%.3f", durationUs / MICROSECONDS_TO_SECONDS));
            }
        }
        if (count == 0) throw new IOException("Invalid concatenating media uri");
        SpiderDebug.log("mpv", "concat uri converted to EDL segments=%d", count);
        return builder.toString();
    }

    private static String edlValue(String value) {
        return "%" + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "%" + value;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public void closeContentFds() {
        if (contentFds.isEmpty()) return;
        for (ParcelFileDescriptor fd : contentFds) {
            try {
                fd.close();
            } catch (IOException ignored) {
            }
        }
        contentFds.clear();
    }
}
