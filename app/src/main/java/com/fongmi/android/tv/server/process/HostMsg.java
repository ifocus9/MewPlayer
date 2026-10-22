package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.playback.PlaybackRuntime;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.WebActivity;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class HostMsg implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/msg");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.OPTIONS) return cors(json(Response.Status.NO_CONTENT, ""), session);
        if (session.getMethod() != NanoHTTPD.Method.POST) return cors(error(Response.Status.METHOD_NOT_ALLOWED, 405, "Only POST is supported"), session);

        try {
            String body = files.get("postData");
            if (TextUtils.isEmpty(body)) body = session.getParms().get("body");
            if (TextUtils.isEmpty(body)) return cors(error(Response.Status.BAD_REQUEST, 400, "Empty body"), session);

            JsonObject object = App.gson().fromJson(body, JsonObject.class);
            if (object == null) return cors(error(Response.Status.BAD_REQUEST, 400, "Invalid JSON"), session);

            String action = str(object, "action");
            JsonObject opt = obj(object, "opt");
            SpiderDebug.log("host-msg", "received action=%s", action);

            if ("openInternalWebview".equals(action) || "openWeb".equals(action)) {
                String targetUrl = str(opt, "url");
                String title = str(opt, "title");
                if (!TextUtils.isEmpty(targetUrl)) {
                    SpiderDebug.log("host-msg", "opening internal webview url=%s title=%s", targetUrl, title);
                    App.post(() -> {
                        try {
                            PlaybackService service = Server.get().getService();
                            if (service != null) service.dispatchStop();
                        } catch (Throwable t) {
                            SpiderDebug.log("host-msg", t);
                        }
                        android.app.Activity current = App.activity();
                        if (current != null && "VideoActivity".equals(current.getClass().getSimpleName())) {
                            WebActivity.start(current, targetUrl, title);
                            current.finish();
                        } else {
                            App.finish("VideoActivity");
                            WebActivity.start(App.get(), targetUrl, title);
                        }
                    });
                    return cors(json(Response.Status.OK, "{\"code\":200,\"msg\":\"success\"}"), session);
                }
            }

            if ("toast".equals(action)) {
                String message = str(opt, "message");
                if (!TextUtils.isEmpty(message)) {
                    App.post(() -> Notify.show(message));
                }
                return cors(json(Response.Status.OK, "{\"code\":200,\"msg\":\"success\"}"), session);
            }

            if ("getUserAgent".equals(action)) {
                String ua = "";
                try {
                    ua = WebSettings.getDefaultUserAgent(App.get());
                } catch (Throwable ignored) {
                }
                JsonObject res = new JsonObject();
                res.addProperty("code", 200);
                res.addProperty("ua", ua);
                return cors(json(Response.Status.OK, res.toString()), session);
            }

            if ("getCookie".equals(action)) {
                String targetUrl = str(opt, "url");
                String cookie = "";
                try {
                    if (!TextUtils.isEmpty(targetUrl)) {
                        cookie = CookieManager.getInstance().getCookie(targetUrl);
                    }
                } catch (Throwable ignored) {
                }
                JsonObject res = new JsonObject();
                res.addProperty("code", 200);
                res.addProperty("cookie", cookie == null ? "" : cookie);
                return cors(json(Response.Status.OK, res.toString()), session);
            }

            if ("setCookie".equals(action)) {
                String targetUrl = str(opt, "url");
                String cookie = str(opt, "cookie");
                try {
                    if (!TextUtils.isEmpty(targetUrl) && !TextUtils.isEmpty(cookie)) {
                        CookieManager.getInstance().setCookie(targetUrl, cookie);
                        CookieManager.getInstance().flush();
                    }
                } catch (Throwable ignored) {
                }
                return cors(json(Response.Status.OK, "{\"code\":200,\"msg\":\"success\"}"), session);
            }

            if ("getPlayInfo".equals(action)) {
                JsonObject res = new JsonObject();
                res.addProperty("code", 200);
                History history = PlaybackRuntime.currentHistory();
                PlayerManager player = history != null ? PlaybackRuntime.playerFor(history.getKey()) : null;
                String title = history != null ? history.getVodName() : "";
                String episode = history != null ? history.getVodRemarks() : "";
                String flag = history != null ? history.getVodFlag() : "";
                String id = history != null ? history.getVodId() : "";
                String playUrl = player != null && !player.isReleased() ? player.getUrl() : (history != null ? history.getEpisodeUrl() : "");
                long position = player != null && !player.isReleased() ? player.getPosition() : (history != null ? history.getPosition() : 0);
                long duration = player != null && !player.isReleased() ? player.getDuration() : (history != null ? history.getDuration() : 0);
                res.addProperty("title", title == null ? "" : title);
                res.addProperty("episodeName", episode == null ? "" : episode);
                res.addProperty("flag", flag == null ? "" : flag);
                res.addProperty("id", id == null ? "" : id);
                res.addProperty("url", playUrl == null ? "" : playUrl);
                res.addProperty("position", position);
                res.addProperty("duration", duration);
                return cors(json(Response.Status.OK, res.toString()), session);
            }

            return cors(json(Response.Status.OK, "{\"code\":200,\"msg\":\"unhandled\"}"), session);
        } catch (Throwable e) {
            SpiderDebug.log("host-msg", e);
            return cors(error(Response.Status.INTERNAL_ERROR, 500, e.getMessage()), session);
        }
    }

    private static JsonObject obj(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private Response error(Response.Status status, int code, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("code", code);
        object.addProperty("message", TextUtils.isEmpty(message) ? "error" : message);
        return json(status, object.toString());
    }

    private Response json(Response.Status status, String text) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", text);
    }

    private Response cors(Response response, IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        response.addHeader("Access-Control-Allow-Origin", TextUtils.isEmpty(origin) ? "*" : origin);
        response.addHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Access-Control-Allow-Methods", "POST,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Expose-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "86400");
        return response;
    }
}
