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
        nodeThread = new Thread(() -> {
            try {
                if (dataDir != null) {
                    File dataFolder = new File(dataDir);
                    if (!dataFolder.exists()) dataFolder.mkdirs();
                    Os.setenv("NODE_PATH", dataFolder.getAbsolutePath(), true);
                }
                Os.setenv("PORT", String.valueOf(port), true);
                Os.setenv("DEV_HTTP_PORT", String.valueOf(port), true);
                Os.setenv("HOST", "127.0.0.1", true);
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

                int exitCode = NodeRunner.startNodeWithArguments(args);
                Log.i(TAG, "Node engine finished execution with code: " + exitCode);
            } catch (Throwable t) {
                Log.e(TAG, "Fatal error executing Node: ", t);
            } finally {
                isRunning = false;
            }
        }, "NodeEngineThread");

        nodeThread.start();
    }

    private File ensureLauncherScript(File scriptFile, int dartPort) {
        try {
            File launcherFile = new File(scriptFile.getParentFile(), "launcher.js");
            String code = "const dartPort = parseInt(process.env.DART_PORT || process.env.CAT_DART_PORT || '" + dartPort + "', 10);\n"
                    + "try {\n"
                    + "    Object.defineProperty(globalThis, 'catDartServerPort', {\n"
                    + "        value: () => dartPort,\n"
                    + "        writable: true,\n"
                    + "        configurable: true,\n"
                    + "        enumerable: true\n"
                    + "    });\n"
                    + "} catch (e) {\n"
                    + "    globalThis.catDartServerPort = () => dartPort;\n"
                    + "}\n"
                    + "const target = process.env.CAT_TARGET_SCRIPT;\n"
                    + "if (target && target !== __filename && require.main === module) {\n"
                    + "    process.argv[1] = target;\n"
                    + "    try {\n"
                    + "        require(target);\n"
                    + "    } catch (e) {\n"
                    + "        if (e && e.code === 'ERR_REQUIRE_ESM') {\n"
                    + "            import('file://' + target).catch((err) => console.error('[Launcher] ESM load failed:', err));\n"
                    + "        } else {\n"
                    + "            console.error('[Launcher] Script load failed:', e);\n"
                    + "        }\n"
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
