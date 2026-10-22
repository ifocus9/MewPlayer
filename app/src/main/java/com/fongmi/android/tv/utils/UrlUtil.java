package com.fongmi.android.tv.utils;

import android.net.Uri;

import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.UriUtil;
import com.google.common.net.HttpHeaders;

public class UrlUtil {

    public static Uri uri(String url) {
        return Uri.parse(url.trim().replace("\\", ""));
    }

    public static String scheme(String url) {
        if (url == null) return "";
        int colon = url.indexOf(':');
        if (colon > 0) {
            String s = url.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            if (s.matches("^[a-z][a-z0-9+.-]*$")) return s;
        }
        return scheme(Uri.parse(url));
    }

    public static String scheme(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null ? "" : scheme.toLowerCase().trim();
    }

    public static String host(String url) {
        return url == null ? "" : host(Uri.parse(url));
    }

    public static String host(Uri uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase().trim();
    }

    public static String path(String url) {
        return url == null ? "" : path(Uri.parse(url));
    }

    public static String path(Uri uri) {
        String path = uri.getLastPathSegment();
        return path == null ? "" : path.trim();
    }

    public static String resolve(String baseUri, String referenceUri) {
        return UriUtil.resolve(baseUri, referenceUri);
    }

    public static boolean isGitUrl(String url) {
        return com.fongmi.nodejs.GitUrl.isGitUrl(url);
    }

    public static String convertGit(String url) {
        return com.fongmi.nodejs.GitUrl.convert(url);
    }

    public static String convert(String url) {
        if (url == null) return "";
        if (url.startsWith("/")) url = "file://" + url;
        if (url.contains(";md5;")) {
            int md5Index = url.indexOf(";md5;");
            return convert(url.substring(0, md5Index)) + url.substring(md5Index);
        }
        if (isGitUrl(url)) {
            return convertGit(url);
        }
        String scheme = scheme(url);
        String path = null;
        if ("assets".equals(scheme)) path = "/";
        else if ("file".equals(scheme)) path = "/file/";
        else if ("proxy".equals(scheme)) path = "/proxy?";
        if (path == null) return url;
        String address = Server.get().getAddress(path);
        if (url.startsWith(scheme + ":///")) {
            return url.replace(scheme + ":///", address);
        } else if (url.startsWith(scheme + "://")) {
            return url.replace(scheme + "://", address);
        } else if (url.startsWith(scheme + ":/")) {
            return url.replace(scheme + ":/", address);
        }
        return url;
    }

    public static String getName(String url) {
        Uri uri = Uri.parse(url);
        String path = path(uri);
        String host = host(uri);
        return !path.isEmpty() ? path : !host.isEmpty() ? host : url;
    }

    public static String fixHeader(String key) {
        if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) return HttpHeaders.USER_AGENT;
        if (HttpHeaders.REFERER.equalsIgnoreCase(key)) return HttpHeaders.REFERER;
        if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) return HttpHeaders.COOKIE;
        return key;
    }
}
