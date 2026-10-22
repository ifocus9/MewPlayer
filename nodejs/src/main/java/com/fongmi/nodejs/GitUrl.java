package com.fongmi.nodejs;

import java.util.Locale;

public class GitUrl {

    public static boolean isGitUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("gitee://") || lower.startsWith("gitee:/") ||
                lower.startsWith("github://") || lower.startsWith("github:/");
    }

    public static String convert(String url) {
        if (url == null) return "";
        if (url.contains(";md5;")) {
            int md5Index = url.indexOf(";md5;");
            return convert(url.substring(0, md5Index)) + url.substring(md5Index);
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
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
}
