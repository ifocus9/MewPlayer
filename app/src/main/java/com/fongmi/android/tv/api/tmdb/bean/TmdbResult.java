package com.fongmi.android.tv.api.tmdb.bean;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** TMDB search/multi 或 discover 接口返回的分页根对象 */
public class TmdbResult {

    @SerializedName("results")
    private List<TmdbMedia> results;

    @SerializedName("total_results")
    private int totalResults;

    @SerializedName("total_pages")
    private int totalPages;

    public List<TmdbMedia> getResults() { return results; }
    public int getTotalResults() { return totalResults; }
    public int getTotalPages() { return totalPages; }
}
