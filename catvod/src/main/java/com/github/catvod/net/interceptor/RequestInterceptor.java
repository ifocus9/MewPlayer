package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class RequestInterceptor implements Interceptor {

    private final ConcurrentHashMap<String, String> authMap;
    private final ConcurrentHashMap<String, String> gitTokenMap;

    public RequestInterceptor() {
        authMap = new ConcurrentHashMap<>();
        gitTokenMap = new ConcurrentHashMap<>();
    }

    public void clear() {
        authMap.clear();
        gitTokenMap.clear();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Request.Builder builder = request.newBuilder();
        HttpUrl url = request.url();
        checkAuth(url, builder);
        checkGitAuth(request, url, builder);
        return chain.proceed(builder.build());
    }

    private void checkAuth(HttpUrl url, Request.Builder builder) {
        String host = url.host();
        String auth = url.queryParameter("auth");
        if (auth != null) authMap.put(host, auth);
        else if (authMap.containsKey(host)) builder.url(url.newBuilder().addQueryParameter("auth", authMap.get(host)).build());
    }

    private void checkGitAuth(Request request, HttpUrl url, Request.Builder builder) {
        String host = url.host();
        if (host.endsWith("gitee.com") || host.endsWith("giteeusercontent.com")) {
            String token = url.queryParameter("access_token");
            if (token != null && !token.isEmpty()) {
                gitTokenMap.put("gitee", token);
            } else {
                token = gitTokenMap.get("gitee");
            }
            if (token != null && !token.isEmpty() && request.header("Authorization") == null) {
                builder.header("Authorization", "Bearer " + token);
            }
        } else if (host.endsWith("github.com") || host.endsWith("githubusercontent.com")) {
            String token = url.queryParameter("token");
            if (token == null) token = url.queryParameter("access_token");
            if (token != null && !token.isEmpty()) {
                gitTokenMap.put("github", token);
            } else {
                token = gitTokenMap.get("github");
            }
            if (token != null && !token.isEmpty() && request.header("Authorization") == null) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
    }
}
