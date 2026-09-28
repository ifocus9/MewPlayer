package com.fongmi.android.tv.node;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NodeBundleManager {

    private static final String TAG = "NodeBundleManager";
    public static final int DEFAULT_PORT = 9988;

    public interface Callback {
        void onSuccess(String t4ConfigUrl);
        void onFailure(Throwable t);
    }

    public static class BundleInfo {
        public final String url;
        public final String md5;
        public final String md5Url;

        public BundleInfo(String url, String md5, String md5Url) {
            this.url = url;
            this.md5 = md5;
            this.md5Url = md5Url;
        }

        public BundleInfo(String url, String md5) {
            this(url, md5, null);
        }
    }

    public static boolean isNodeConfig(String input) {
        if (TextUtils.isEmpty(input)) return false;
        String trimmed = input.trim();
        return trimmed.endsWith(".md5") ||
                trimmed.contains(".md5?") ||
                trimmed.contains(".md5#") ||
                trimmed.contains(";md5;") ||
                trimmed.endsWith(".js") ||
                trimmed.contains(".js?") ||
                trimmed.contains("/catpaw/") ||
                !trimmed.endsWith(".json");
    }

    public static String fetchRemoteMd5(String md5Url) {
        try {
            Request request = new Request.Builder()
                    .url(md5Url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = OkHttp.client().newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String content = response.body().string().trim();
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[0-9a-fA-F]{32}").matcher(content);
                    if (matcher.find()) {
                        return matcher.group().toLowerCase();
                    }
                    return content.replace("\"", "").replace("'", "").trim().toLowerCase();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to fetch remote md5 from: " + md5Url, t);
        }
        return "";
    }

    public static BundleInfo parse(String input) {
        if (TextUtils.isEmpty(input)) return new BundleInfo("", "");
        String raw = input.trim();
        if (raw.contains(".md5")) {
            String bundleUrl = raw.replaceAll("(?i)\\.md5(?=$|[?#])", "");
            return new BundleInfo(bundleUrl, "", raw);
        }
        if (raw.contains(";md5;")) {
            String[] parts = raw.split(";md5;");
            return new BundleInfo(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
        }
        if (raw.contains(";")) {
            String[] parts = raw.split(";");
            return new BundleInfo(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
        }
        return new BundleInfo(raw, "");
    }

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
        return new File(getNodeDir(context), "index.js");
    }

    public static boolean isServiceRunning() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(200, TimeUnit.MILLISECONDS)
                    .readTimeout(300, TimeUnit.MILLISECONDS)
                    .build();
            Request req = new Request.Builder().url("http://127.0.0.1:" + DEFAULT_PORT + "/t4/config").get().build();
            try (Response resp = client.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void startIfPresent(Context context) {
        try {
            File scriptFile = getScriptFile(context);
            if (scriptFile.exists() && scriptFile.length() > 0) {
                File dataDir = getDataDir(context);
                Log.i(TAG, "Auto-starting Node service with existing bundle...");
                NodeService.start(context, scriptFile.getAbsolutePath(), dataDir.getAbsolutePath(), DEFAULT_PORT);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to auto-start Node service: ", t);
        }
    }

    public static String prepareAndStartSync(Context context, String input, long timeoutMs) throws Exception {
        BundleInfo info = parse(input);
        String bundleUrl = info.url;
        String expectedMd5 = info.md5;

        if (!TextUtils.isEmpty(info.md5Url)) {
            Log.i(TAG, "Fetching remote MD5 from: " + info.md5Url);
            String remoteMd5 = fetchRemoteMd5(info.md5Url);
            if (!TextUtils.isEmpty(remoteMd5)) {
                expectedMd5 = remoteMd5;
                Log.i(TAG, "Successfully fetched remote MD5: " + expectedMd5);
            } else {
                Log.w(TAG, "Failed to parse remote MD5 from " + info.md5Url + ", will fallback to local if available.");
            }
        }

        File scriptFile = getScriptFile(context);
        File dataDir = getDataDir(context);

        boolean needDownload = true;
        if (scriptFile.exists() && scriptFile.length() > 0) {
            if (TextUtils.isEmpty(expectedMd5)) {
                Log.i(TAG, "Local index.js exists and no expected MD5 provided, using local bundle.");
                needDownload = false;
            } else {
                String localMd5 = Util.md5(scriptFile);
                if (expectedMd5.equalsIgnoreCase(localMd5)) {
                    Log.i(TAG, "Local index.js matches expected MD5: " + localMd5 + ", skipping download.");
                    needDownload = false;
                } else {
                    Log.i(TAG, "Local index.js MD5 (" + localMd5 + ") != expected (" + expectedMd5 + "), will update.");
                }
            }
        }

        if (needDownload) {
            if (TextUtils.isEmpty(bundleUrl)) {
                throw new Exception("Unable to resolve JS bundle URL from input: " + input);
            }
            Log.i(TAG, "Downloading bundle from: " + bundleUrl);
            downloadBundle(bundleUrl, expectedMd5, scriptFile);
        }

        Log.i(TAG, "Starting Node.js service...");
        NodeService.start(context, scriptFile.getAbsolutePath(), dataDir.getAbsolutePath(), DEFAULT_PORT);

        return waitForReadySync(DEFAULT_PORT, timeoutMs);
    }

    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void prepareAndStart(Context context, String input, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                String readyUrl = prepareAndStartSync(context, input, 15000);
                postSuccess(callback, readyUrl);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to prepare and start Node bundle: ", t);
                postFailure(callback, t);
            }
        });
    }

    private static String waitForReadySync(int port, long timeoutMs) throws Exception {
        String testUrl = "http://127.0.0.1:" + port + "/t4/config";
        long startTime = System.currentTimeMillis();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(1000, TimeUnit.MILLISECONDS)
                .build();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Request req = new Request.Builder().url(testUrl).get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        Log.i(TAG, "Node service is ready at: " + testUrl);
                        return testUrl;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        throw new Exception("Node service startup timed out after " + timeoutMs + "ms");
    }

    private static void downloadBundle(String url, String expectedMd5, File destFile) throws Exception {
        Request request = new Request.Builder().url(url).build();
        File tempFile = new File(destFile.getParentFile(), destFile.getName() + ".download");
        if (tempFile.exists()) tempFile.delete();

        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP download failed with code: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new Exception("Empty response body downloading bundle");

            try (InputStream is = body.byteStream(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
            }
        }

        if (!TextUtils.isEmpty(expectedMd5)) {
            String actualMd5 = Util.md5(tempFile);
            if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                tempFile.delete();
                throw new Exception("MD5 mismatch! Expected: " + expectedMd5 + ", Actual: " + actualMd5);
            }
        }

        if (destFile.exists()) destFile.delete();
        if (!tempFile.renameTo(destFile)) {
            throw new Exception("Failed to rename downloaded bundle to: " + destFile.getAbsolutePath());
        }
        Log.i(TAG, "Bundle downloaded successfully to: " + destFile.getAbsolutePath());
    }

    private static void waitForReady(int port, int timeoutMs, Callback callback) {
        String testUrl = "http://127.0.0.1:" + port + "/t4/config";
        long startTime = System.currentTimeMillis();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(1000, TimeUnit.MILLISECONDS)
                .build();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Request req = new Request.Builder().url(testUrl).get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        Log.i(TAG, "Node service is ready at: " + testUrl);
                        postSuccess(callback, testUrl);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }

        postFailure(callback, new Exception("Node service startup timed out after " + timeoutMs + "ms"));
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
