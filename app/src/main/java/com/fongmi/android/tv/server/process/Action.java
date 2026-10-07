package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class Action implements Process {

    private static final String APK_PART = "apk";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/action");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String param = params.get("do");
        SpiderDebug.log("action", "do=%s params=%s", param, params);
        return TextUtils.isEmpty(param) ? Nano.ok() : doJob(param, params, files);
    }

    private Response doJob(String param, Map<String, String> params, Map<String, String> files) {
        return switch (param) {
            case "file" -> {
                onFile(params);
                yield Nano.ok();
            }
            case "push" -> {
                onPush(params);
                yield Nano.ok();
            }
            case "cast" -> {
                onCast(params);
                yield Nano.ok();
            }
            case "apk" -> onApk(params, files);
            case "apk_url" -> onApkUrl(params);
            case "search" -> {
                onSearch(params);
                yield Nano.ok();
            }
            case "setting" -> {
                onSetting(params);
                yield Nano.ok();
            }
            case "refresh" -> {
                onRefresh(params);
                yield Nano.ok();
            }
            case "control" -> {
                onControl(params);
                yield Nano.ok();
            }
            case "danmaku" -> {
                onDanmaku(params);
                yield Nano.ok();
            }
            default -> Nano.ok();
        };
    }

    private void onFile(Map<String, String> params) {
        String path = params.get("path");
        if (TextUtils.isEmpty(path)) return;
        if (path.endsWith(".apk")) FileUtil.openFile(Path.local(path));
        else if (path.endsWith(".srt") || path.endsWith(".ssa") || path.endsWith(".ass")) RefreshEvent.subtitle(path);
        else ServerEvent.setting(path);
    }

    private void onPush(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return;
        ServerEvent.push(url);
    }

    private void onSearch(Map<String, String> params) {
        String word = params.get("word");
        if (TextUtils.isEmpty(word)) return;
        ServerEvent.search(word);
    }

    private void onSetting(Map<String, String> params) {
        String text = params.get("text");
        String name = params.get("name");
        if (TextUtils.isEmpty(text)) return;
        ServerEvent.setting(text, name);
    }

    private void onRefresh(Map<String, String> params) {
        String type = params.get("type");
        String path = params.get("path");
        String json = params.get("json");
        if (TextUtils.isEmpty(type)) return;
        switch (type) {
            case "home" -> RefreshEvent.home();
            case "live" -> RefreshEvent.live();
            case "detail" -> RefreshEvent.detail();
            case "player" -> RefreshEvent.player();
            case "category" -> RefreshEvent.category();
            case "danmaku" -> RefreshEvent.danmaku(path);
            case "subtitle" -> RefreshEvent.subtitle(path);
            case "vod" -> RefreshEvent.vod(Vod.objectFrom(json));
        }
    }

    private void onControl(Map<String, String> params) {
        String type = params.get("type");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(type)) return;
        switch (type) {
            case "play" -> App.post(() -> service.player().play());
            case "pause" -> App.post(() -> service.player().pause());
            case "stop" -> App.post(service::dispatchStop);
            case "prev" -> App.post(service::dispatchPrev);
            case "next" -> App.post(service::dispatchNext);
            case "repeat" -> App.post(service::dispatchRepeat);
            case "replay" -> App.post(service::dispatchReplay);
        }
    }

    private void onDanmaku(Map<String, String> params) {
        String text = params.get("text");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(text)) return;
        App.post(() -> service.player().sendDanmaku(text));
    }

    private void onCast(Map<String, String> params) {
        Config config = Config.objectFrom(params.get("config"));
        Device device = Device.objectFrom(params.get("device"));
        History history = History.objectFrom(params.get("history"));
        CastEvent.post(Config.find(config), device, history);
    }

    private Response onApk(Map<String, String> params, Map<String, String> files) {
        File target = null;
        try {
            if (files == null || !files.containsKey(APK_PART)) throw new IllegalArgumentException("Missing APK file");
            String name = sanitizeApkName(params.get("name"));
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IllegalArgumentException("Invalid APK name");
            File source = new File(files.get(APK_PART));
            long expectedSize = parseLong(params.get("size"));
            if (!source.isFile() || source.length() <= 0) throw new IllegalArgumentException("Empty APK file");
            if (expectedSize > 0 && source.length() != expectedSize) throw new IllegalArgumentException("APK size mismatch");
            String expectedSha256 = params.get("sha256");
            if (!TextUtils.isEmpty(expectedSha256) && !expectedSha256.equalsIgnoreCase(sha256(source))) throw new IllegalArgumentException("APK checksum mismatch");
            if (App.get().getPackageManager().getPackageArchiveInfo(source.getAbsolutePath(), 0) == null) throw new IllegalArgumentException("Invalid APK package");
            long available = FileUtil.getAvailableStorageSpace(Path.cache());
            if (available > 0 && source.length() + 16L * 1024 * 1024 > available) throw new IllegalStateException("Insufficient storage");
            target = Path.cache("pushed-" + System.currentTimeMillis() + ".apk");
            Path.copy(source, target);
            if (target.length() != source.length()) throw new IllegalStateException("APK copy incomplete");
            File installFile = target;
            Device senderDevice = Device.objectFrom(params.get("device"));
            String sender = senderDevice == null ? "" : senderDevice.getName();
            SpiderDebug.log("apk-push", "received name=%s size=%d sender=%s", name, target.length(), sender);
            App.post(() -> {
                try {
                    FileUtil.openFile(installFile);
                } catch (Exception e) {
                    Path.clear(installFile);
                    Notify.show(e.getMessage());
                }
            });
            Task.schedule(() -> Path.clear(installFile), 30, TimeUnit.MINUTES);
            return Nano.ok("APK received");
        } catch (Exception e) {
            if (target != null) Path.clear(target);
            SpiderDebug.log("apk-push", e);
            return Nano.error(fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    private Response onApkUrl(Map<String, String> params) {
        try {
            Device senderDevice = Device.objectFrom(params.get("device"));
            String sender = senderDevice == null ? "" : senderDevice.getName();
            ApkUrlPush.StartResult result = ApkUrlPush.get().start(params.get("url"), sender);
            if (result == ApkUrlPush.StartResult.BUSY) return Nano.error(Response.Status.CONFLICT, ResUtil.getString(R.string.apk_push_url_busy));
            return Nano.ok("APK URL accepted");
        } catch (IllegalArgumentException e) {
            return Nano.error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            SpiderDebug.log("apk-push-url", e);
            return Nano.error(ResUtil.getString(R.string.apk_push_url_schedule_failed));
        }
    }

    private String sanitizeApkName(String name) {
        if (TextUtils.isEmpty(name)) return "app.apk";
        return name.replace('\\', '_').replace('/', '_').replace('\u0000', '_').trim();
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
