package com.fongmi.android.tv.node;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;

public class NodeService extends Service {

    private static final String TAG = "NodeService";

    public static final String ACTION_START = "com.fongmi.android.tv.node.START";
    public static final String ACTION_STOP = "com.fongmi.android.tv.node.STOP";

    public static final String EXTRA_SCRIPT_PATH = "script_path";
    public static final String EXTRA_DATA_DIR = "data_dir";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_DART_PORT = "dart_port";

    private Thread nodeThread;
    private volatile boolean isRunning = false;

    public static void start(Context context, String scriptPath, String dataDir, int port) {
        start(context, scriptPath, dataDir, port, com.github.catvod.Proxy.getPort());
    }

    public static void start(Context context, String scriptPath, String dataDir, int port, int dartPort) {
        Intent intent = new Intent(context, NodeService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SCRIPT_PATH, scriptPath);
        intent.putExtra(EXTRA_DATA_DIR, dataDir);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_DART_PORT, dartPort > 0 ? dartPort : com.github.catvod.Proxy.getPort());
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, NodeService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stopping NodeService and terminating node process...");
            stopNodeProcess();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String scriptPath = intent.getStringExtra(EXTRA_SCRIPT_PATH);
            String dataDir = intent.getStringExtra(EXTRA_DATA_DIR);
            int port = intent.getIntExtra(EXTRA_PORT, 9988);
            int dartPort = intent.getIntExtra(EXTRA_DART_PORT, com.github.catvod.Proxy.getPort());

