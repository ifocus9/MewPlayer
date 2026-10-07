package com.fongmi.android.tv.bean;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.web.page.WebPageManager;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Backup {

    @SerializedName("site")
    private List<Site> site;
    @SerializedName("live")
    private List<Live> live;
    @SerializedName("config")
    private List<Config> config;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("track")
    private List<Track> track;
    @SerializedName("device")
    private List<Device> device;
    @SerializedName("prefers")
    private Map<String, ?> prefers;

    public static Backup create() {
        Backup backup = new Backup();
        backup.setPrefers(Prefers.getPrefers().getAll());
        backup.setSite(AppDatabase.get().getSiteDao().findAll());
        backup.setLive(AppDatabase.get().getLiveDao().findAll());
        backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        backup.setTrack(AppDatabase.get().getTrackDao().findAll());
        backup.setDevice(AppDatabase.get().getDeviceDao().findAll());
        return backup;
    }

    public static Backup objectFrom(String json) {
        try {
            Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
            Backup backup = gson.fromJson(json, Backup.class);
            return backup == null ? new Backup() : backup;
        } catch (Exception e) {
            return new Backup();
        }
    }

    public void restore() {
        restore(true);
    }

    public void restore(boolean preserveMissingWebHomePrefs) {
        AppDatabase.get().clearAllTables();
        AppDatabase.get().getSiteDao().insertOrUpdate(getSite());
        AppDatabase.get().getLiveDao().insertOrUpdate(getLive());
        AppDatabase.get().getConfigDao().insertOrUpdate(getConfig());
        AppDatabase.get().getHistoryDao().insertOrUpdate(getHistory());
        AppDatabase.get().getTrackDao().insertOrUpdate(getTrack());
        AppDatabase.get().getDeviceDao().insertOrUpdate(getDevice());
        restorePrefers(getPrefers(), true, preserveMissingWebHomePrefs);
    }

    private static void restorePrefers(Map<String, ?> values, boolean clear, boolean preserveMissingWebHomePrefs) {
        Map<String, Object> preserved = new HashMap<>();
        if (clear && preserveMissingWebHomePrefs) {
            for (Map.Entry<String, ?> entry : Prefers.getPrefers().getAll().entrySet()) {
                boolean webHomePref = WebPageManager.isPref(entry.getKey());
                if (webHomePref && !values.containsKey(entry.getKey())) preserved.put(entry.getKey(), entry.getValue());
            }
        }
        SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        if (clear) editor.clear();
        if (containsPlaybackPerformanceProfile(values)) {
            editor.remove("playback_performance_profile_auto_light_v1");
        }
        putPrefers(editor, preserved);
        putPrefers(editor, values);
        editor.commit();
    }

    private static boolean containsPlaybackPerformanceProfile(
            Map<String, ?> values) {
        return values.containsKey("playback_performance_profile")
                || values.containsKey("perf_exo_profile")
                || values.containsKey("perf_mpv_profile")
                || values.containsKey("perf_ijk_profile");
    }

    private static void putPrefers(SharedPreferences.Editor editor, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Number) {
                Number number = (Number) value;
                if (number.toString().contains(".")) editor.putFloat(entry.getKey(), number.floatValue());
                else editor.putInt(entry.getKey(), number.intValue());
            }
        }
    }

    public List<Site> getSite() {
        return site == null ? Collections.emptyList() : site;
    }

    public void setSite(List<Site> site) {
        this.site = site;
    }

    public List<Live> getLive() {
        return live == null ? Collections.emptyList() : live;
    }

    public void setLive(List<Live> live) {
        this.live = live;
    }

    public List<Config> getConfig() {
        return config == null ? Collections.emptyList() : config;
    }

    public void setConfig(List<Config> config) {
        this.config = config;
    }

    public List<History> getHistory() {
        return history == null ? Collections.emptyList() : history;
    }

    public void setHistory(List<History> history) {
        this.history = history;
    }

    public List<Track> getTrack() {
        return track == null ? Collections.emptyList() : track;
    }

    public void setTrack(List<Track> track) {
        this.track = track;
    }

    public List<Device> getDevice() {
        return device == null ? Collections.emptyList() : device;
    }

    public void setDevice(List<Device> device) {
        this.device = device;
    }

    public Map<String, ?> getPrefers() {
        return prefers == null ? new HashMap<>() : prefers;
    }

    public void setPrefers(Map<String, ?> prefers) {
        this.prefers = prefers;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
