package com.fongmi.android.tv.node;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Node.js 猫源 bundle 管理，按 FongMi 5.6.8 的 Node 载入契约：
 * <ul>
 *     <li>配置入口为 {@code .../index.js.md5}，同目录提供 index.js、index.config.js、index.config.js.md5；</li>
 *     <li>两份文件都按各自 .md5 增量更新，下载到 files/nodejs/（index.js 通过 require('./index.config.js') 等方式读取）；</li>
 *     <li>标准 CatPawOpen bundle 只导出 start(config)，由 NodeService 生成的 launcher.js 负责调用；</li>
 *     <li>就绪探测依次尝试 /check、/config（标准猫源）与 /t4/config（旧版 T4 适配 bundle）。</li>
 * </ul>
 * 兼容写法：{@code index.js;md5;<hash>}、以 .js 结尾的 bundle 地址（不带校验）。
 */
public class NodeBundleManager {

    private static final String TAG = "NodeBundleManager";
    /** 首选端口（兼容写死 127.0.0.1:9988 的网页）；被占用时依次尝试 9989、9990……见 {@link #choosePort} */
    public static final int DEFAULT_PORT = 9988;
    /** 上次启动 Node 所用的端口：主进程重启后 :node 进程可能还在，据此继续连接 */
    private static final String KEY_PORT = "node_bundle_port";
    private static volatile int port;

    public static final String SCRIPT_NAME = "index.js";
    public static final String CONFIG_NAME = "index.config.js";
    /** :node 进程写入的启动状态（start / listening / error / fatal / exit），主进程据此判断失败原因 */
    public static final String STATUS_NAME = "node_status.log";
    private static final String NODE_PROCESS_SUFFIX = ":node";

    /** 冷启动（加载 80MB libnode + 解析 bundle + 注册站点）在低端盒子上可能超过 15 秒 */
    public static final long DEFAULT_TIMEOUT_MS = 30000;
    /** :node 进程存活但超过这么久仍未监听端口，视为僵死 */
    private static final long STALE_MS = 60000;

    /** 上次下载 bundle 所用的地址：没有 MD5 时据此判断换源后必须重新下载 */
    private static final String KEY_SCRIPT_URL = "node_bundle_script_url";
    private static final String KEY_CONFIG_URL = "node_bundle_config_url";

    private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final String[] READY_PATHS = {"/check", "/config", "/t4/config"};

    public interface Callback {
        void onSuccess(String readyUrl);

        void onFailure(Throwable t);
    }

    /**
     * 解析后的下载计划。md5 / md5Url 二选一（都为空表示不校验）。
     */
    public static class BundleInfo {
        public final String scriptUrl;
        public final String scriptMd5;
        public final String scriptMd5Url;
        public final String configUrl;
        public final String configMd5Url;
        /** index.js.md5 入口下 index.config.js 为契约必需；其余写法下为可选 */
        public final boolean configRequired;

        BundleInfo(String scriptUrl, String scriptMd5, String scriptMd5Url, String configUrl, String configMd5Url, boolean configRequired) {
            this.scriptUrl = scriptUrl;
            this.scriptMd5 = scriptMd5;
            this.scriptMd5Url = scriptMd5Url;
            this.configUrl = configUrl;
            this.configMd5Url = configMd5Url;
            this.configRequired = configRequired;
        }
    }

    // ---- 识别 ----

    /**
     * 只把明确的 Node bundle 入口当成 Node 配置，其余地址一律按普通 JSON 配置（与 FongMi 一致）：
     * <ul>
     *     <li>路径以 index.js.md5 结尾（FongMi 标准入口，忽略大小写、query、fragment）；</li>
     *     <li>包含 {@code ;md5;}（index.js;md5;hash 兼容写法）；</li>
     *     <li>路径以 .js 结尾（直接给 bundle 地址，不带校验）。</li>
     * </ul>
     */
    public static boolean isNodeConfig(String input) {
        if (TextUtils.isEmpty(input)) return false;
        String raw = input.trim();
        if (raw.contains(";md5;")) return true;
        String path = stripQuery(raw).toLowerCase(Locale.ROOT);
        return path.endsWith("/" + SCRIPT_NAME + ".md5") || path.equals(SCRIPT_NAME + ".md5") || path.endsWith(".js");
    }

