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
        if (url == null) return false;
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("gitee://") || lower.startsWith("gitee:/") ||
                lower.startsWith("github://") || lower.startsWith("github:/");
    }

    public static String convertGit(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean isGitee = lower.startsWith("gitee://") || lower.startsWith("gitee:/");
        boolean isGithub = lower.startsWith("github://") || lower.startsWith("github:/");
        if (!isGitee && !isGithub) return url;

        String raw = trimmed.replace("\\", "/");
        int colon = raw.indexOf(':');
        String remainder = raw.substring(colon + 1);
        while (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }

        // Separate fragment (#) and query (?) first to avoid confusion with @ in query/path
        String fragment = "";
        int hashIndex = remainder.indexOf('#');
        if (hashIndex >= 0) {
            fragment = remainder.substring(hashIndex);
            remainder = remainder.substring(0, hashIndex);
        }
        String query = "";
        int qIndex = remainder.indexOf('?');
        if (qIndex >= 0) {
            query = remainder.substring(qIndex + 1);
            remainder = remainder.substring(0, qIndex);
        }

        int firstSlash = remainder.indexOf('/');
        int atIndex = remainder.indexOf('@');
        String token = "";
        if (atIndex >= 0 && (firstSlash < 0 || atIndex < firstSlash)) {
            token = remainder.substring(0, atIndex).trim();
            remainder = remainder.substring(atIndex + 1).trim();
            firstSlash = remainder.indexOf('/');
        }

        String host;
        String path;
        if (firstSlash < 0) {
            host = remainder.contains(".") ? remainder : (isGitee ? "gitee.com" : "github.com");
            path = "";
        } else {
            String potentialHost = remainder.substring(0, firstSlash).trim();
            if (potentialHost.contains(".")) {
                host = potentialHost;
                path = remainder.substring(firstSlash + 1).trim();
            } else {
                host = isGitee ? "gitee.com" : "github.com";
                path = remainder.trim();
            }
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (!path.isEmpty()) {
            String[] segments = path.split("/");
            if (segments.length >= 3) {
                String owner = segments[0];
                String repo = segments[1];
                if (isGitee) {
                    if (!"raw".equalsIgnoreCase(segments[2])) {
                        int secondSlash = path.indexOf('/', owner.length() + 1);
                        String sub = secondSlash >= 0 ? path.substring(secondSlash + 1) : "";
                        path = owner + "/" + repo + "/raw/" + sub;
                    }
                } else {
                    host = "raw.githubusercontent.com";
                    if ("raw".equalsIgnoreCase(segments[2])) {
                        int rawIdx = path.indexOf("/raw/");
                        String sub = rawIdx >= 0 ? path.substring(rawIdx + 5) : "";
                        path = owner + "/" + repo + "/" + sub;
                    }
                }
            }
        }

        if (!token.isEmpty()) {
            String tokenParam = isGitee ? "access_token=" + token : "token=" + token;
            if (query.isEmpty()) {
                query = tokenParam;
            } else if (!query.contains(isGitee ? "access_token=" : "token=")) {
                query = query + "&" + tokenParam;
            }
        }

        StringBuilder sb = new StringBuilder("https://");
        sb.append(host);
        if (!path.isEmpty()) {
            sb.append('/').append(path);
        }
        if (!query.isEmpty()) {
            sb.append('?').append(query);
        }
        if (!fragment.isEmpty()) {
            sb.append(fragment);
        }
        return sb.toString();
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