            startNode(scriptPath, dataDir, port, dartPort);
        }

        return START_NOT_STICKY;
    }

    private synchronized void startNode(String scriptPath, String dataDir, int port, int dartPort) {
        if (isRunning) {
            Log.w(TAG, "Node is already running in this process.");
            return;
        }

        if (scriptPath == null || !new File(scriptPath).exists()) {
            Log.e(TAG, "Node script does not exist: " + scriptPath);
            return;
        }

        isRunning = true;
        File statusFile = NodeBundleManager.getStatusFile(this);
        NodeBundleManager.writeStatus(statusFile, "start " + System.currentTimeMillis(), false);
        nodeThread = new Thread(() -> {
            int exitCode = Integer.MIN_VALUE;
            try {
                Os.setenv("CAT_NODE_STATUS", statusFile.getAbsolutePath(), true);
                if (dataDir != null) {
                    File dataFolder = new File(dataDir);
                    if (!dataFolder.exists()) dataFolder.mkdirs();
                    Os.setenv("NODE_PATH", dataFolder.getAbsolutePath(), true);
                }
                Os.setenv("PORT", String.valueOf(port), true);
                Os.setenv("DEV_HTTP_PORT", String.valueOf(port), true);
                // 监听所有网卡：局域网内的手机/电脑可直接打开 Node 网页后台（http://设备IP:端口/website）
                // 注意：网页后台无鉴权，同一局域网内任何设备都能访问并修改网盘 Cookie
                Os.setenv("HOST", "0.0.0.0", true);
                Os.setenv("DART_PORT", String.valueOf(dartPort), true);
                Os.setenv("CAT_DART_PORT", String.valueOf(dartPort), true);
                Os.setenv("CAT_TARGET_SCRIPT", scriptPath, true);

                File scriptFile = new File(scriptPath);
                File launcherFile = ensureLauncherScript(scriptFile, dartPort);

                Log.i(TAG, "Loading native Node libraries...");
                NodeRunner.loadLibraries();

                String[] args;
                if (launcherFile != null && launcherFile.exists()) {
                    Log.i(TAG, "Launching Node runtime with launcher preload: " + launcherFile.getAbsolutePath() + " target: " + scriptPath);
                    args = new String[]{"node", "-r", launcherFile.getAbsolutePath(), scriptPath};
                } else {
                    Log.i(TAG, "Launching Node runtime with script: " + scriptPath);
                    args = new String[]{"node", scriptPath};
                }

                exitCode = NodeRunner.startNodeWithArguments(args);
                Log.i(TAG, "Node engine finished execution with code: " + exitCode);
            } catch (Throwable t) {
                Log.e(TAG, "Fatal error executing Node: ", t);
                NodeBundleManager.writeStatus(statusFile, "fatal " + t, true);
            } finally {
                isRunning = false;
                if (exitCode != Integer.MIN_VALUE) NodeBundleManager.writeStatus(statusFile, "exit code=" + exitCode, true);
                // V8 不能在同一进程内重新初始化：Node 结束后退出 :node 进程，下次启动拿到全新进程，
                // 否则后续 ACTION_START 会在同一进程里再次调用 node::Start
                stopSelf();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "NodeEngineThread");

        nodeThread.start();
    }

    /**
     * 生成 launcher.js（以 -r 预加载），按 CatPawOpen / FongMi 宿主约定运行 bundle：
     * <ul>
     *     <li>提供 globalThis.catServerFactory（http.createServer）与 catDartServerPort（宿主 /msg 端口）；</li>
     *     <li>require 目标 index.js：若导出 start(config)（标准猫源），读取同目录 index.config.js 并调用 start；</li>
     *     <li>bundle 自启动（旧版 T4 适配 bundle，无 start 导出或已监听端口）时不重复调用 start；</li>
     *     <li>兜底 unhandledRejection，避免端口占用等异步异常直接结束 Node 进程。</li>
     * </ul>
     */
    private File ensureLauncherScript(File scriptFile, int dartPort) {
        try {
            File launcherFile = new File(scriptFile.getParentFile(), "launcher.js");
            String code = "const http = require('http');\n"
                    + "const net = require('net');\n"
                    + "const path = require('path');\n"
                    + "const fs = require('fs');\n"
                    + "const dartPort = parseInt(process.env.DART_PORT || process.env.CAT_DART_PORT || '" + dartPort + "', 10);\n"
                    + "const httpPort = parseInt(process.env.DEV_HTTP_PORT || process.env.PORT || '9988', 10);\n"
                    + "function define(name, value) {\n"
                    + "    try {\n"
                    + "        Object.defineProperty(globalThis, name, { value, writable: true, configurable: true, enumerable: true });\n"
                    + "    } catch (e) {\n"
                    + "        globalThis[name] = value;\n"
                    + "    }\n"
                    + "}\n"
                    + "// 启动阶段的错误写入状态文件，主进程据此提示失败原因（端口监听后不再记录普通错误）\n"
                    + "const statusFile = process.env.CAT_NODE_STATUS || '';\n"
                    + "let listening = false;\n"
                    + "let reported = 0;\n"
                    + "function report(kind, e) {\n"
                    + "    if (!statusFile || reported >= 20 || (listening && kind !== 'fatal')) return;\n"
                    + "    reported++;\n"
                    + "    try {\n"
                    + "        const msg = String((e && e.stack) || e).split('\\n').slice(0, 4).map((s) => s.trim()).join(' | ').slice(0, 600);\n"
                    + "        fs.appendFileSync(statusFile, kind + ' ' + msg + '\\n');\n"
                    + "    } catch (_) {}\n"
                    + "}\n"
                    + "define('catDartServerPort', () => dartPort);\n"
                    + "if (typeof globalThis.catServerFactory !== 'function') {\n"
                    + "    define('catServerFactory', (handle) => {\n"
                    + "        const server = http.createServer((req, res) => handle(req, res));\n"
                    + "        server.on('listening', () => {\n"
                    + "            listening = true;\n"
                    + "            console.log('[Launcher] Run on ' + server.address().port);\n"
                    + "            try { if (statusFile) fs.appendFileSync(statusFile, 'listening ' + server.address().port + '\\n'); } catch (_) {}\n"
                    + "        });\n"
                    + "        server.on('error', (e) => { console.error('[Launcher] server error:', e); report('error', e); });\n"
                    + "        server.on('close', () => console.log('[Launcher] Close'));\n"
                    + "        return server;\n"
                    + "    });\n"
                    + "}\n"
                    + "process.on('unhandledRejection', (e) => { console.error('[Launcher] unhandledRejection:', e); report('error', e); });\n"
                    + "process.on('uncaughtException', (e) => { console.error('[Launcher] uncaughtException:', e); report('error', e); });\n"
                    + "function portInUse() {\n"
                    + "    return new Promise((resolve) => {\n"
                    + "        const socket = net.connect({ host: '127.0.0.1', port: httpPort });\n"
                    + "        socket.setTimeout(300);\n"
                    + "        socket.once('connect', () => { socket.destroy(); resolve(true); });\n"
                    + "        socket.once('timeout', () => { socket.destroy(); resolve(false); });\n"
                    + "        socket.once('error', () => resolve(false));\n"
                    + "    });\n"
                    + "}\n"
                    + "function loadConfig(dir) {\n"
                    + "    const file = path.join(dir, 'index.config.js');\n"
                    + "    if (!fs.existsSync(file)) {\n"
                    + "        console.warn('[Launcher] index.config.js not found, start with empty config');\n"
                    + "        return {};\n"
                    + "    }\n"
                    + "    try {\n"
                    + "        const mod = require(file);\n"
                    + "        if (!mod) return {};\n"
                    + "        return mod.default || mod;\n"
                    + "    } catch (e) {\n"
                    + "        console.error('[Launcher] index.config.js load failed:', e);\n"
                    + "        report('error', 'index.config.js load failed: ' + ((e && e.stack) || e));\n"
                    + "        return {};\n"
                    + "    }\n"
                    + "}\n"
                    + "async function boot(mod, target) {\n"
                    + "    const start = mod && (typeof mod.start === 'function' ? mod.start : (mod.default && typeof mod.default.start === 'function' ? mod.default.start : null));\n"
                    + "    if (!start) return;\n"
                    + "    // 给自启动的 bundle 一点时间监听端口；已在监听则不再调用 start，避免重复监听\n"
                    + "    await new Promise((r) => setTimeout(r, 300));\n"
                    + "    if (await portInUse()) {\n"
                    + "        console.log('[Launcher] port ' + httpPort + ' already in use, skip start(config)');\n"
                    + "        return;\n"
                    + "    }\n"
                    + "    const config = loadConfig(path.dirname(target));\n"
                    + "    console.log('[Launcher] start(config) keys=' + Object.keys(config || {}).join(','));\n"
                    + "    await start(config);\n"
                    + "}\n"
                    + "const target = process.env.CAT_TARGET_SCRIPT;\n"
                    + "const fail = (e) => { console.error('[Launcher] start failed:', e); report('fatal', e); };\n"
                    + "if (target && target !== __filename) {\n"
                    + "    if (require.main === module) {\n"
                    + "        // launcher 作为主脚本运行：自己加载目标 bundle\n"
                    + "        process.argv[1] = target;\n"
                    + "        try {\n"
                    + "            boot(require(target), target).catch(fail);\n"
                    + "        } catch (e) {\n"
                    + "            if (e && e.code === 'ERR_REQUIRE_ESM') {\n"
                    + "                import('file://' + target).then((m) => boot(m, target)).catch(fail);\n"
                    + "            } else {\n"
                    + "                console.error('[Launcher] Script load failed:', e);\n"
                    + "                report('fatal', e);\n"
                    + "            }\n"
                    + "        }\n"
                    + "    } else {\n"
                    + "        // -r 预加载：node 随后把目标 bundle 作为主模块执行，执行完后取其导出调用 start(config)\n"
                    + "        setImmediate(() => {\n"
                    + "            try {\n"
                    + "                const cached = require.cache[require.resolve(target)];\n"
                    + "                if (cached) boot(cached.exports, target).catch(fail);\n"
                    + "                else import('file://' + target).then((m) => boot(m, target)).catch(fail);\n"
                    + "            } catch (e) {\n"
                    + "                fail(e);\n"
                    + "            }\n"
                    + "        });\n"
                    + "    }\n"
                    + "}\n";
            com.github.catvod.utils.Path.write(launcherFile, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return launcherFile;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to create launcher.js: ", t);
            return null;
        }
    }

    private void stopNodeProcess() {
        isRunning = false;
        stopSelf();
        // Since V8 isolate cannot be re-initialized in the same process,
        // cleanly exit this dedicated :node process so next start has a fresh process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "NodeService destroyed.");
    }
}
