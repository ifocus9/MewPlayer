package com.fongmi.android.tv.player;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

public class PlayerHelper {

    public static String getDefaultUa() {
        return Util.getUserAgent(App.get(), BuildConfig.APPLICATION_ID);
    }

    public static String getUa() {
        return resolveUa(Setting.getUa(), PlayerHelper::getDefaultUa);
    }

    public static String resolveUa(String ua, Supplier<String> fallback) {
        return TextUtils.isEmpty(ua) ? fallback.get() : ua;
    }

    public static String getSubtitleMimeType(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (path.endsWith(".ssa") || path.endsWith(".ass")) return MimeTypes.TEXT_SSA;
        if (path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    public static Bundle toBundle(Map<String, String> headers) {
        Bundle bundle = new Bundle();
        headers.forEach(bundle::putString);
        return bundle;
    }

    public static String describeFormat(Format format) {
        StringJoiner joiner = new StringJoiner(",");
        if (format.id != null) joiner.add(format.id);
        if (format.label != null) joiner.add(format.label);
        if (format.codecs != null) joiner.add(format.codecs);
        if (format.language != null) joiner.add(format.language);
        if (format.sampleMimeType != null) joiner.add(format.sampleMimeType);
        if (format.containerMimeType != null) joiner.add(format.containerMimeType);
        if (format.width != C.LENGTH_UNSET) joiner.add(String.valueOf(format.width));
        if (format.height != C.LENGTH_UNSET) joiner.add(String.valueOf(format.height));
        if (format.sampleRate != C.RATE_UNSET_INT) joiner.add(String.valueOf(format.sampleRate));
        if (format.channelCount != C.LENGTH_UNSET) joiner.add(String.valueOf(format.channelCount));
        if (format.averageBitrate != C.LENGTH_UNSET) joiner.add(String.valueOf(format.averageBitrate));
        return joiner.toString();
    }

    public static void share(Activity activity, String url, Map<String, String> headers, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            Bundle bundle = toBundle(headers);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, url);
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title).putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(getChooser(intent));
        } catch (Exception ignored) {
        }
    }

    public static void download(Activity activity, String url, Map<String, String> headers, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            String[] idmPackages = new String[]{
                    "idm.internet.download.manager.plus",
                    "idm.internet.download.manager",
                    "idm.internet.download.manager.adm.lite",
                    "com.dv.adm.pay",
                    "com.dv.adm"
            };

            PackageManager pm = activity.getPackageManager();
            String targetPackage = null;
            for (String pkg : idmPackages) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    targetPackage = pkg;
                    break;
                } catch (Exception ignored) {
                }
            }

            // Case-insensitive header lookup
            String referer = getHeader(headers, "Referer");
            String userAgent = getHeader(headers, "User-Agent");
            if (userAgent.isEmpty()) userAgent = getUa();
            String cookie = getHeader(headers, "Cookie");

            Bundle bundle = new Bundle();
            if (headers != null) {
                headers.forEach(bundle::putString);
            }
            if (!userAgent.isEmpty() && !hasHeader(bundle, "User-Agent")) {
                bundle.putString("User-Agent", userAgent);
            }
            if (!referer.isEmpty() && !hasHeader(bundle, "Referer")) {
                bundle.putString("Referer", referer);
            }
            if (!cookie.isEmpty() && !hasHeader(bundle, "Cookie")) {
                bundle.putString("Cookie", cookie);
            }

            List<String> list = new ArrayList<>();
            for (String key : bundle.keySet()) {
                list.add(key);
                list.add(bundle.getString(key));
            }

            String fileName = title != null ? title.toString().trim() : "";
            if (!fileName.isEmpty()) {
                fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (!fileName.contains(".")) {
                    fileName = fileName + (url.contains(".m3u8") ? ".mp4" : ".mp4");
                }
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (!fileName.isEmpty()) {
                intent.putExtra("title", fileName);
                intent.putExtra("extra_filename", fileName);
            }

            // 1DM 官方标准参数 (注意：不要传 secure_uri=true，否则 1DM 会将其作为安全网址锁定，导致无法直接下载并显示更新链接)
            if (!referer.isEmpty()) intent.putExtra("extra_referer", referer);
            if (!userAgent.isEmpty()) intent.putExtra("extra_useragent", userAgent);
            if (!cookie.isEmpty()) intent.putExtra("extra_cookies", cookie);

            // 通用及 1DM 支持的 Headers Bundle
            intent.putExtra("headers", bundle);
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("android.media.intent.extra.HTTP_HEADERS", bundle);
            if (!list.isEmpty()) intent.putExtra("headers_array", list.toArray(String[]::new));

            intent.putExtra("force_editor", true);

            if (targetPackage != null) {
                try {
                    if (targetPackage.startsWith("idm.internet.download.manager")) {
                        intent.setComponent(new ComponentName(targetPackage, "idm.internet.download.manager.Downloader"));
                    } else {
                        intent.setPackage(targetPackage);
                    }
                    activity.startActivity(intent);
                } catch (Exception e) {
                    intent.setComponent(null);
                    intent.setPackage(targetPackage);
                    activity.startActivity(intent);
                }
            } else {
                Notify.show(R.string.idm_not_found);
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.detail_download)));
            }
        } catch (Exception e) {
            Notify.show(e.getMessage());
        }
    }

    public static void openDownloadApp(Activity activity) {
        if (activity == null) return;
        try {
            String[] idmPackages = new String[]{
                    "idm.internet.download.manager.plus",
                    "idm.internet.download.manager",
                    "idm.internet.download.manager.adm.lite",
                    "com.dv.adm.pay",
                    "com.dv.adm"
            };
            PackageManager pm = activity.getPackageManager();
            for (String pkg : idmPackages) {
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(launchIntent);
                    return;
                }
            }
            Notify.show(R.string.idm_not_found);
        } catch (Exception e) {
            Notify.show(e.getMessage());
        }
    }

    private static boolean hasHeader(Bundle bundle, String name) {
        if (bundle == null) return false;
        for (String key : bundle.keySet()) {
            if (name.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String getHeader(Map<String, String> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public static void choose(Activity activity, String url, Map<String, String> headers, boolean isVod, long position, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            List<String> list = new ArrayList<>();
            headers.forEach((key, value) -> {
                list.add(key);
                list.add(value);
            });
            Uri data = url.startsWith("file://") || url.startsWith("/") ? FileUtil.getShareUri(url) : Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title).putExtra("return_result", isVod);
            intent.putExtra("headers", list.toArray(String[]::new));
            if (isVod) intent.putExtra("position", (int) position);
            activity.startActivityForResult(getChooser(intent), 1001);
        } catch (Exception ignored) {
        }
    }

    public static void onExternalResult(Intent data, Runnable onNext, LongConsumer seekTo) {
        try {
            if (data == null || data.getExtras() == null) return;
            long position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) App.post(onNext);
            if ("user".equals(endBy)) seekTo.accept(position);
        } catch (Exception ignored) {
        }
    }

    private static Intent getChooser(Intent intent) {
        List<ComponentName> components = new ArrayList<>();
        for (ResolveInfo resolveInfo : App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            String pkgName = resolveInfo.activityInfo.packageName;
            if (pkgName.equals(App.get().getPackageName())) {
                components.add(new ComponentName(pkgName, resolveInfo.activityInfo.name));
            }
        }
        return Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, components.toArray(new ComponentName[0]));
    }
}
