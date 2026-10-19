package com.fongmi.android.tv.api.tmdb.bean;

import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 影片/剧集详情（含 append_to_response=credits,images 等）。
 * 直接继承 TmdbMedia 以复用基础字段。
 */
public class TmdbDetail extends TmdbMedia {

    @SerializedName("genres")
    private List<Genre> genres;

    @SerializedName("runtime")
    private Integer runtime;          // 电影时长（分钟）

    @SerializedName("episode_run_time")
    private List<Integer> episodeRunTime; // 剧集单集时长

    @SerializedName("status")
    private String status;

    @SerializedName("credits")
    private Credits credits;

    @SerializedName("images")
    private Images images;

    @SerializedName("recommendations")
    private TmdbResult recommendations;

    @SerializedName("similar")
    private TmdbResult similar;

    // ---- Inner classes ----

    public static class Images {
        @SerializedName("backdrops")
        public List<ImageItem> backdrops;
        @SerializedName("posters")
        public List<ImageItem> posters;
    }

    public static class ImageItem {
        @SerializedName("file_path")
        public String filePath;
        @SerializedName("vote_average")
        public double voteAverage;
    }

    public static class Genre {
        @SerializedName("id")
        public int id;
        @SerializedName("name")
        public String name;
    }

    public static class Credits {
        @SerializedName("cast")
        public List<CastMember> cast;
        @SerializedName("crew")
        public List<CrewMember> crew;
    }

    public static class CastMember {
        @SerializedName("id")
        public int id;
        @SerializedName("name")
        public String name;
        @SerializedName("character")
        public String character;
        @SerializedName("profile_path")
        public String profilePath;
        @SerializedName("order")
        public int order;
    }

    public static class CrewMember {
        @SerializedName("id")
        public int id;
        @SerializedName("name")
        public String name;
        @SerializedName("job")
        public String job;
        @SerializedName("department")
        public String department;
        @SerializedName("profile_path")
        public String profilePath;
    }

    // ---- Getters ----

    public List<Genre> getGenres() { return genres; }

    public int getRuntimeMinutes() {
        if (runtime != null && runtime > 0) return runtime;
        if (episodeRunTime != null && !episodeRunTime.isEmpty()) return episodeRunTime.get(0);
        return 0;
    }

    public String getStatus() { return status; }

    public Credits getCredits() { return credits; }

    /** 返回导演名（crew 中 job=Director 的第一个） */
    public String getDirector() {
        if (credits == null || credits.crew == null) return "";
        for (CrewMember m : credits.crew) {
            if ("Director".equalsIgnoreCase(m.job)) return m.name;
        }
        return "";
    }

    /** 返回前 N 位演员（取 cast 列表） */
    public List<CastMember> getTopCast(int n) {
        if (credits == null || credits.cast == null) return java.util.Collections.emptyList();
        return credits.cast.subList(0, Math.min(n, credits.cast.size()));
    }

    /** 拼接类型标签，逗号分隔 */
    public String getGenreLabel() {
        if (genres == null || genres.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < genres.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(genres.get(i).name);
        }
        return sb.toString();
    }

    public Images getImages() { return images; }

    public List<TmdbMedia> getRecommendations() {
        if (recommendations != null && recommendations.getResults() != null && !recommendations.getResults().isEmpty()) {
            return recommendations.getResults();
        }
        if (similar != null && similar.getResults() != null && !similar.getResults().isEmpty()) {
            return similar.getResults();
        }
        return java.util.Collections.emptyList();
    }

    /**
     * 筛选核心主创团队（导演、编剧、制片人等），按人去重
     */
    public List<CrewMember> getKeyCrew() {
        if (credits == null || credits.crew == null) return java.util.Collections.emptyList();
        List<CrewMember> list = new ArrayList<>();
        java.util.Set<Integer> seenIds = new java.util.HashSet<>();
        String[] priorityJobs = new String[]{"Director", "Screenplay", "Writer", "Producer", "Executive Producer"};
        for (String targetJob : priorityJobs) {
            for (CrewMember member : credits.crew) {
                if (member == null || seenIds.contains(member.id)) continue;
                if (targetJob.equalsIgnoreCase(member.job)) {
                    seenIds.add(member.id);
                    list.add(member);
                }
            }
        }
        if (list.size() < 3) {
            for (CrewMember member : credits.crew) {
                if (member == null || seenIds.contains(member.id)) continue;
                if ("Directing".equalsIgnoreCase(member.department) || "Writing".equalsIgnoreCase(member.department) || "Production".equalsIgnoreCase(member.department)) {
                    seenIds.add(member.id);
                    list.add(member);
                }
            }
        }
        return list;
    }

    /**
     * 获取供大画报轮播展示的超清原画图片 URL 列表。
     * 针对移动端竖屏展示，优先采用竖版高清海报（original 尺寸保证顶级清晰度），辅以高清剧照壁纸。
     */
    public List<String> getAllBackdropUrls() {
        List<String> list = new ArrayList<>();
        // 1. 优先竖版主海报（original 原图无损超清）
        if (getPosterPath() != null && !getPosterPath().isEmpty()) {
            list.add(TmdbApi.imageUrl(getPosterPath(), "original"));
        }
        // 2. 补充其他官方竖版海报
        if (images != null && images.posters != null) {
            for (ImageItem item : images.posters) {
                if (item != null && item.filePath != null && !item.filePath.isEmpty()) {
                    String url = TmdbApi.imageUrl(item.filePath, "original");
                    if (!list.contains(url)) {
                        list.add(url);
                    }
                }
                if (list.size() >= 5) break;
            }
        }
        // 3. 补充横版高清背景/壁纸
        if (getBackdropPath() != null && !getBackdropPath().isEmpty()) {
            String url = TmdbApi.imageUrl(getBackdropPath(), "original");
            if (!list.contains(url)) {
                list.add(url);
            }
        }
        if (images != null && images.backdrops != null) {
            for (ImageItem item : images.backdrops) {
                if (item != null && item.filePath != null && !item.filePath.isEmpty()) {
                    String url = TmdbApi.imageUrl(item.filePath, "original");
                    if (!list.contains(url)) {
                        list.add(url);
                    }
                }
                if (list.size() >= 8) break;
            }
        }
        return list;
    }
}
