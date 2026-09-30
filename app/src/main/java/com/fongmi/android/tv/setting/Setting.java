package com.fongmi.android.tv.setting;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.LocaleList;
import android.provider.Settings;
import android.util.DisplayMetrics;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.OciMirror;
import com.fongmi.android.tv.update.UpdateSource;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class Setting {

    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

    public static final int LANGUAGE_FOLLOW_SYSTEM = 0;
    public static final int LANGUAGE_SIMPLIFIED = 1;
    public static final int LANGUAGE_TRADITIONAL = 2;
    private static final int[] LANGUAGE_OPTIONS = {LANGUAGE_FOLLOW_SYSTEM, LANGUAGE_SIMPLIFIED, LANGUAGE_TRADITIONAL};

    public static final int CSP_WARMUP_DISABLED = 0;
    public static final int CSP_WARMUP_DEFAULT = 1;
    public static final int CSP_WARMUP_CUSTOM = 2;

    public static final int UI_SCALE_FOLLOW_SYSTEM = 0;
    public static final int UI_SCALE_STANDARD = 1;
    public static final int UI_SCALE_COMPACT = 2;
    public static final int UI_SCALE_SMALLER = 3;
    public static final int UI_SCALE_MILD_COMPACT = 4;
    public static final int UI_SCALE_MORE_COMPACT = 5;
    private static final int[] UI_SCALE_OPTIONS = {UI_SCALE_FOLLOW_SYSTEM, UI_SCALE_STANDARD, UI_SCALE_MILD_COMPACT, UI_SCALE_COMPACT, UI_SCALE_MORE_COMPACT, UI_SCALE_SMALLER};

    public static final int WALL_BLACK = 0;
    private static final int[] DEFAULT_WALLS = {WALL_BLACK};

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static String getHotTv() {
        return Prefers.getString("hot_tv");
    }

    public static void putHotTv(String hot) {
        Prefers.put("hot_tv", hot);
    }

    public static String getHotMovie() {
        return Prefers.getString("hot_movie");
    }

    public static void putHotMovie(String hot) {
        Prefers.put("hot_movie", hot);
    }

    public static String getHotVariety() {
        return Prefers.getString("hot_variety");
    }

    public static void putHotVariety(String hot) {
        Prefers.put("hot_variety", hot);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static int getWall() {
        return Prefers.getInt("wall", WALL_BLACK);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", wall);
    }

    public static int getWallType() {
        return Prefers.getInt("wall_type", 0);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", type);
    }

    public static int nextDefaultWall() {
        return WALL_BLACK;
    }

    public static int[] getDefaultWalls() {
        return DEFAULT_WALLS.clone();
    }

    public static int getDefaultWallIndex(int wall) {
        return wall == WALL_BLACK ? 0 : -1;
    }

    public static boolean isBuiltInWall(int wall) {
        return wall == WALL_BLACK;
    }

    public static boolean isBuiltInColorWall(int wall) {
        return wall == WALL_BLACK;
    }

    private static boolean isLegacyColorWall(int wall) {
        return false;
    }

    public static boolean isBuiltInDesignWall(int wall) {
        return false;
    }

    public static int getBuiltInWallColor(int wall) {
        return 0xFF000000;
    }

    public static String getBuiltInWallName(int wall) {
        return "默认黑色";
    }

    public static String getWallDesc(String desc) {
        return getWallType() == 0 && isBuiltInWall(getWall()) ? getBuiltInWallName(getWall()) : desc;
    }

    public static int getReset() {
        return Prefers.getInt("reset", 0);
    }

    public static void putReset(int reset) {
        Prefers.put("reset", reset);
    }

    public static int getSiteMode() {
        return Prefers.getInt("site_mode");
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", mode);
    }

    public static int getSiteColumn() {
        return Prefers.getInt("site_column", 1);
    }

    public static void putSiteColumn(int column) {
        Prefers.put("site_column", column);
    }

    public static int getSyncMode() {
        return Prefers.getInt("sync_mode");
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", mode);
    }

    public static String getSyncPaths() {
        return Prefers.getString("sync_paths", "TV\nTVBox\nTVData");
    }

    public static void putSyncPaths(String paths) {
        Prefers.put("sync_paths", paths);
    }

    public static String getLoginStatePaths() {
        return Prefers.getString("login_state_paths");
    }

    public static void putLoginStatePaths(String paths) {
        Prefers.put("login_state_paths", paths);
    }

    public static String getLoginStatePendingPaths() {
        return Prefers.getString("login_state_pending_paths");
    }

    public static void putLoginStatePendingPaths(String paths) {
        Prefers.put("login_state_pending_paths", paths);
    }

    public static String getLoginStateSnapshot() {
        return Prefers.getString("login_state_snapshot");
    }

    public static void putLoginStateSnapshot(String snapshot) {
        Prefers.put("login_state_snapshot", snapshot);
    }

    public static String getLoginStateFindings() {
        return Prefers.getString("login_state_findings");
    }

    public static void putLoginStateFindings(String findings) {
        Prefers.put("login_state_findings", findings);
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static int getLanguage() {
        int language = Prefers.getInt("language", LANGUAGE_FOLLOW_SYSTEM);
        return isLanguage(language) ? language : LANGUAGE_FOLLOW_SYSTEM;
    }

    public static void putLanguage(int language) {
        int value = isLanguage(language) ? language : LANGUAGE_FOLLOW_SYSTEM;
        Prefers.put("language", value);
        applyLanguage(value);
    }

    public static void applyLanguage() {
        applyLanguage(getLanguage());
    }

    public static int getLanguageIndex() {
        int language = getLanguage();
        for (int i = 0; i < LANGUAGE_OPTIONS.length; i++) if (LANGUAGE_OPTIONS[i] == language) return i;
        return LANGUAGE_FOLLOW_SYSTEM;
    }

    public static void putLanguageIndex(int index) {
        putLanguage(index >= 0 && index < LANGUAGE_OPTIONS.length ? LANGUAGE_OPTIONS[index] : LANGUAGE_FOLLOW_SYSTEM);
    }

    private static boolean isLanguage(int language) {
        for (int option : LANGUAGE_OPTIONS) if (option == language) return true;
        return false;
    }

    private static void applyLanguage(int language) {
        if (language == LANGUAGE_SIMPLIFIED) Trans.setTraditional(false);
        else if (language == LANGUAGE_TRADITIONAL) Trans.setTraditional(true);
        else Trans.setTraditional(null);
    }

    public static Context wrapDisplay(Context context) {
        return wrapUiScale(wrapLanguage(context));
    }

    public static Context wrapLanguage(Context context) {
        int language = getLanguage();
        applyLanguage(language);
        if (language == LANGUAGE_FOLLOW_SYSTEM) return context;
        Locale locale = language == LANGUAGE_TRADITIONAL ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(config);
    }

    public static int getUiScale() {
        int scale = Prefers.getInt("ui_scale", UI_SCALE_FOLLOW_SYSTEM);
        return isUiScale(scale) ? scale : UI_SCALE_FOLLOW_SYSTEM;
    }

    public static void putUiScale(int scale) {
        Prefers.put("ui_scale", isUiScale(scale) ? scale : UI_SCALE_FOLLOW_SYSTEM);
    }

    public static int getUiScaleIndex() {
        int scale = getUiScale();
        for (int i = 0; i < UI_SCALE_OPTIONS.length; i++) if (UI_SCALE_OPTIONS[i] == scale) return i;
        return UI_SCALE_FOLLOW_SYSTEM;
    }

    public static void putUiScaleIndex(int index) {
        putUiScale(index >= 0 && index < UI_SCALE_OPTIONS.length ? UI_SCALE_OPTIONS[index] : UI_SCALE_FOLLOW_SYSTEM);
    }

    private static boolean isUiScale(int scale) {
        for (int option : UI_SCALE_OPTIONS) if (option == scale) return true;
        return false;
    }

    public static Context wrapUiScale(Context context) {
        int scale = getUiScale();
        if (scale == UI_SCALE_FOLLOW_SYSTEM) return context;
        float factor = getUiScaleFactor(scale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int stableDensity = DisplayMetrics.DENSITY_DEVICE_STABLE > 0 ? DisplayMetrics.DENSITY_DEVICE_STABLE : metrics.densityDpi;
        int densityDpi = Math.max(DisplayMetrics.DENSITY_LOW, Math.round(stableDensity * factor));
        config.densityDpi = densityDpi;
        config.fontScale = 1.0f;
        config.screenWidthDp = pxToDp(metrics.widthPixels, densityDpi);
        config.screenHeightDp = pxToDp(metrics.heightPixels, densityDpi);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        return context.createConfigurationContext(config);
    }

    private static float getUiScaleFactor(int scale) {
        return switch (scale) {
            case UI_SCALE_STANDARD -> 0.8f;
            case UI_SCALE_MILD_COMPACT -> 0.75f;
            case UI_SCALE_COMPACT -> 0.7f;
            case UI_SCALE_MORE_COMPACT -> 0.65f;
            case UI_SCALE_SMALLER -> 0.6f;
            default -> 1.0f;
        };
    }

    private static int pxToDp(int px, int densityDpi) {
        return Math.max(1, Math.round(px * (float) DisplayMetrics.DENSITY_DEFAULT / densityDpi));
    }

    public static boolean isDriveCheck() {
        return Prefers.getBoolean("drive_check", true);
    }

    public static void putDriveCheck(boolean driveCheck) {
        Prefers.put("drive_check", driveCheck);
    }

    public static boolean isCompactEpisodeTitle() {
        return Prefers.getBoolean("compact_episode_title");
    }

    public static void putCompactEpisodeTitle(boolean compact) {
        Prefers.put("compact_episode_title", compact);
    }

    public static boolean isSiteHealthSort() {
        return Prefers.getBoolean("site_health_sort", true);
    }

    public static void putSiteHealthSort(boolean sort) {
        Prefers.put("site_health_sort", sort);
    }

    public static boolean isSiteHealthDialogSort() {
        return Prefers.getBoolean("site_health_dialog_sort");
    }

    public static void putSiteHealthDialogSort(boolean sort) {
        Prefers.put("site_health_dialog_sort", sort);
    }

    public static boolean isWebHomeExtension() {
        return Prefers.getBoolean("web_home_extension", true);
    }

    public static void putWebHomeExtension(boolean extension) {
        Prefers.put("web_home_extension", extension);
    }

    public static final String DEFAULT_WEB_HOME_URL = "";

    public static String getWebHomePage() {
        return Prefers.getString("web_home_page", DEFAULT_WEB_HOME_URL);
    }

    public static String getWebHomePageDisplay() {
        return getWebHomePage();
    }

    public static void putWebHomePage(String url) {
        Prefers.put("web_home_page", url == null ? "" : url.trim());
    }

    public static void resetWebHomePage() {
        Prefers.put("web_home_page", DEFAULT_WEB_HOME_URL);
    }

    public static boolean isWebHomeFullscreen() {
        return Prefers.getBoolean("web_home_fullscreen", true);
    }

    public static void putWebHomeFullscreen(boolean fullscreen) {
        Prefers.put("web_home_fullscreen", fullscreen);
    }

    public static boolean isPlaybackArtworkWall() {
        return Prefers.getBoolean("playback_artwork_wall", true);
    }

    public static void putPlaybackArtworkWall(boolean artworkWall) {
        Prefers.put("playback_artwork_wall", artworkWall);
    }

    public static boolean isCspWarmup() {
        return getCspWarmupMode() != CSP_WARMUP_DISABLED;
    }

    public static void putCspWarmup(boolean warmup) {
        if (warmup) {
            Prefers.put("csp_warmup", true);
            if (getCspWarmupSelectedMode() == CSP_WARMUP_DISABLED) Prefers.put("csp_warmup_mode", CSP_WARMUP_DEFAULT);
        } else {
            Prefers.put("csp_warmup", false);
        }
    }

    public static int getCspWarmupMode() {
        if (!Prefers.getBoolean("csp_warmup")) return CSP_WARMUP_DISABLED;
        return getCspWarmupSelectedMode();
    }

    public static int getCspWarmupSelectedMode() {
        int mode = Prefers.getInt("csp_warmup_mode", CSP_WARMUP_DEFAULT);
        return mode == CSP_WARMUP_CUSTOM ? CSP_WARMUP_CUSTOM : CSP_WARMUP_DEFAULT;
    }

    public static void putCspWarmupMode(int mode) {
        if (mode == CSP_WARMUP_DISABLED) {
            Prefers.put("csp_warmup", false);
        } else {
            Prefers.put("csp_warmup", true);
            Prefers.put("csp_warmup_mode", mode == CSP_WARMUP_CUSTOM ? CSP_WARMUP_CUSTOM : CSP_WARMUP_DEFAULT);
        }
    }

    public static List<String> getCspWarmupSites() {
        try {
            List<String> keys = App.gson().fromJson(Prefers.getString("csp_warmup_sites", "[]"), STRING_LIST);
            if (keys == null) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String key : keys) if (key != null && !key.trim().isEmpty() && !result.contains(key.trim())) result.add(key.trim());
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public static void putCspWarmupSites(List<String> keys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (keys != null) for (String key : keys) if (key != null && !key.trim().isEmpty()) result.add(key.trim());
        Prefers.put("csp_warmup_sites", App.gson().toJson(result));
    }

    public static int getSearchColumn() {
        return Math.min(Math.max(Prefers.getInt("search_column", 2), 1), 2);
    }

    public static void putSearchColumn(int column) {
        Prefers.put("search_column", column == 2 ? 2 : 1);
    }

    public static boolean isDebugLog() {
        return DebugLogStore.isEnabled();
    }

    public static void putDebugLog(boolean debugLog) {
        DebugLogStore.setEnabled(debugLog);
        if (debugLog) logDebugEnvironment("enable");
    }

    public static void logDebugEnvironment(String reason) {
        boolean hardwareAccelerated = (App.get().getApplicationInfo().flags & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
        DebugLogStore.event(new com.github.catvod.crawler.diagnostics.DiagnosticEvent("env.device", "none", "process", 0, 0)
                .observed("reason", reason).observed("appVersion", BuildConfig.VERSION_NAME).observed("versionCode", BuildConfig.VERSION_CODE)
                .observed("buildTime", BuildConfig.BUILD_TIME).observed("buildTag", BuildConfig.BUILD_TAG)
                .observed("gitRevision", BuildConfig.GIT_REVISION).observed("state", BuildConfig.GIT_STATE)
                .observed("fingerprintDigest", com.fongmi.android.tv.player.NativeLibraryDiagnostics.digestText(Build.FINGERPRINT))
                .observed("media3Version", BuildConfig.MEDIA3_VERSION).observed("flavor", BuildConfig.FLAVOR_mode)
                .observed("abi", BuildConfig.FLAVOR_abi).observed("process64Bit", android.os.Process.is64Bit())
                .observed("android", Build.VERSION.RELEASE).observed("api", Build.VERSION.SDK_INT)
                .observed("targetSdk", App.get().getApplicationInfo().targetSdkVersion)
                .observed("manufacturer", Build.MANUFACTURER).observed("model", Build.MODEL)
                .observed("device", Build.DEVICE).observed("hardwareAccelerated", hardwareAccelerated)
                .pin("device"));
        com.fongmi.android.tv.player.NativeLibraryDiagnostics.request();
        com.fongmi.android.tv.player.PlaybackDiagnosticSession.captureActive(android.os.SystemClock.elapsedRealtime());
        SpiderDebug.log("env", "reason=%s app=%s(%s) mode=%s abi=%s debug=%s hardware=%s android=%s sdk=%s incremental=%s manufacturer=%s brand=%s model=%s device=%s product=%s supportedAbis=%s",
                reason,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.FLAVOR_mode,
                BuildConfig.FLAVOR_abi,
                BuildConfig.DEBUG,
                hardwareAccelerated,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.VERSION.INCREMENTAL,
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                String.join(",", Build.SUPPORTED_ABIS));
        WebViewUtil.logProvider("debug-env");
    }

    public static boolean isShellProxy() {
        return Prefers.getBoolean("shell_proxy");
    }

    public static void putShellProxy(boolean shellProxy) {
        Prefers.put("shell_proxy", shellProxy);
        ProxySetting.apply();
    }

    public static String getShellProxyRules() {
        return Prefers.getString("shell_proxy_rules");
    }

    public static void putShellProxyRules(String rules) {
        Prefers.put("shell_proxy_rules", rules);
        ProxySetting.apply();
    }

    public static void putShellProxyConfig(String url, String rules) {
        Prefers.put("shell_proxy_url", url);
        Prefers.put("shell_proxy_rules", rules);
        Prefers.put("shell_proxy_hosts", "*");
        ProxySetting.apply();
    }

    public static String getShellProxyUrl() {
        return Prefers.getString("shell_proxy_url");
    }

    public static void putShellProxyUrl(String url) {
        Prefers.put("shell_proxy_url", url);
        ProxySetting.apply();
    }

    public static String getShellProxyHosts() {
        return Prefers.getString("shell_proxy_hosts", "*");
    }

    public static void putShellProxyHosts(String hosts) {
        Prefers.put("shell_proxy_hosts", hosts);
        ProxySetting.apply();
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static String getUpdateSource() {
        return UpdateSource.normalize(Prefers.getString("update_source", UpdateSource.OCI));
    }

    public static void putUpdateSource(String source) {
        Prefers.put("update_source", UpdateSource.normalize(source));
    }

    public static String getUpdateGithubProxy() {
        return GithubProxy.find(Prefers.getString("update_github_proxy", GithubProxy.DIRECT)).id;
    }

    public static void putUpdateGithubProxy(String proxy) {
        Prefers.put("update_github_proxy", GithubProxy.find(proxy).id);
    }

    public static String getUpdateGithubProxyUrl() {
        return Prefers.getString("update_github_proxy_url");
    }

    public static void putUpdateGithubProxyUrl(String url) {
        Prefers.put("update_github_proxy_url", url == null ? "" : url.trim());
    }

    public static String getUpdateGithubProxyMode() {
        return GithubProxy.normalizeMode(Prefers.getString("update_github_proxy_mode", GithubProxy.MODE_FULL_URL));
    }

    public static void putUpdateGithubProxyMode(String mode) {
        Prefers.put("update_github_proxy_mode", GithubProxy.normalizeMode(mode));
    }

    public static String getUpdateOciMirror() {
        return OciMirror.find(Prefers.getString("update_oci_mirror", OciMirror.DEFAULT)).id;
    }

    public static void putUpdateOciMirror(String mirror) {
        Prefers.put("update_oci_mirror", OciMirror.find(mirror).id);
    }

    public static String getUpdateOciMirrorUrl() {
        return Prefers.getString("update_oci_mirror_url");
    }

    public static void putUpdateOciMirrorUrl(String url) {
        Prefers.put("update_oci_mirror_url", url == null ? "" : url.trim());
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(App.get(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasFileManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + App.get().getPackageName())).resolveActivity(App.get().getPackageManager()) != null || new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(App.get().getPackageManager()) != null;
    }
}
