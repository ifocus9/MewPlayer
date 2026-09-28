package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.WebActivity;
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

            if ("openInternalWebview".equals(action)) {
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
