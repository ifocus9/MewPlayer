package com.github.catvod.net;

import com.github.catvod.net.interceptor.RequestInterceptor;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RequestInterceptorTest {

    private Response execute(RequestInterceptor interceptor, Request request, AtomicReference<Request> captured) throws IOException {
        Interceptor.Chain chain = (Interceptor.Chain) Proxy.newProxyInstance(
                Interceptor.Chain.class.getClassLoader(),
                new Class<?>[]{Interceptor.Chain.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("request".equals(method.getName())) {
                            return request;
                        }
                        if ("proceed".equals(method.getName())) {
                            Request req = (Request) args[0];
                            captured.set(req);
                            return new Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(ResponseBody.create("ok", MediaType.get("text/plain")))
                                    .build();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                }
        );
        return interceptor.intercept(chain);
    }

    @Test
    public void addsBearerTokenForGitee() throws Exception {
        RequestInterceptor interceptor = new RequestInterceptor();
        Request request = new Request.Builder()
                .url("https://gitee.com/user/repo/raw/dist/index.js.md5?access_token=secret_gitee_token")
                .build();

        AtomicReference<Request> captured = new AtomicReference<>();
        try (Response resp = execute(interceptor, request, captured)) {
            assertEquals("Bearer secret_gitee_token", captured.get().header("Authorization"));
        }
    }

    @Test
    public void addsBearerTokenForGiteeUserContentRedirect() throws Exception {
        RequestInterceptor interceptor = new RequestInterceptor();
        Request request = new Request.Builder()
                .url("https://raw.giteeusercontent.com/user/repo/dist/index.js.md5?access_token=secret_gitee_token")
                .build();

        AtomicReference<Request> captured = new AtomicReference<>();
        try (Response resp = execute(interceptor, request, captured)) {
            assertEquals("Bearer secret_gitee_token", captured.get().header("Authorization"));
        }
    }

    @Test
    public void addsBearerTokenForGithub() throws Exception {
        RequestInterceptor interceptor = new RequestInterceptor();
        Request request = new Request.Builder()
                .url("https://raw.githubusercontent.com/user/repo/dist/index.js.md5?token=secret_github_token")
                .build();

        AtomicReference<Request> captured = new AtomicReference<>();
        try (Response resp = execute(interceptor, request, captured)) {
            assertEquals("Bearer secret_github_token", captured.get().header("Authorization"));
        }
    }

    @Test
    public void doesNotAddAuthorizationForOrdinaryDomains() throws Exception {
        RequestInterceptor interceptor = new RequestInterceptor();
        Request request = new Request.Builder()
                .url("https://example.com/test.js?access_token=secret_token")
                .build();

        AtomicReference<Request> captured = new AtomicReference<>();
        try (Response resp = execute(interceptor, request, captured)) {
            assertNull(captured.get().header("Authorization"));
        }
    }
}
