package com.fongmi.android.tv.api.tmdb.bean;

import com.google.gson.annotations.SerializedName;

/** TMDB search/multi 结果列表中的一条媒体记录（movie 或 tv） */
public class TmdbMedia {

    @SerializedName("id")
    private int id;

    /** "movie" 或 "tv" */
    @SerializedName("media_type")
    private String mediaType;

    /** 电影片名 */
    @SerializedName("title")
    private String title;

    /** 剧集片名 */
    @SerializedName("name")
    private String name;

    /** 电影原名 */
    @SerializedName("original_title")
    private String originalTitle;

    /** 剧集原名 */
    @SerializedName("original_name")
    private String originalName;

    @SerializedName("overview")
    private String overview;

    @SerializedName("poster_path")
    private String posterPath;

    @SerializedName("backdrop_path")
    private String backdropPath;

    /** 上映日期（电影） */
    @SerializedName("release_date")
    private String releaseDate;

    /** 首播日期（剧集） */
    @SerializedName("first_air_date")
    private String firstAirDate;

    /** 综合评分（0~10） */
    @SerializedName("vote_average")
    private double voteAverage;

    @SerializedName("vote_count")
    private int voteCount;

    @SerializedName("popularity")
    private double popularity;

    // ---- Getters ----

    public int getId() { return id; }

    public String getMediaType() {
        if (mediaType != null) return mediaType;
        // 详情页接口不带 media_type，根据是否有 title 字段区分
        return title != null ? "movie" : "tv";
    }

    /** 统一片名（中文优先） */
    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (name != null && !name.isEmpty()) return name;
        if (originalTitle != null && !originalTitle.isEmpty()) return originalTitle;
        return originalName != null ? originalName : "";
    }

    public String getOriginalTitle() {
        return originalTitle != null ? originalTitle : originalName;
    }

    public String getOverview() { return overview != null ? overview : ""; }

    public String getPosterPath() { return posterPath; }

    public String getBackdropPath() { return backdropPath; }

    public String getDate() {
        return releaseDate != null ? releaseDate : (firstAirDate != null ? firstAirDate : "");
    }

    public String getYear() {
        String date = getDate();
        if (date != null && date.length() >= 4) return date.substring(0, 4);
        return "";
    }

    public double getVoteAverage() { return voteAverage; }
    public int getVoteCount() { return voteCount; }
    public double getPopularity() { return popularity; }
}
