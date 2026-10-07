package com.fongmi.android.tv.web;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.fongmi.android.tv.web.page.WebPage;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HomeWebController {

    private static final String BRIDGE = "fongmiBridge";

    private final Listener listener;
    private final Activity activity;
    private final boolean debugTools;
    /*
     * 承载底部「网页」Tab 中用户自定义的网页（{@link #loadPage(WebPage, boolean)}）：
     *   - 只有 trusted 网页才挂载 fongmiBridge / 注入 SDK，且调用方 origin 必须与网页 origin 一致；
     *   - 返回键只要有历史就后退；
     *   - 加载失败回调 Listener#onWebPageError(int, String)；
     *   - 支持文件上传、HTML5 全屏视频、下载与外部 scheme 链接。
     */
    private volatile WebPage page;
    private volatile String allowedOrigin = "";
    private volatile String currentOrigin = "";
    private boolean bridgeAttached;
    private WebView webView;
    private final float density;
    /** trusted 网页：在 document-start 注入 SDK，保证每次导航 / 刷新后页面脚本执行前 window.fongmi 就已存在 */
    private ScriptHandler pageSdkHandler;
    private String pageSdkOrigin = "";
    /** PAGE 模式切换网页：pending 在 loadPage 置位，新导航 onPageStarted 时 armed，提交后 clearHistory */
    private boolean clearHistoryPending;
    private boolean clearHistoryArmed;
    /** PAGE 模式：loadPage 发起的首次加载尚未提交文档，期间的服务端 3xx 跳转视为同一个可信网页 */
    private boolean followInitialRedirect;
    private String defaultUserAgent;
    private String homePage;
    private String lastPageUrl;
    private WebHomeViewport viewport = WebHomeViewport.EMPTY;
    private String lastViewportKey;
    private long pauseAt;
    private int inlineEvaluationCount;
    private boolean paused;

    private HomeWebController(Activity activity, WebView webView, Listener listener, boolean debugTools) {
        this.activity = activity;
        this.webView = webView;
        this.listener = listener;
        this.debugTools = debugTools;
        this.density = activity.getResources().getDisplayMetrics().density;
        init();
    }

    /**
     * 创建用于底部「网页」Tab 的控制器。
     */
    public static HomeWebController forPage(Activity activity, WebView webView, Listener listener) {
        return new HomeWebController(activity, webView, listener, false);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void init() {
        if (debugTools) WebView.setWebContentsDebuggingEnabled(true);
        WebViewUtil.configureHome(webView);
        configurePage();
        defaultUserAgent = webView.getSettings().getUserAgentString();
        webView.setBackgroundColor(Color.TRANSPARENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setOnFocusChangeListener((v, hasFocus) -> SpiderDebug.log("webhome-focus", "webview focus=%s visible=%s url=%s", hasFocus, isVisible(), webView.getUrl()));
        webView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> injectViewport());
        bridgeAttached = false;
        updateBridge();
        webView.setWebViewClient(client());
        webView.setWebChromeClient(chrome());
        WebViewUtil.logProvider("webhome");
    }

    private void configurePage() {
        WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 不支持多窗口：window.open / target=_blank 直接在当前 WebView 中打开
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> onDownload(url));
    }

    /**
     * 只有 trusted 网页才挂载 bridge。
     * add/removeJavascriptInterface 在下一次页面加载时生效，因此必须在 loadUrl 之前调用。
     */
    @SuppressLint({"JavascriptInterface", "AddJavascriptInterface"})
    private void updateBridge() {
        boolean attach = page != null && page.isTrusted();
        if (attach == bridgeAttached) return;
        if (attach) webView.addJavascriptInterface(new HomeWebBridge(this, activity, webView), BRIDGE);
        else webView.removeJavascriptInterface(BRIDGE);
        bridgeAttached = attach;
    }

    public WebPage getPage() {
        return page;
    }

    /**
     * 主线程调用：当前主文档是否允许使用原生能力。
     */
    public boolean isBridgeAllowed() {
        if (page == null || !page.isTrusted() || !bridgeAttached) return false;
        String origin = WebPage.origin(webView.getUrl());
        return !origin.isEmpty() && origin.equals(allowedOrigin);
    }

    /**
     * 任意线程调用（@JavascriptInterface 同步方法）：使用主线程在导航回调中缓存的 origin。
     */
    public boolean isBridgeAllowedCached() {
        WebPage current = page;
        if (current == null || !current.isTrusted()) return false;
        String origin = currentOrigin;
        return !origin.isEmpty() && origin.equals(allowedOrigin);
    }

    private void updateCurrentOrigin(String url) {
        currentOrigin = WebPage.origin(url);
    }

    /**
     * 加载用户网页。UA / 请求头来自 WebPage。
     */
    public boolean loadPage(WebPage target, boolean force) {
        if (target == null || !WebPage.isHttpUrl(target.getUrl())) return false;
        String url = UrlUtil.convert(target.getUrl());
        if (TextUtils.isEmpty(url)) return false;
        boolean switched = page == null || !page.getId().equals(target.getId());
        boolean changed = switched || page.getUpdateTime() != target.getUpdateTime();
        // 切换到另一个网页：新网页加载后清空历史，避免返回键回到上一个网页
        // （上一个网页的 origin 不再被信任，回去后原生能力会失效，标题也对不上）
        if (switched) {
            clearHistoryPending = true;
            clearHistoryArmed = false;
        }
        boolean reload = force || changed || !url.equals(homePage);
        page = target;
        // 不重新加载（如在列表里再次选中当前网页）时保留已生效的 origin：它可能已跟随首次加载的服务端跳转更新过
        if (reload || TextUtils.isEmpty(allowedOrigin)) allowedOrigin = WebPage.origin(url);
        // 不能清空为 ""：同一网页不重新加载时不会触发 onPageStarted，
        // 清空后同步桥方法（resultChunk / resourceUrl 等）会一直被拒绝，表现为原生能力丢失
        updateCurrentOrigin(webView.getUrl());
        updateBridge();
        registerPageSdk();
        Server.get().start();
        if (isLocalHostUrl(url) && !com.fongmi.android.tv.node.NodeBundleManager.isServiceRunning()) {
            com.fongmi.android.tv.node.NodeBundleManager.startIfPresent(activity);
            if (!com.fongmi.android.tv.node.NodeBundleManager.isServiceRunning()) {
                SpiderDebug.log("webhome", "page waits local node service url=%s", url);
                listener.onWebLoading();
                show();
                waitForLocalNodeAndLoad(url, () -> {
                    if (page == target) doLoadPage(url, true);
                });
                return true;
            }
        }
        return doLoadPage(url, reload);
    }

    private boolean doLoadPage(String url, boolean reload) {
        if (reload || !url.equals(homePage)) {
            lastViewportKey = "";
            homePage = url;
            followInitialRedirect = true;
            loadUrl(url);
        }
        show();
        return true;
    }

    private void notifyError(int code, String description) {
        listener.onWebPageError(code, description == null ? "" : description);
    }

    private boolean handleExternalUrl(String url) {
        String scheme = UrlUtil.scheme(url);
        if (scheme.isEmpty() || "http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme) || "javascript".equals(scheme) || "file".equals(scheme)) return false;
        try {
            if ("intent".equals(scheme)) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = intent.getStringExtra("browser_fallback_url");
                // 安全：不允许网页通过 intent:// 指定组件或 selector
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                try {
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    if (!WebPage.isHttpUrl(fallback)) throw e;
                    webView.loadUrl(fallback);
                }
                return true;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (Throwable e) {
            SpiderDebug.log("webhome-webview", "external url failed url=%s error=%s", url, e.getMessage());
            Notify.show(R.string.web_page_no_app);
        }
        return true;
    }

    private void onDownload(String url) {
        if (!WebPage.isHttpUrl(url)) {
            Notify.show(R.string.web_page_download_unsupported);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (Throwable e) {
            Notify.show(R.string.web_page_no_app);
        }
    }

    private static boolean isLocalHostUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("127.0.0.1:9988") || url.contains("localhost:9988");
    }

    private void waitForLocalNodeAndLoad(String url, Runnable onReady) {
        final long start = System.currentTimeMillis();
        new Thread(() -> {
            boolean ready = false;
            while (System.currentTimeMillis() - start < 8000) {
                if (com.fongmi.android.tv.node.NodeBundleManager.isServiceRunning()) {
                    ready = true;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            final boolean isReady = ready;
            App.post(() -> {
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (isReady) {
                    SpiderDebug.log("webhome", "local node service is now ready, loading url=%s", url);
                    onReady.run();
                } else {
                    SpiderDebug.log("webhome", "local node service wait timed out");
                    notifyError(-6, "local node service is not ready");
                }
            });
        }).start();
    }

    public void reload() {
        // 刷新当前页面（而不是回到首页）
        String current = webView.getUrl();
        if (isEmptyDocumentUrl(current) && !TextUtils.isEmpty(homePage)) loadUrl(homePage);
        else webView.reload();
    }

    private void loadUrl(String rawUrl) {
        String url = com.fongmi.android.tv.node.NodeBundleManager.localUrl(rawUrl);
        if (!TextUtils.equals(url, rawUrl)) {
            // 本地 Node 服务因 9988 被其它应用占用换了端口：跟随实际端口，受信任的 origin 一并迁移
            if (WebPage.origin(rawUrl).equals(allowedOrigin)) allowedOrigin = WebPage.origin(url);
        }
        WebPage current = page;
        Map<String, String> headers = current == null ? Collections.<String, String>emptyMap() : current.getHeaders();
        String userAgent = current != null && !TextUtils.isEmpty(current.getUa()) ? current.getUa() : header(headers, HttpHeaders.USER_AGENT);
        if (!TextUtils.isEmpty(userAgent)) webView.getSettings().setUserAgentString(userAgent);
        else if (!TextUtils.isEmpty(defaultUserAgent)) webView.getSettings().setUserAgentString(defaultUserAgent);
        Map<String, String> requestHeaders = requestHeaders(url, headers);
        lastPageUrl = url;
        SpiderDebug.log("webhome-webview", "load url=%s ua=%s headers=%s", url, !TextUtils.isEmpty(userAgent), requestHeaders.keySet());
        // 不做超时重建：用户网页加载慢是常态，错误由 WebView 自身回调
        if (requestHeaders.isEmpty()) webView.loadUrl(url);
        else webView.loadUrl(url, requestHeaders);
    }

    private Map<String, String> requestHeaders(String url, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(key) || value == null) continue;
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) continue;
            if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) {
                CookieManager.getInstance().setCookie(url, value);
                continue;
            }
            result.put(key, value);
        }
        return result;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public void evaluate(String script, ValueCallback<String> callback) {
        webView.post(() -> webView.evaluateJavascript(script, callback));
    }

    public void dispatchDebugConsole(String level, String message) {
        if (!debugTools) return;
        String text = (TextUtils.isEmpty(level) ? "log" : level).toUpperCase(Locale.ROOT) + " " + (message == null ? "" : message);
        App.post(() -> listener.onWebConsole(text));
    }

    public void dispatchDebugNetwork(String type, String method, String url, int status, long durationMs, String detail) {
        if (!debugTools) return;
        App.post(() -> listener.onWebNetwork(type, method, url, status, durationMs, detail));
    }

    public void show() {
        webView.setVisibility(View.VISIBLE);
        focusWebView("show");
    }

    public void hide() {
        webView.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return webView.getVisibility() == View.VISIBLE;
    }

    public boolean handleBack() {
        if (!isVisible()) return false;
        if (!webView.canGoBack()) return false;
        // 用户自由浏览：只要有历史就后退，不受「首页 / 跨域」边界限制
        webView.goBack();
        return true;
    }

    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            listener.setChrome(normalChrome());
            return;
        }
        listener.setToolbar(visible);
    }

    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            listener.setChrome(normalChrome());
            return;
        }
        listener.setChrome(payload);
    }

    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            listener.setChrome(normalChrome());
            return;
        }
        listener.restoreChrome();
    }

    private JsonObject normalChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", WebHomeChrome.NORMAL);
        return object;
    }

    public String getViewportJson() {
        return viewport.json(density, webView.getWidth(), webView.getHeight());
    }

    public void setViewport(WebHomeViewport viewport) {
        this.viewport = viewport == null ? WebHomeViewport.EMPTY : viewport;
        injectViewport();
    }

    public void openVod() {
        listener.openVod();
    }

    public void openSetting() {
        listener.openSetting();
    }

    public void onResume() {
        paused = false;
        synchronized (this) {
            inlineEvaluationCount = 0;
        }
        webView.onResume();
        webView.resumeTimers();
        recoverAfterResume();
    }

    public void onPause() {
        paused = true;
        pauseAt = System.currentTimeMillis();
        dispatchLifecycle("fmpause", "{time:" + pauseAt + "}");
        webView.onPause();
    }

    public boolean beginInlineEvaluation() {
        synchronized (this) {
            if (!paused) return false;
            inlineEvaluationCount++;
            if (inlineEvaluationCount > 1) return true;
        }
        App.post(() -> {
            if (!paused) return;
            SpiderDebug.log("webhome-inline", "resume WebView for inline evaluation url=%s", webView.getUrl());
            webView.onResume();
            webView.resumeTimers();
        });
        return true;
    }

    public void endInlineEvaluation(boolean active) {
        if (!active) return;
        boolean pause;
        synchronized (this) {
            if (inlineEvaluationCount > 0) inlineEvaluationCount--;
            pause = paused && inlineEvaluationCount == 0;
        }
        if (!pause) return;
        App.post(() -> {
            if (!paused) return;
            SpiderDebug.log("webhome-inline", "pause WebView after inline evaluation url=%s", webView.getUrl());
            webView.onPause();
        });
    }

    public void destroy() {
        removePageSdk();
        webView.stopLoading();
        webView.destroy();
        if (debugTools) WebView.setWebContentsDebuggingEnabled(false);
    }

    private void recreateWebView() {
        ViewGroup parent = webView.getParent() instanceof ViewGroup ? (ViewGroup) webView.getParent() : null;
        if (parent == null) return;
        int index = parent.indexOfChild(webView);
        int id = webView.getId();
        int visibility = webView.getVisibility();
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        try {
            removePageSdk();
            webView.stopLoading();
            parent.removeView(webView);
            webView.destroy();
        } catch (Throwable ignored) {
        }
        webView = new WebView(activity);
        webView.setId(id);
        webView.setVisibility(visibility);
        parent.addView(webView, Math.max(0, index), params);
        init();
        registerPageSdk();
    }

    private void recoverAfterResume() {
        if (!isVisible()) return;
        if (recoverEmptyDocument()) return;
        webView.setBackgroundColor(Color.TRANSPARENT);
        focusWebView("resume");
        webView.requestLayout();
        webView.invalidate();
        webView.postInvalidateOnAnimation();
        nudgeCompositor();
        dispatchResume(0);
        dispatchResume(80);
        dispatchResume(260);
    }

    private boolean recoverEmptyDocument() {
        String current = webView.getUrl();
        if (!isEmptyDocumentUrl(current) || TextUtils.isEmpty(homePage)) return false;
        String target = !TextUtils.isEmpty(lastPageUrl) && !isEmptyDocumentUrl(lastPageUrl) ? lastPageUrl : homePage;
        SpiderDebug.log("webhome-webview", "restore reload reason=empty-url current=%s target=%s", current, target);
        listener.onWebLoading();
        lastViewportKey = "";
        loadUrl(target);
        return true;
    }

    private boolean isEmptyDocumentUrl(String url) {
        return TextUtils.isEmpty(url) || "about:blank".equalsIgnoreCase(url);
    }

    private void dispatchResume(long delay) {
        webView.postDelayed(() -> {
            injectViewport();
            long now = System.currentTimeMillis();
            long pausedMs = pauseAt > 0 ? Math.max(0, now - pauseAt) : 0;
            dispatchLifecycle("fmresume", "{time:" + now + ",pausedMs:" + pausedMs + "}");
        }, delay);
    }

    private void nudgeCompositor() {
        webView.setAlpha(0.99f);
        webView.postDelayed(() -> {
            webView.setAlpha(1f);
            webView.invalidate();
            webView.postInvalidateOnAnimation();
        }, 50);
    }

    private boolean focusWebView(String reason) {
        if (webView.hasFocus()) return true;
        boolean ok = webView.requestFocus();
        SpiderDebug.log("webhome-focus", "request reason=%s ok=%s visible=%s width=%s height=%s url=%s", reason, ok, isVisible(), webView.getWidth(), webView.getHeight(), webView.getUrl());
        return ok;
    }

    private void dispatchLifecycle(String event, String detail) {
        String script = "(function(){try{window.dispatchEvent(new CustomEvent('" + event + "',{detail:" + detail + "}));}catch(e){}})();";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private WebViewClient client() {
        return new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                SpiderDebug.log("webhome-webview", "page started url=%s", url);
                listener.onWebRequest("PAGE", url, true);
                updateCurrentOrigin(url);
                if (clearHistoryPending) {
                    clearHistoryPending = false;
                    clearHistoryArmed = true;
                }
                lastPageUrl = url;
                lastViewportKey = "";
                listener.onWebLoading();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                // 兜底：比 onPageFinished 更早（不等图片等子资源），不支持 document-start 时尽早补上 SDK
                updateCurrentOrigin(view.getUrl());
                if (isBridgeAllowed()) injectSdk();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                SpiderDebug.log("webhome-webview", "page finished url=%s title=%s", url, view.getTitle());
                lastPageUrl = url;
                updateCurrentOrigin(view.getUrl());
                if (isBridgeAllowed()) injectSdk();
                else injectViewport();
                focusWebView("page-finished");
                listener.onWebReady();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateCurrentOrigin(url);
                // 首次加载的文档已提交，之后的跳转（含 JS 跳转 / 用户点击）不再自动扩展可信 origin
                followInitialRedirect = false;
                if (clearHistoryArmed) {
                    // 新网页的文档已提交：丢弃切换前网页的历史
                    clearHistoryArmed = false;
                    view.clearHistory();
                    SpiderDebug.log("webhome", "page switched, history cleared url=%s", url);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                SpiderDebug.log("webhome-webview", "resource error main=%s code=%s desc=%s url=%s", request.isForMainFrame(), error.getErrorCode(), error.getDescription(), request.getUrl());
                listener.onWebConsole("ERROR " + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
                if (request.isForMainFrame()) {
                    // 保留 homePage，便于错误页「重试」时重新加载
                    listener.onWebPageError(error.getErrorCode(), String.valueOf(error.getDescription()));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String target = request.getUrl().toString();
                if (handleExternalUrl(target)) return true;
                followTrustedRedirect(request, target);
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                listener.onWebRequest(request.getMethod(), request.getUrl().toString(), request.isForMainFrame(), request.getRequestHeaders());
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                SpiderDebug.log("webhome-webview", "render process gone didCrash=%s priority=%s", detail.didCrash(), detail.rendererPriorityAtExit());
                recreateWebView();
                if (!TextUtils.isEmpty(homePage)) {
                    listener.onWebLoading();
                    loadUrl(homePage);
                } else {
                    notifyError(-1, "render process gone");
                }
                return true;
            }
        };
    }

    private WebChromeClient chrome() {
        return new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    String line = String.format(Locale.ROOT, "%s %s:%s %s", message.messageLevel(), message.sourceId(), message.lineNumber(), message.message());
                    SpiderDebug.log("webhome-console", line);
                    listener.onWebConsole(line);
                }
                return super.onConsoleMessage(message);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return listener.onShowFileChooser(callback, params);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                listener.onShowCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                listener.onHideCustomView();
            }
        };
    }

    private void injectSdk() {
        injectViewport();
        webView.evaluateJavascript(getSdk(), null);
    }

    /**
     * trusted 网页把 SDK 注册为 document-start 脚本，仅对该网页 origin 生效。
     * onPageFinished 在图片多 / 网络慢时会很晚才回调，页面脚本启动时检测不到 window.fongmi 就会退化为非原生模式；
     * document-start 让每次加载（含刷新、前进后退、JS 跳转）都在页面脚本之前就具备原生能力。
     * 不支持该特性的 WebView 仍走 onPageCommitVisible / onPageFinished 注入兜底。
     */
    private void registerPageSdk() {
        WebPage current = page;
        String origin = current != null && current.isTrusted() && bridgeAttached ? allowedOrigin : "";
        if (pageSdkHandler != null && origin.equals(pageSdkOrigin)) return;
        removePageSdk();
        if (TextUtils.isEmpty(origin) || !isDocumentStartSupported()) return;
        String rule = originRule(origin);
        if (TextUtils.isEmpty(rule)) return;
        try {
            pageSdkHandler = WebViewCompat.addDocumentStartJavaScript(webView, getSdk(), Collections.singleton(rule));
            pageSdkOrigin = origin;
            SpiderDebug.log("webhome", "page sdk document-start registered rule=%s", rule);
        } catch (Throwable e) {
            pageSdkHandler = null;
            pageSdkOrigin = "";
            SpiderDebug.log("webhome", "page sdk document-start register failed rule=%s error=%s", rule, e.getMessage());
        }
    }

    /**
     * trusted 网页首次加载时的服务端 3xx 跳转（如 http→https、example.com→www.example.com、/→/app/ 到另一个端口）
     * 仍视为用户添加的那个网页：把可信 origin 更新为跳转目标，否则切换到这类网页后原生能力始终不生效。
     * 只在首次文档提交前生效；JS 跳转、用户点击链接都不会扩展可信范围。
     */
    private void followTrustedRedirect(WebResourceRequest request, String target) {
        if (!followInitialRedirect || !request.isRedirect()) return;
        WebPage current = page;
        if (current == null || !current.isTrusted()) return;
        String origin = WebPage.origin(target);
        if (origin.isEmpty() || origin.equals(allowedOrigin)) return;
        SpiderDebug.log("webhome", "trusted page redirect origin %s -> %s", allowedOrigin, origin);
        allowedOrigin = origin;
        registerPageSdk();
    }

    private void removePageSdk() {
        try {
            if (pageSdkHandler != null) pageSdkHandler.remove();
        } catch (Throwable e) {
            SpiderDebug.log("webhome", "page sdk document-start remove failed error=%s", e.getMessage());
        }
        pageSdkHandler = null;
        pageSdkOrigin = "";
    }

    /**
     * WebPage.origin 总是带端口（默认端口也补齐），document-start 的 origin 规则里去掉默认端口更稳妥。
     */
    private static String originRule(String origin) {
        if (TextUtils.isEmpty(origin)) return "";
        if (origin.startsWith("http://") && origin.endsWith(":80")) return origin.substring(0, origin.length() - 3);
        if (origin.startsWith("https://") && origin.endsWith(":443")) return origin.substring(0, origin.length() - 4);
        return origin;
    }

    private boolean isDocumentStartSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable e) {
            return false;
        }
    }

    private void injectViewport() {
        if (webView.getWidth() <= 0 || webView.getHeight() <= 0) return;
        String key = viewport.key(density, webView.getWidth(), webView.getHeight());
        if (key.equals(lastViewportKey)) return;
        lastViewportKey = key;
        String script = viewport.script(density, webView.getWidth(), webView.getHeight());
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private String getSdk() {
        return String.format(Locale.ROOT, """
                (function(){
                  if(window.fm&&window.fongmi){if(document&&document.documentElement)document.documentElement.classList.add('fm-native');window.dispatchEvent(new CustomEvent('fmsdk'));return;}
                  if(document&&document.documentElement)document.documentElement.classList.add('fm-native');
                  window.fongmiClient={mode:'%s',isLeanback:%s};
                  const callbacks={};
                  let seq=0;
                  function invoke(method,payload){
                    return new Promise((resolve,reject)=>{
                      const id='fm_'+Date.now()+'_'+(++seq);
                      callbacks[id]={resolve,reject};
                      fongmiBridge.invoke(id,method,JSON.stringify(payload||{}));
                    });
                  }
                  function hydrate(data){
                    if(!data||!data.__fmResultId)return data;
                    const resultId=data.__fmResultId;
                    const length=fongmiBridge.resultLength(resultId);
                    let text='';
                    for(let start=0;start<length;start+=60000)text+=fongmiBridge.resultChunk(resultId,start);
                    fongmiBridge.clearResult(resultId);
                    return JSON.parse(text);
                  }
                  window.fongmiNative={
                    resolve:(id,data)=>{ if(callbacks[id]){ callbacks[id].resolve(hydrate(data)); delete callbacks[id]; } },
                    reject:(id,error)=>{ if(callbacks[id]){ callbacks[id].reject(new Error(error||'')); delete callbacks[id]; } }
                  };
                  if(!window.__fmUrlHook&&window.history){
                    window.__fmUrlHook=true;
                    const emit=()=>window.dispatchEvent(new CustomEvent('fmurlchange',{detail:{url:location.href}}));
                    const rawPush=history.pushState;
                    const rawReplace=history.replaceState;
                    history.pushState=function(){const r=rawPush.apply(this,arguments);emit();return r;};
                    history.replaceState=function(){const r=rawReplace.apply(this,arguments);emit();return r;};
                    window.addEventListener('popstate',emit);
                  }
                  %s
                  const player={
                    playUrl:(url,title,options)=>invoke('player.playUrl',Object.assign({},options||{},{url,title})),
                    playVod:(siteKey,vodId,title,pic,options)=>invoke('player.playVod',Object.assign({},options||{},{siteKey,vodId,title,pic})),
                    playVodInline:(payload)=>invoke('player.playVodInline',payload||{}),
                    preloadArtwork:(pic,wallPic)=>invoke('player.preloadArtwork',{pic,wallPic}),
                    control:(action)=>invoke('player.control',{action}),
                    status:()=>invoke('player.status',{})
                  };
                  const net={
                    request:(url,options)=>invoke('net.request',Object.assign({},options||{},{url})),
                    resourceUrl:(url,options)=>fongmiBridge.resourceUrl(url,JSON.stringify(options||{}))
                  };
                  const cache={
                    get:(key,rule)=>invoke('cache.get',{key,rule}),
                    set:(key,value,rule)=>invoke('cache.set',{key,value,rule}),
                    del:(key,rule)=>invoke('cache.del',{key,rule})
                  };
                  const pan={
                    check:(items)=>invoke('pan.check',{items}),
                    play:(payload)=>invoke('pan.play',payload||{})
                  };
                  const ext={
                    info:()=>invoke('ext.info',{}),
                    log:(message,data)=>invoke('ext.log',{message,data}),
                    toast:(message)=>invoke('ext.toast',{message})
                  };
                  const ui={
                    setToolbar:(visible)=>invoke('ui.setToolbar',{visible:visible!==false}),
                    setChrome:(options)=>invoke('ui.setChrome',options||{}),
                    restoreChrome:()=>invoke('ui.restoreChrome',{}),
                    getViewport:()=>invoke('ui.getViewport',{})
                  };
                  window.fongmi={invoke,player,net,cache,
                    app:{
                      search:(keyword,options)=>invoke('app.search',Object.assign({},options||{},{keyword})),
                      openVod:()=>invoke('app.openVod',{}),
                      openLive:()=>invoke('app.openLive',{}),
                      openSetting:()=>invoke('app.openSetting',{}),
                      history:()=>invoke('app.history',{})
                    },
                    pan,
                    ext,
                    device:{info:()=>invoke('device.info',{})},
                    site:{info:()=>invoke('site.info',{})},
                    config:{info:()=>invoke('config.info',{})},
                    ui,
                    navigation:{
                      back:()=>invoke('navigation.back',{}),
                      reload:()=>invoke('navigation.reload',{})
                    }
                  };
                  window.fm={
                    req:net.request,
                    res:net.resourceUrl,
                    play:player.playUrl,
                    vod:player.playVod,
                    vodInline:player.playVodInline,
                    preloadArtwork:player.preloadArtwork,
                    ctrl:player.control,
                    stat:player.status,
                    search:window.fongmi.app.search,
                    openVod:window.fongmi.app.openVod,
                    openLive:window.fongmi.app.openLive,
                    openSetting:window.fongmi.app.openSetting,
                    history:window.fongmi.app.history,
                    pan,
                    check:window.fongmi.pan.check,
                    cache,
                    ext,
                    ui,
                    device:window.fongmi.device.info,
                    site:window.fongmi.site.info,
                    config:window.fongmi.config.info,
                    back:window.fongmi.navigation.back,
                    reload:window.fongmi.navigation.reload
                  };
                  window.dispatchEvent(new CustomEvent('fmsdk'));
                })();
                """, com.fongmi.android.tv.BuildConfig.FLAVOR_mode, com.fongmi.android.tv.utils.Util.isLeanback(), debugTools ? debugSdkHook() : "");
    }

    private String debugSdkHook() {
        return """
                  if(!window.__fmConsoleHook){
                    window.__fmConsoleHook=true;
                    ['log','info','warn','error','debug'].forEach(function(level){
                      const raw=console[level]||console.log;
                      console[level]=function(){
                        const args=Array.prototype.slice.call(arguments);
                        try{fongmiBridge.console(level,args.map(function(v){try{return typeof v==='string'?v:JSON.stringify(v);}catch(e){return String(v);}}).join(' '));}catch(e){}
                        return raw&&raw.apply(console,args);
                      };
                    });
                  }
                  if(!window.__fmNetworkHook){
                    window.__fmNetworkHook=true;
                    const absolute=function(url){try{return new URL(String(url),location.href).href;}catch(e){return String(url||'');}};
                    const clip=function(value){value=String(value||'');return value.length>2000?value.slice(0,2000)+'\\n...truncated':value;};
                    const bodyText=function(body){
                      if(!body)return '';
                      if(typeof body==='string')return 'payload:\\n'+clip(body);
                      if(body instanceof URLSearchParams)return 'payload:\\n'+clip(body.toString());
                      if(body instanceof FormData){
                        const out=[];
                        try{body.forEach(function(v,k){out.push(k+'='+(v&&v.name?'[file '+v.name+']':String(v)));});}catch(e){}
                        return 'payload:\\n'+clip(out.join('\\n'));
                      }
                      return 'payloadType='+Object.prototype.toString.call(body)+'\\npayloadBytes='+String(body).length;
                    };
                    const headers=function(headers){
                      const out=[];
                      try{
                        if(headers&&headers.forEach)headers.forEach(function(v,k){out.push(k+': '+v);});
                        else if(headers)Object.keys(headers).forEach(function(k){out.push(k+': '+headers[k]);});
                      }catch(e){}
                      return out.join('\\n');
                    };
                    const rawFetch=window.fetch;
                    if(rawFetch){
                      window.fetch=function(input,init){
                        const started=Date.now();
                        const method=(init&&init.method)||(input&&input.method)||'GET';
                        const url=absolute(input&&input.url?input.url:input);
                        const requestHeaders=headers((init&&init.headers)||(input&&input.headers));
                        const body=bodyText(init&&init.body);
                        try{fongmiBridge.network('FETCH_START',method,url,0,0,[requestHeaders,body].filter(Boolean).join('\\n'));}catch(e){}
                        return rawFetch.apply(this,arguments).then(function(resp){
                          try{fongmiBridge.network('FETCH_DONE',method,url,resp.status||0,Date.now()-started,['type='+(resp.type||''),'headers:',headers(resp.headers)].join('\\n'));}catch(e){}
                          return resp;
                        }).catch(function(err){
                          try{fongmiBridge.network('FETCH_ERROR',method,url,0,Date.now()-started,String(err&&err.message||err));}catch(e){}
                          throw err;
                        });
                      };
                    }
                    const RawXHR=window.XMLHttpRequest;
                    if(RawXHR&&RawXHR.prototype){
                      const rawOpen=RawXHR.prototype.open;
                      const rawSend=RawXHR.prototype.send;
                      RawXHR.prototype.open=function(method,url){
                        this.__fmMethod=method||'GET';
                        this.__fmUrl=absolute(url);
                        return rawOpen.apply(this,arguments);
                      };
                      RawXHR.prototype.send=function(){
                        const xhr=this;
                        const started=Date.now();
                        const body=arguments.length?bodyText(arguments[0]):'';
                        try{fongmiBridge.network('XHR_START',xhr.__fmMethod||'GET',xhr.__fmUrl||'',0,0,body);}catch(e){}
                        xhr.addEventListener('loadend',function(){
                          try{fongmiBridge.network('XHR_DONE',xhr.__fmMethod||'GET',xhr.__fmUrl||'',xhr.status||0,Date.now()-started,[xhr.statusText||'',xhr.getAllResponseHeaders&&xhr.getAllResponseHeaders()||''].filter(Boolean).join('\\n'));}catch(e){}
                        });
                        xhr.addEventListener('error',function(){
                          try{fongmiBridge.network('XHR_ERROR',xhr.__fmMethod||'GET',xhr.__fmUrl||'',xhr.status||0,Date.now()-started,'error');}catch(e){}
                        });
                        return rawSend.apply(this,arguments);
                      };
                    }
                  }
                """;
    }

    public interface Listener {

        void onWebLoading();

        void onWebReady();

        /**
         * 主文档加载失败（含本地服务等待超时、渲染进程崩溃）。
         */
        void onWebPageError(int code, String description);

        /**
         * 文件上传。返回 true 表示已接管，并且之后必须调用 callback.onReceiveValue(...)。
         */
        default boolean onShowFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
            return false;
        }

        /**
         * HTML5 全屏视频。默认不支持，立即通知网页退出全屏。
         */
        default void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            if (callback != null) callback.onCustomViewHidden();
        }

        default void onHideCustomView() {
        }

        default void setToolbar(boolean visible) {
        }

        default void setChrome(JsonObject payload) {
        }

        default void restoreChrome() {
        }

        default WebHomeViewport getViewport() {
            return WebHomeViewport.EMPTY;
        }

        default void openVod() {
        }

        default void openSetting() {
        }

        default void onWebConsole(String line) {
        }

        default void onWebRequest(String method, String url, boolean mainFrame) {
        }

        default void onWebRequest(String method, String url, boolean mainFrame, Map<String, String> headers) {
            onWebRequest(method, url, mainFrame);
        }

        default void onWebNetwork(String type, String method, String url, int status, long durationMs, String detail) {
        }
    }
}