    public static BundleInfo parse(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.contains(";md5;")) {
            String[] parts = raw.split(";md5;", 2);
            String script = parts[0].trim();
            String md5 = parts.length > 1 ? parts[1].trim() : "";
            boolean md5IsUrl = md5.startsWith("http");
            String config = sibling(script, CONFIG_NAME);
            return new BundleInfo(script, md5IsUrl ? "" : md5, md5IsUrl ? md5 : "", config, withMd5(config), false);
        }
        String path = stripQuery(raw);
        if (path.toLowerCase(Locale.ROOT).endsWith(".md5")) {
            // .../index.js.md5 -> .../index.js，保留原 query / fragment（部分 CDN 需要签名参数）
            String script = path.substring(0, path.length() - 4) + raw.substring(path.length());
            String config = sibling(script, CONFIG_NAME);
            return new BundleInfo(script, "", raw, config, withMd5(config), true);
        }
        String config = sibling(raw, CONFIG_NAME);
        return new BundleInfo(raw, "", "", config, withMd5(config), false);
    }

    private static String stripQuery(String url) {
        int end = url.length();
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        if (q >= 0) end = Math.min(end, q);
        if (h >= 0) end = Math.min(end, h);
        return url.substring(0, end);
    }

    /** 同目录下的另一个文件，沿用原地址的 query / fragment */
    private static String sibling(String url, String name) {
        String path = stripQuery(url);
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "";
        return path.substring(0, slash + 1) + name + url.substring(path.length());
    }

    private static String withMd5(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String path = stripQuery(url);
        return path + ".md5" + url.substring(path.length());
    }

    // ---- 文件位置 ----

    public static File getNodeDir(Context context) {
        File dir = new File(context.getFilesDir(), "nodejs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getDataDir(Context context) {
        File dir = new File(getNodeDir(context), "data");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getScriptFile(Context context) {
        return new File(getNodeDir(context), SCRIPT_NAME);
    }

    public static File getConfigFile(Context context) {
        return new File(getNodeDir(context), CONFIG_NAME);
    }

    public static File getStatusFile(Context context) {
        return new File(getNodeDir(context), STATUS_NAME);
    }

    // ---- 启动状态（:node 进程写，主进程读） ----

    /** 写一行状态：start &lt;毫秒&gt; / listening &lt;端口&gt; / error &lt;信息&gt; / fatal &lt;信息&gt; / exit &lt;退出码&gt; */
    static void writeStatus(File file, String line, boolean append) {
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            fos.write((line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static final class Status {
        long startAt;
        boolean failed;
        String detail = "";
    }

    private static Status readStatus(Context context) {
        Status status = new Status();
        File file = getStatusFile(context);
        if (!file.exists()) return status;
        StringBuilder detail = new StringBuilder();
        for (String line : Path.read(file).split("\n")) {
            line = line.trim();
            if (line.startsWith("start ")) {
                try {
                    status.startAt = Long.parseLong(line.substring(6).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("fatal ") || line.startsWith("exit ")) {
                status.failed = true;
                detail.append(line).append('\n');
            } else if (line.startsWith("error ")) {
                detail.append(line).append('\n');
            }
        }
        String text = detail.toString().trim();
        status.detail = text.length() > 800 ? "…" + text.substring(text.length() - 800) : text;
        return status;
    }

    /** :node 进程存活但不提供服务：已报告失败、没有启动记录，或启动已超过 {@link #STALE_MS} */
    private static boolean isStale(Context context) {
        Status status = readStatus(context);
        if (status.failed || status.startAt <= 0) return true;
        return System.currentTimeMillis() - status.startAt > STALE_MS;
    }

    // ---- :node 进程 ----

    public static boolean isNodeProcess(Context context) {
        return currentProcessName().equals(context.getApplicationInfo().processName + NODE_PROCESS_SUFFIX);
    }

    private static boolean isMainProcess(Context context) {
        return currentProcessName().equals(context.getApplicationInfo().processName);
    }

    private static String currentProcessName() {
        String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : Path.read(new File("/proc/self/cmdline"));
        if (name == null) return "";
        int nul = name.indexOf('\0');
        if (nul >= 0) name = name.substring(0, nul);
        return name.trim();
    }

    private static int findNodePid(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am == null ? null : am.getRunningAppProcesses();
            if (list == null) return 0;
            String name = context.getApplicationInfo().processName + NODE_PROCESS_SUFFIX;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info.uid == Process.myUid() && name.equals(info.processName)) return info.pid;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query running processes: " + t.getMessage());
        }
        return 0;
    }

    /**
     * V8 不能在同一进程内重新初始化：直接结束 :node 进程（同 UID 允许），等进程消失、端口释放后再启动。
     * 找不到进程但端口在监听时退回 NodeService.stop。
     */
    private static void killNodeProcess(Context context) {
        // 没有自己的 :node 进程就不用管端口：端口上即使有应答也是其它应用的服务
        int pid = findNodePid(context);
        if (pid <= 0) return;
        Log.i(TAG, "Killing :node process pid=" + pid);
        Process.killProcess(pid);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            // 进程结束后内核会关闭它的监听 socket
            if (findNodePid(context) == 0) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "Node process did not stop within 5s, starting anyway.");
    }

    // ---- 运行状态 ----

    public static boolean isServiceRunning() {
        return probeReady(200, 300) != null;
    }

    /**
     * @return 第一个返回 2xx 的就绪地址；都不可用时返回 null
     */
    private static String probeReady(long connectMs, long readMs) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .build();
        String base = baseUrl();
        for (String path : READY_PATHS) {
            String url = base + path;
            try (Response resp = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
                if (resp.isSuccessful()) return url;
            } catch (Throwable e) {
                // 连接被拒绝说明端口没有监听，没必要再试其余路径
                if (e instanceof java.net.ConnectException) return null;
            }
        }
        return null;
    }

    public static void startIfPresent(Context context) {
        try {
            // 端口只由主进程决定并缓存，其它进程（如 :error_activity）不要自启动，避免各进程端口记录不一致
            if (!isMainProcess(context)) return;
            File scriptFile = getScriptFile(context);
            if (!scriptFile.exists() || scriptFile.length() <= 0) return;
            // :node 进程还在时它监听的就是已记录的端口（重复的启动请求会被 NodeService 忽略）；否则重新选空闲端口
            if (findNodePid(context) <= 0) {
                getStatusFile(context).delete();
                int next = choosePort(DEFAULT_PORT);
                if (next <= 0) return;
                setPort(next);
            }
            Log.i(TAG, "Auto-starting Node service with existing bundle on port " + getPort());
            NodeService.start(context, scriptFile.getAbsolutePath(), getDataDir(context).getAbsolutePath(), getPort());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to auto-start Node service: ", t);
        }
    }

    // ---- 端口 ----

    /** Node 服务当前（或下次启动）监听的端口 */
    public static int getPort() {
        int value = port;
        if (value <= 0) port = value = Prefers.getInt(KEY_PORT, DEFAULT_PORT);
        return value;
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    private static void setPort(int value) {
        port = value;
        Prefers.put(KEY_PORT, value);
    }

    /** 把网页地址里写死的 127.0.0.1:9988 / localhost:9988 换成实际端口 */
    public static String localUrl(String url) {
        int current = getPort();
        if (TextUtils.isEmpty(url) || current == DEFAULT_PORT) return url;
        return url.replace("127.0.0.1:" + DEFAULT_PORT, "127.0.0.1:" + current).replace("localhost:" + DEFAULT_PORT, "localhost:" + current);
    }

    /** 127.0.0.1 上的端口能否绑定（与 libuv 一样开 SO_REUSEADDR，TIME_WAIT 不算占用） */
    private static boolean isPortFree(int value) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), value), 1);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 为全新启动的 Node 选端口。localhost 端口是全设备共享的，其它影视类应用的 Node 服务也常用 9988：
     * 从 {@code from} 开始（首次为 9988）逐个往上找第一个能绑定的端口：9988、9989、9990……
     *
     * @return 空闲端口；直到 65535 都被占用时返回 -1
     */
    private static int choosePort(int from) {
        for (int p = Math.max(from, DEFAULT_PORT); p <= 65535; p++) {
            if (isPortFree(p)) return p;
        }
        return -1;
    }

    /** 端口被其它程序占用（Node 报 EADDRINUSE） */
    private static final class PortInUseException extends Exception {
        PortInUseException(String message) {
            super(message);
        }
    }

    // ---- 准备与启动 ----

    /**
     * 增量下载 index.js / index.config.js，启动（或在文件更新后重启）Node 服务并等待就绪。
     *
     * @return 就绪探测成功的地址
     */
    public static String prepareAndStartSync(Context context, String input, long timeoutMs) throws Exception {
        BundleInfo info = parse(input);
        if (TextUtils.isEmpty(info.scriptUrl)) throw new Exception("Unable to resolve JS bundle URL from input: " + input);

        File scriptFile = getScriptFile(context);
        File configFile = getConfigFile(context);
        boolean changed = ensureFile(info.scriptUrl, info.scriptMd5, info.scriptMd5Url, scriptFile, KEY_SCRIPT_URL, true);
        changed |= ensureConfig(info, configFile);

        // 只有自己的 :node 进程存活时，端口上的服务才算我们的；没有进程却有应答说明端口被其它应用占用
        boolean alive = findNodePid(context) > 0;
        boolean ready = alive && isServiceRunning();
        // 进程还活着时 NodeService 会忽略新的启动请求（isRunning），所以以下两种情况必须先结束旧进程：
        // 1. bundle 已更新（无论旧进程是否已监听端口）；
        // 2. 进程存活却没在提供服务（旧 bundle 启动失败被 launcher 吞掉异常、或卡死）。
        if (alive && (changed || (!ready && isStale(context)))) {
            Log.i(TAG, changed ? "Bundle updated, restarting Node service..." : "Node process alive but not serving, restarting Node service...");
            killNodeProcess(context);
            alive = false;
        }

        // 9988 起逐个往上试，直到 Node 在某个端口上启动成功；只有端口被占用才换下一个，其它失败照常报错
        int from = DEFAULT_PORT;
        while (true) {
            if (!alive) {
                // 全新启动：清掉旧状态（避免把上一次的失败当成本次结果），并选下一个空闲端口
                getStatusFile(context).delete();
                int next = choosePort(from);
                if (next <= 0) throw new Exception("Node 猫源启动失败：" + from + " 之后没有可用的本地端口");
                setPort(next);
            }
            Log.i(TAG, "Starting Node.js service on port " + getPort() + "...");
            NodeService.start(context, scriptFile.getAbsolutePath(), getDataDir(context).getAbsolutePath(), getPort());
            try {
                return waitForReadySync(context, timeoutMs);
            } catch (PortInUseException e) {
                // 检测空闲之后、Node 监听之前被其它程序抢占：从下一个端口继续
                Log.w(TAG, e.getMessage() + ", retrying with port " + (getPort() + 1));
                killNodeProcess(context);
                alive = false;
                from = getPort() + 1;
            }
        }
    }

    private static boolean ensureConfig(BundleInfo info, File configFile) throws Exception {
        if (TextUtils.isEmpty(info.configUrl)) return false;
        try {
            return ensureFile(info.configUrl, "", info.configMd5Url, configFile, KEY_CONFIG_URL, info.configRequired);
        } catch (Exception e) {
            if (info.configRequired && !configFile.exists()) throw e;
            // 可选配置（;md5; / 直接 .js 写法）或已有本地副本：继续使用本地 / 空配置
            Log.w(TAG, "index.config.js unavailable, continue with " + (configFile.exists() ? "local copy" : "empty config") + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 按 MD5（直接给出或从 md5Url 获取）判断是否需要下载；没有 MD5 时，地址变化或本地缺失才下载。
     *
     * @param strict 下载失败时是否抛出（false 时只在没有本地文件时抛出）
     * @return 本地文件是否被替换
     */
    private static boolean ensureFile(String url, String md5, String md5Url, File dest, String urlKey, boolean strict) throws Exception {
        String expected = md5;
        if (TextUtils.isEmpty(expected) && !TextUtils.isEmpty(md5Url)) {
            expected = fetchRemoteMd5(md5Url);
            if (TextUtils.isEmpty(expected)) Log.w(TAG, "Failed to fetch remote MD5 from " + md5Url + ", fallback to local copy if available.");
        }
        boolean exists = dest.exists() && dest.length() > 0;
        boolean sameUrl = url.equals(Prefers.getString(urlKey));
        if (exists) {
            if (!TextUtils.isEmpty(expected)) {
                String local = Util.md5(dest);
                if (expected.equalsIgnoreCase(local)) {
                    Prefers.put(urlKey, url);
                    return false;
                }
                Log.i(TAG, dest.getName() + " MD5 " + local + " != expected " + expected + ", updating.");
            } else if (sameUrl) {
                Log.i(TAG, dest.getName() + " exists and no MD5 available, using local copy.");
                return false;
            }
        }
        try {
            Log.i(TAG, "Downloading " + dest.getName() + " from: " + url);
            download(url, expected, dest);
            Prefers.put(urlKey, url);
            return true;
        } catch (Exception e) {
            if (strict || !exists) throw e;
            Log.w(TAG, "Download " + dest.getName() + " failed, keep local copy: " + e.getMessage());
            return false;
        }
    }

    public static String fetchRemoteMd5(String md5Url) {
        try {
            Request request = new Request.Builder().url(md5Url).header("User-Agent", "Mozilla/5.0").build();
            try (Response response = OkHttp.client().newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    Matcher matcher = MD5.matcher(response.body().string());
                    if (matcher.find()) return matcher.group().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to fetch remote md5 from: " + md5Url, t);
        }
        return "";
    }

    private static void download(String url, String expectedMd5, File destFile) throws Exception {
        File tempFile = new File(destFile.getParentFile(), destFile.getName() + ".download");
        if (tempFile.exists()) tempFile.delete();
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP download failed with code: " + response.code() + " url=" + url);
            ResponseBody body = response.body();
            if (body == null) throw new Exception("Empty response body downloading " + url);
            try (InputStream is = body.byteStream(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                fos.flush();
            }
        }
        if (!TextUtils.isEmpty(expectedMd5)) {
            String actualMd5 = Util.md5(tempFile);
            if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                tempFile.delete();
                throw new Exception("MD5 mismatch for " + destFile.getName() + "! Expected: " + expectedMd5 + ", Actual: " + actualMd5);
            }
        }
        if (destFile.exists()) destFile.delete();
        if (!tempFile.renameTo(destFile)) throw new Exception("Failed to rename downloaded file to: " + destFile.getAbsolutePath());
        Log.i(TAG, destFile.getName() + " downloaded successfully.");
    }

    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void prepareAndStart(Context context, String input, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                String readyUrl = prepareAndStartSync(context, input, DEFAULT_TIMEOUT_MS);
                postSuccess(callback, readyUrl);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to prepare and start Node bundle: ", t);
                postFailure(callback, t);
            }
        });
    }

    private static String waitForReadySync(Context context, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        long portBusyAt = 0;
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            String ready = probeReady(500, 1000);
            if (ready != null) {
                Log.i(TAG, "Node service is ready at: " + ready);
                return ready;
            }
            Status status = readStatus(context);
            boolean portBusy = status.detail.contains("EADDRINUSE") && status.detail.contains(":" + getPort());
            if (status.failed) {
                // bundle 启动失败或 Node 已退出：不必等满超时；结束残留进程，下次加载拿到全新进程
                killNodeProcess(context);
                if (portBusy) throw new PortInUseException("Node 猫源启动失败：本地端口 " + getPort() + " 已被其它应用占用");
                throw new Exception("Node 猫源启动失败" + (status.detail.isEmpty() ? "" : "：\n" + status.detail));
            }
            if (portBusy) {
                // 监听失败但进程没退出（错误被 launcher 兜底）；留 3 秒给可能的重复监听竞争，仍不可用就换端口
                long now = System.currentTimeMillis();
                if (portBusyAt == 0) portBusyAt = now;
                else if (now - portBusyAt > 3000) throw new PortInUseException("Node 猫源启动失败：本地端口 " + getPort() + " 已被其它应用占用");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Status status = readStatus(context);
        String reason = status.startAt <= 0 ? "Node 进程未启动" : "本地服务 " + baseUrl() + " 未就绪";
        throw new Exception("Node 猫源启动超时（" + timeoutMs / 1000 + " 秒，" + reason + "）" + (status.detail.isEmpty() ? "" : "：\n" + status.detail));
    }

    private static void postSuccess(Callback callback, String url) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(url));
    }

    private static void postFailure(Callback callback, Throwable t) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(t));
    }
}
