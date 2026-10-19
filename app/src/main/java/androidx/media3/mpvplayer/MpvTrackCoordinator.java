package androidx.media3.mpvplayer;

import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.player.AudioPlaybackDiagnostics;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.player.iso.IsoSessionManager;
import com.fongmi.android.tv.player.mpv.MpvDirectAudioPolicy;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 负责 MPV 播放器的音视频轨道与章节协调管理：
 * 1. 轨道快照读取、JSON与原生属性解析、Dolby Vision Profile/Level 检测
 * 2. 轨道刷新调度（防抖、合并计数、重启动态优先级）
 * 3. 智能音频策略（多声道透传降级、AAC优先选择）
 * 4. 主字幕与次字幕（双字幕）选择和状态跟踪
 * 5. 章节（Chapters / Editions）解析与切换
 */
public final class MpvTrackCoordinator {

    private static final String TAG = "MpvTrackCoordinator";
    private static final long POST_RESTART_TRACK_REFRESH_DELAY_MS = 200L;

    public interface Host {
        boolean isInitialized();
        boolean isReleased();
        MpvPlayerConfig getConfig();
        Handler getMainHandler();
        String getCurrentIsoUri();
        String getPlaybackTraceId();
        boolean isPlaybackRestarted();
        boolean isFileLoaded();
        long getFileLoadedAtElapsedRealtimeMs();
        VideoSize getVideoSize();
        float getVideoFrameRate();
        String getCachedAudioCodecProfile();
        String getActiveAudioSpdif();
        String getActiveVideoOutputVo();
        boolean shouldCollectDebugDetails();

        void syncOsdSurfaceRequirementFromMpv();
        void invalidateState();

        String propertyStringOrInt(String property);
        String stringProperty(String property, String fallback);
        int intProperty(String property, int fallback);
        boolean booleanProperty(String property, boolean fallback);
        double doubleProperty(String property, double fallback);

        void safeSetPropertyString(String name, String value);
        void safeSetPropertyInt(String name, int value);
        void appendSubtitleDiagnostic(String text);
    }

    private final Host host;
    private final Runnable trackRefreshRunnable = this::runScheduledTrackRefresh;

    private Tracks currentTracks = Tracks.EMPTY;
    private List<MediaChapter> currentChapters = List.of();
    private List<MediaEdition> currentEditions = List.of();
    private int currentChapter = C.INDEX_UNSET;

    private MpvPlayer.VideoTrackDiagnostics selectedVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
    private MpvPlayer.VideoTrackDiagnostics availableVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
    private AudioPlaybackDiagnostics.Track selectedAudioTrackDiagnostics = AudioPlaybackDiagnostics.Track.empty();
    private AudioPlaybackDiagnostics.Track automaticAudioOriginalTrack = AudioPlaybackDiagnostics.Track.empty();
    private AudioPlaybackDiagnostics.Track automaticAudioActiveTrack = AudioPlaybackDiagnostics.Track.empty();
    private String automaticAudioDowngradeReason = "";

    private boolean trackRefreshScheduled;
    private boolean trackRefreshPrioritized;
    private int trackRefreshCoalescedEvents;
    private long trackRefreshFirstScheduledAtMs;
    private String trackRefreshLastReason;

    private boolean isoTrackListDumped;
    private boolean preferAacApplied;
    private boolean directAudioApplied;
    private boolean audioTrackManuallySelected;
    private int cachedSelectedHlsBitrate;

    public MpvTrackCoordinator(Host host) {
        this.host = host;
    }

    public synchronized Tracks getCurrentTracks() {
        return currentTracks;
    }

    public synchronized List<MediaChapter> getCurrentChapters() {
        return currentChapters;
    }

    public synchronized List<MediaEdition> getCurrentEditions() {
        return currentEditions;
    }

    public synchronized int getCurrentChapter() {
        return currentChapter;
    }

    public synchronized MpvPlayer.VideoTrackDiagnostics getSelectedVideoTrackDiagnostics() {
        return selectedVideoTrackDiagnostics;
    }

    public synchronized MpvPlayer.VideoTrackDiagnostics getAvailableVideoTrackDiagnostics() {
        return availableVideoTrackDiagnostics;
    }

    public synchronized AudioPlaybackDiagnostics.Track getSelectedAudioTrackDiagnostics() {
        return selectedAudioTrackDiagnostics;
    }

    public synchronized AudioPlaybackDiagnostics.Track getAutomaticAudioOriginalTrack() {
        return automaticAudioOriginalTrack;
    }

    public synchronized AudioPlaybackDiagnostics.Track getAutomaticAudioActiveTrack() {
        return automaticAudioActiveTrack;
    }

    public synchronized String getAutomaticAudioDowngradeReason() {
        return automaticAudioDowngradeReason;
    }

    public synchronized int getCachedSelectedHlsBitrate() {
        return cachedSelectedHlsBitrate;
    }

    public synchronized void resetTrackSelectionFlags() {
        preferAacApplied = false;
        directAudioApplied = false;
        audioTrackManuallySelected = false;
    }

    public synchronized void reset() {
        cancelScheduledTrackRefresh();
        resetTrackDiagnostics();
    }

    public synchronized void resetTrackDiagnostics() {
        currentTracks = Tracks.EMPTY;
        selectedVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
        availableVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
        resetAudioPlaybackDiagnostics();
        cachedSelectedHlsBitrate = 0;
        currentChapters = List.of();
        currentEditions = List.of();
        currentChapter = C.INDEX_UNSET;
        isoTrackListDumped = false;
        preferAacApplied = false;
        directAudioApplied = false;
        audioTrackManuallySelected = false;
    }

    public synchronized void resetAudioPlaybackDiagnostics() {
        selectedAudioTrackDiagnostics = AudioPlaybackDiagnostics.Track.empty();
        clearAutomaticAudioDowngrade();
    }

    public synchronized void clearAutomaticAudioDowngrade() {
        automaticAudioOriginalTrack = AudioPlaybackDiagnostics.Track.empty();
        automaticAudioActiveTrack = AudioPlaybackDiagnostics.Track.empty();
        automaticAudioDowngradeReason = "";
    }

    public synchronized void scheduleTrackRefresh(String reason) {
        if (host.isReleased()) return;
        long nowMs = SystemClock.elapsedRealtime();
        if (!trackRefreshScheduled) {
            trackRefreshFirstScheduledAtMs = nowMs;
            trackRefreshCoalescedEvents = 0;
        }
        trackRefreshScheduled = true;
        trackRefreshCoalescedEvents++;
        trackRefreshLastReason = reason;
        boolean prioritizePlaybackRestart = host.getConfig().deferStartupTrackRefresh()
                && "event=playback-restart".equals(reason);
        if (trackRefreshPrioritized && !prioritizePlaybackRestart) return;
        if (prioritizePlaybackRestart) trackRefreshPrioritized = true;
        host.getMainHandler().removeCallbacks(trackRefreshRunnable);
        long delayMs = prioritizePlaybackRestart
                ? POST_RESTART_TRACK_REFRESH_DELAY_MS
                : MpvTrackRefreshPolicy.delayMs(
                host.isFileLoaded(), host.getFileLoadedAtElapsedRealtimeMs(), nowMs);
        host.getMainHandler().postDelayed(trackRefreshRunnable, delayMs);
    }

    public synchronized void cancelScheduledTrackRefresh() {
        trackRefreshScheduled = false;
        host.getMainHandler().removeCallbacks(trackRefreshRunnable);
        resetTrackRefreshDiagnostics();
    }

    private synchronized void resetTrackRefreshDiagnostics() {
        trackRefreshPrioritized = false;
        trackRefreshCoalescedEvents = 0;
        trackRefreshFirstScheduledAtMs = 0;
        trackRefreshLastReason = null;
    }

    private synchronized void runScheduledTrackRefresh() {
        int coalescedEvents = trackRefreshCoalescedEvents;
        long spanMs = Math.max(0, SystemClock.elapsedRealtime() - trackRefreshFirstScheduledAtMs);
        String lastReason = trackRefreshLastReason;
        trackRefreshScheduled = false;
        resetTrackRefreshDiagnostics();
        if (host.isReleased()) return;
        if (host.shouldCollectDebugDetails()) {
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                    "track refresh run coalesced=%d span=%dms last=%s startup=%s",
                    coalescedEvents, spanMs, lastReason,
                    MpvTrackRefreshPolicy.isStartupWindow(host.isFileLoaded(),
                            host.getFileLoadedAtElapsedRealtimeMs(),
                            SystemClock.elapsedRealtime()));
        }
        if (host.getConfig().deferStartupTrackRefresh() && !host.isPlaybackRestarted()) {
            if (host.shouldCollectDebugDetails()) {
                PlaybackTrace.log("mpv", host.getPlaybackTraceId(),
                        "track refresh deferred until playback restart");
            }
            return;
        }
        refreshTracks();
        if (host.getConfig().deferStartupTrackRefresh()) refreshChapters();
        host.invalidateState();
    }

    public synchronized void refreshTracks() {
        if (trackRefreshScheduled) cancelScheduledTrackRefresh();
        if (host.getConfig().deferStartupTrackRefresh() && !host.isPlaybackRestarted()) return;
        if (!host.isInitialized()) {
            currentTracks = Tracks.EMPTY;
            selectedVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            availableVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            cachedSelectedHlsBitrate = 0;
            return;
        }
        host.syncOsdSurfaceRequirementFromMpv();
        int count = Math.max(0, host.intProperty("track-list/count", 0));
        if (count <= 0) {
            currentTracks = Tracks.EMPTY;
            selectedVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            availableVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            cachedSelectedHlsBitrate = 0;
            return;
        }
        if (!isoTrackListDumped && !TextUtils.isEmpty(host.getCurrentIsoUri())) {
            isoTrackListDumped = true;
            PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "async track snapshot count=%d", count);
        }
        List<TrackInfo> infos = new ArrayList<>();
        int audioIndex = 0;
        int subtitleIndex = 0;
        for (int i = 0; i < count; i++) {
            String mpvType = host.stringProperty("track-list/" + i + "/type", "");
            int typeIndex = "audio".equals(mpvType) ? audioIndex++ : "sub".equals(mpvType) ? subtitleIndex++ : C.INDEX_UNSET;
            TrackInfo info = readTrackInfo(i, typeIndex);
            if (info == null) continue;
            infos.add(info);
        }
        if (infos.isEmpty()) {
            currentTracks = Tracks.EMPTY;
            selectedVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            availableVideoTrackDiagnostics = MpvPlayer.VideoTrackDiagnostics.empty();
            cachedSelectedHlsBitrate = 0;
            return;
        }
        String selectedVideo = selectedTrackId(C.TRACK_TYPE_VIDEO);
        String selectedAudio = selectedTrackId(C.TRACK_TYPE_AUDIO);
        String selectedText = selectedTrackId(C.TRACK_TYPE_TEXT);
        TrackInfo selectedVideoInfo = findTrack(infos, C.TRACK_TYPE_VIDEO, selectedVideo);
        if (selectedVideoInfo == null && isAutoOrUnknownTrackChoice(selectedVideo)) {
            selectedVideoInfo = firstTrack(infos, C.TRACK_TYPE_VIDEO);
        }
        TrackInfo selectedAudioInfo = findTrack(infos, C.TRACK_TYPE_AUDIO, selectedAudio);
        selectedAudioTrackDiagnostics = selectedAudioInfo == null
                ? AudioPlaybackDiagnostics.Track.empty()
                : selectedAudioInfo.toAudioPlaybackTrack(host.getCachedAudioCodecProfile());
        TrackInfo firstVideoInfo = firstTrack(infos, C.TRACK_TYPE_VIDEO);
        availableVideoTrackDiagnostics = firstVideoInfo == null
                ? MpvPlayer.VideoTrackDiagnostics.empty()
                : firstVideoInfo.toVideoTrackDiagnostics();
        selectedVideoTrackDiagnostics = selectedVideoInfo == null
                ? MpvPlayer.VideoTrackDiagnostics.empty()
                : selectedVideoInfo.toVideoTrackDiagnostics();
        cachedSelectedHlsBitrate = selectedVideoInfo != null && selectedVideoInfo.hlsBitrate > 0
                ? selectedVideoInfo.hlsBitrate
                : selectedAudioInfo != null && selectedAudioInfo.hlsBitrate > 0
                ? selectedAudioInfo.hlsBitrate : 0;
        boolean hasSelectedVideo = hasSelectedTrack(infos, C.TRACK_TYPE_VIDEO, selectedVideo);
        boolean hasSelectedAudio = hasSelectedTrack(infos, C.TRACK_TYPE_AUDIO, selectedAudio);
        boolean hasSelectedText = hasSelectedTrack(infos, C.TRACK_TYPE_TEXT, selectedText);
        boolean autoVideoFallbackUsed = false;
        boolean autoAudioFallbackUsed = false;
        boolean autoTextFallbackUsed = false;
        List<Tracks.Group> groups = new ArrayList<>();
        for (TrackInfo info : infos) {
            boolean selected = isTrackSelected(info, trackIdForType(info.type, selectedVideo, selectedAudio, selectedText));
            if (!selected && info.type == C.TRACK_TYPE_VIDEO && !hasSelectedVideo && isAutoOrUnknownTrackChoice(selectedVideo) && !autoVideoFallbackUsed) {
                selected = true;
                autoVideoFallbackUsed = true;
            } else if (!selected && info.type == C.TRACK_TYPE_AUDIO && !hasSelectedAudio && isAutoOrUnknownTrackChoice(selectedAudio) && !autoAudioFallbackUsed) {
                selected = true;
                autoAudioFallbackUsed = true;
            } else if (!selected && info.type == C.TRACK_TYPE_TEXT && !hasSelectedText && isAutoTrackChoice(selectedText) && !autoTextFallbackUsed) {
                selected = true;
                autoTextFallbackUsed = true;
            }
            Format format = info.toFormat();
            TrackGroup mediaGroup = new TrackGroup("mpv:" + info.type + ":" + info.id, format);
            groups.add(new Tracks.Group(mediaGroup, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        if (!maybeSelectPreferredDirectAudio(infos, selectedAudio)) {
            maybeSelectPreferredAac(infos, selectedAudio);
        }
        logTrackSnapshot(infos, selectedVideo, selectedAudio, selectedText, currentTracks);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("mpv", "tracks refreshed count=%d groups=%d", count, groups.size());
    }

    public static String mpvTrackProperty(int type) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> "vid";
            case C.TRACK_TYPE_AUDIO -> "aid";
            case C.TRACK_TYPE_TEXT -> "sid";
            default -> null;
        };
    }

    public void setMpvTrack(int type, String mpvId) {
        if (!host.isInitialized()) return;
        String property = mpvTrackProperty(type);
        if (property == null) return;
        String current = host.propertyStringOrInt(property);
        if (MpvInitialTrackSelectionPolicy.isSameTrackSelection(current, mpvId)) {
            SpiderDebug.log("mpv",
                    "select track skipped type=%d property=%s id=%s reason=already-selected",
                    type, property, mpvId);
            return;
        }
        try {
            host.safeSetPropertyString(property, mpvId);
            if (type == C.TRACK_TYPE_TEXT) host.syncOsdSurfaceRequirementFromMpv();
            Log.d(TAG, "set track property=" + property + " requested=" + mpvId + " actual=" + host.propertyStringOrInt(property));
            host.appendSubtitleDiagnostic("set-track property=" + property + " requested=" + mpvId + " actual=" + host.propertyStringOrInt(property));
        } catch (Throwable e) {
            Log.e(TAG, "set track failed property=" + property + " requested=" + mpvId, e);
            host.appendSubtitleDiagnostic("set-track-failed property=" + property + " requested=" + mpvId + " error=" + e);
        }
        SpiderDebug.log("mpv", "select track type=%d property=%s id=%s", type, property, mpvId);
    }

    public synchronized void resetTrackSelection() {
        audioTrackManuallySelected = true;
        preferAacApplied = true;
        clearAutomaticAudioDowngrade();
        setMpvTrack(C.TRACK_TYPE_VIDEO, "auto");
        setMpvTrack(C.TRACK_TYPE_AUDIO, "auto");
        setMpvTrack(C.TRACK_TYPE_TEXT, "auto");
        setSecondarySubtitleTrackSelection("no");
        refreshTracks();
        host.invalidateState();
    }

    public synchronized void setTrackSelection(int type, String mpvId) {
        if (TextUtils.isEmpty(mpvId)) return;
        if (type == C.TRACK_TYPE_AUDIO) {
            audioTrackManuallySelected = true;
            preferAacApplied = true;
            clearAutomaticAudioDowngrade();
        }
        setMpvTrack(type, mpvId);
        refreshTracks();
        host.invalidateState();
    }

    public synchronized void restoreVideoTrackSelection() {
        if (!host.isInitialized() || host.getConfig().deferStartupTrackRefresh() && !host.isPlaybackRestarted()) return;
        int count = Math.max(0, host.intProperty("track-list/count", 0));
        for (int index = 0; index < count; index++) {
            TrackInfo info = readTrackInfo(index, C.INDEX_UNSET);
            if (info == null || info.type != C.TRACK_TYPE_VIDEO) continue;
            setMpvTrack(C.TRACK_TYPE_VIDEO, info.id);
            SpiderDebug.log("mpv", "restore video track id=%s codec=%s", info.id, info.codec);
            refreshTracks();
            host.invalidateState();
            return;
        }
        SpiderDebug.log("mpv", "restore video track skipped no video track");
    }

    public synchronized void setSecondarySubtitleTrackSelection(String mpvId) {
        if (TextUtils.isEmpty(mpvId) || !host.isInitialized()) return;
        host.safeSetPropertyString("secondary-sid", mpvId);
        host.syncOsdSurfaceRequirementFromMpv();
        SpiderDebug.log("mpv", "select secondary subtitle id=%s", mpvId);
        refreshTracks();
        host.invalidateState();
    }

    public synchronized boolean isSecondarySubtitleSelected(String mpvId) {
        if (TextUtils.isEmpty(mpvId)) return false;
        String selected = secondarySubtitleTrackId();
        if (TextUtils.isEmpty(selected) || isAutoTrackChoice(selected) || isDisabledTrackChoice(selected)) return false;
        return selected.equals(mpvId) || normalizeTrackId(selected).equals(normalizeTrackId(mpvId));
    }

    public synchronized boolean selectChapter(MediaChapter chapter) {
        if (chapter == null || chapter.index < 0 || chapter.index >= currentChapters.size()) return false;
        currentChapter = chapter.index;
        if (host.isInitialized()) host.safeSetPropertyInt("chapter", chapter.index);
        refreshChapters();
        host.invalidateState();
        return true;
    }

    public synchronized boolean selectEdition(MediaEdition edition) {
        if (edition == null || edition.index < 0 || edition.index >= currentEditions.size()) return false;
        currentChapter = edition.index;
        if (host.isInitialized()) host.safeSetPropertyInt("chapter", edition.index);
        refreshChapters();
        host.invalidateState();
        return true;
    }

    public synchronized void refreshChapters() {
        if (host.isReleased() || !host.isInitialized() || shouldDeferStartupMetadataRefresh()) return;
        currentChapter = host.intProperty("chapter", currentChapter);
        parseAndUpdateChapters(host.stringProperty("chapter-list", ""));
    }

    public synchronized void handleChapterListProperty(@Nullable Object value) {
        if (shouldDeferStartupMetadataRefresh()) return;
        parseAndUpdateChapters(value instanceof String string ? string : "");
    }

    private boolean shouldDeferStartupMetadataRefresh() {
        return host.getConfig().deferStartupTrackRefresh() && !host.isPlaybackRestarted();
    }

    private void parseAndUpdateChapters(String json) {
        if (TextUtils.isEmpty(json)) {
            updateCurrentChapters(List.of(), List.of());
            return;
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("[")) {
            updateCurrentChapters(List.of(), List.of());
            return;
        }
        try {
            JSONArray array = new JSONArray(trimmed);
            List<MediaChapter> chapters = new ArrayList<>();
            List<MediaEdition> editions = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                long timeUs = secondsToUs(item.optDouble("time", 0));
                String label = chapterLabel(i, item.optString("title", null));
                boolean selected = i == currentChapter;
                chapters.add(MediaChapter.chapter(i, timeUs, label, selected));
                editions.add(MediaEdition.edition(i, timeUs, label, selected));
            }
            updateCurrentChapters(chapters, editions);
        } catch (Throwable ignored) {
            updateCurrentChapters(List.of(), List.of());
        }
    }

    private void updateCurrentChapters(List<MediaChapter> chapters, List<MediaEdition> editions) {
        if (chapters == null) chapters = List.of();
        if (editions == null) editions = List.of();
        if (chapters.equals(currentChapters) && editions.equals(currentEditions)) return;
        currentChapters = chapters;
        currentEditions = editions;
        SpiderDebug.log("mpv", "chapters refreshed count=%d selected=%d", chapters.size(), currentChapter);
        host.invalidateState();
    }

    private String chapterLabel(int index, @Nullable String title) {
        title = emptyToNull(title);
        return title == null ? "Chapter " + (index + 1) : title;
    }

    private long secondsToUs(double seconds) {
        if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return 0;
        return Math.round(seconds * 1_000_000.0);
    }

    @Nullable
    private String emptyToNull(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean maybeSelectPreferredDirectAudio(List<TrackInfo> infos, String selectedAudio) {
        if (directAudioApplied || audioTrackManuallySelected || !host.isInitialized()
                || !"mediacodec_embed".equals(host.getActiveVideoOutputVo())) return false;
        TrackInfo selected = findTrack(infos, C.TRACK_TYPE_AUDIO, selectedAudio);
        if (selected == null) return false;
        List<MpvDirectAudioPolicy.Candidate> candidates = new ArrayList<>();
        for (TrackInfo info : infos) {
            if (info.type != C.TRACK_TYPE_AUDIO) continue;
            candidates.add(new MpvDirectAudioPolicy.Candidate(
                    info.id, info.lang, info.codec, info.title, info.channels));
        }
        MpvDirectAudioPolicy.Selection selection = MpvDirectAudioPolicy.select(
                candidates, selected.id, host.getActiveAudioSpdif(),
                host.getConfig().multichannelPcm());
        directAudioApplied = true;
        preferAacApplied = true;
        TrackInfo target = selected;
        for (TrackInfo info : infos) {
            if (info.type == C.TRACK_TYPE_AUDIO && TextUtils.equals(info.id, selection.id())) {
                target = info;
                break;
            }
        }
        if (selection.changed()) {
            automaticAudioOriginalTrack = selected.toAudioPlaybackTrack(host.getCachedAudioCodecProfile());
            automaticAudioActiveTrack = target.toAudioPlaybackTrack("");
            automaticAudioDowngradeReason = selection.reason();
            setMpvTrack(C.TRACK_TYPE_AUDIO, selection.id());
        }
        SpiderDebug.log("mpv", "direct automatic audio selection changed=%s reason=%s previous=%s/%s/%dch/%s selected=%s/%s/%dch/%s",
                selection.changed(), selection.reason(), selected.id, selected.codec,
                selected.channels, selected.lang, target.id, target.codec,
                target.channels, target.lang);
        return true;
    }

    private void maybeSelectPreferredAac(List<TrackInfo> infos, String selectedAudio) {
        if (!PlayerSetting.isPreferAAC(PlayerSetting.MPV) || preferAacApplied
                || directAudioApplied || audioTrackManuallySelected || !host.isInitialized()) return;
        TrackInfo selected = findTrack(infos, C.TRACK_TYPE_AUDIO, selectedAudio);
        if (selected != null && isAacTrack(selected)) {
            preferAacApplied = true;
            return;
        }
        TrackInfo preferred = findPreferredAacTrack(infos, selected);
        if (preferred == null || TextUtils.equals(preferred.id, selected == null ? "" : selected.id)) return;
        preferAacApplied = true;
        setMpvTrack(C.TRACK_TYPE_AUDIO, preferred.id);
        SpiderDebug.log("mpv", "prefer AAC audio track selected id=%s codec=%s previous=%s",
                preferred.id, preferred.codec, selected == null ? selectedAudio : selected.id);
    }

    @Nullable
    private TrackInfo findTrack(List<TrackInfo> infos, int type, String selectedId) {
        for (TrackInfo info : infos) if (info.type == type && isTrackSelected(info, selectedId)) return info;
        return null;
    }

    @Nullable
    private TrackInfo firstTrack(List<TrackInfo> infos, int type) {
        for (TrackInfo info : infos) if (info.type == type) return info;
        return null;
    }

    @Nullable
    private TrackInfo findPreferredAacTrack(List<TrackInfo> infos, @Nullable TrackInfo selected) {
        TrackInfo first = null;
        for (TrackInfo info : infos) {
            if (info.type != C.TRACK_TYPE_AUDIO || !isAacTrack(info)) continue;
            if (first == null) first = info;
            if (selected != null && !TextUtils.isEmpty(selected.lang) && TextUtils.equals(selected.lang, info.lang)) return info;
        }
        return first;
    }

    private boolean isAacTrack(TrackInfo info) {
        return info != null && sampleMimeType(info).equals(MimeTypes.AUDIO_AAC);
    }

    private boolean isTrackSelected(TrackInfo info, String selectedId) {
        if (info.selected) return true;
        if (TextUtils.isEmpty(selectedId) || isAutoTrackChoice(selectedId) || isDisabledTrackChoice(selectedId)) return false;
        return selectedId.equals(info.id) || normalizeTrackId(selectedId).equals(normalizeTrackId(info.id));
    }

    private boolean hasSelectedTrack(List<TrackInfo> infos, int type, String selectedId) {
        for (TrackInfo info : infos) {
            if (info.type == type && isTrackSelected(info, selectedId)) return true;
        }
        return false;
    }

    private String selectedTrackId(int type) {
        String property = mpvTrackProperty(type);
        if (property == null) return "";
        String selected = host.propertyStringOrInt(property);
        if (isDisabledTrackChoice(selected)) return selected;
        String current = currentTrackId(type);
        return !TextUtils.isEmpty(current) ? current : selected;
    }

    public String currentTrackId(int type) {
        String property = switch (type) {
            case C.TRACK_TYPE_VIDEO -> "current-tracks/video/id";
            case C.TRACK_TYPE_AUDIO -> "current-tracks/audio/id";
            case C.TRACK_TYPE_TEXT -> "current-tracks/sub/id";
            default -> null;
        };
        return property == null ? "" : host.propertyStringOrInt(property);
    }

    private String secondarySubtitleTrackId() {
        String selected = host.propertyStringOrInt("secondary-sid");
        if (isDisabledTrackChoice(selected)) return selected;
        String current = host.propertyStringOrInt("current-tracks/sub2/id");
        if (!TextUtils.isEmpty(current)) return current;
        return selected;
    }

    private String trackIdForType(int type, String selectedVideo, String selectedAudio, String selectedText) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> selectedVideo;
            case C.TRACK_TYPE_AUDIO -> selectedAudio;
            case C.TRACK_TYPE_TEXT -> selectedText;
            default -> "";
        };
    }

    private boolean isAutoTrackChoice(String value) {
        return "auto".equalsIgnoreCase(value);
    }

    private boolean isAutoOrUnknownTrackChoice(String value) {
        return TextUtils.isEmpty(value) || isAutoTrackChoice(value);
    }

    static boolean isDisabledTrackChoice(String value) {
        return "no".equalsIgnoreCase(value);
    }

    private String normalizeTrackId(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.startsWith("0") && normalized.length() > 1) normalized = normalized.substring(1);
        return normalized;
    }


    @Nullable
    private TrackInfo readTrackInfo(int index, int typeIndex) {
        String prefix = "track-list/" + index + "/";
        String mpvType = host.stringProperty(prefix + "type", "");
        int type = mediaTrackType(mpvType);
        if (type == C.TRACK_TYPE_UNKNOWN) return null;
        if (type == C.TRACK_TYPE_VIDEO && host.booleanProperty(prefix + "albumart", false)) return null;
        String id = host.stringProperty(prefix + "id", "");
        if (TextUtils.isEmpty(id)) id = String.valueOf(host.intProperty(prefix + "id", index + 1));
        String title = host.stringProperty(prefix + "title", "");
        String lang = host.stringProperty(prefix + "lang", "");
        int demuxId = host.intProperty(prefix + "demux-id", C.INDEX_UNSET);
        int srcId = host.intProperty(prefix + "src-id", C.INDEX_UNSET);
        if (TextUtils.isEmpty(lang) && typeIndex >= 0 && !TextUtils.isEmpty(host.getCurrentIsoUri())) {
            int isoTrackType = type == C.TRACK_TYPE_AUDIO ? 1 : type == C.TRACK_TYPE_TEXT ? 2 : 0;
            if (isoTrackType != 0) {
                int isoTrackId = demuxId > 0 ? demuxId : srcId;
                lang = IsoSessionManager.getTrackLanguage(IsoSessionManager.parseId(host.getCurrentIsoUri()), isoTrackType, isoTrackId, typeIndex);
            }
        }
        String codec = host.stringProperty(prefix + "codec", "");
        String decoder = host.stringProperty(prefix + "decoder", "");
        int dolbyVisionProfile = host.intProperty(prefix + "dolby-vision-profile", C.INDEX_UNSET);
        int dolbyVisionLevel = host.intProperty(prefix + "dolby-vision-level", C.INDEX_UNSET);
        int sourceDolbyVisionProfile = host.intProperty(prefix + "source-dolby-vision-profile", C.INDEX_UNSET);
        int sourceDolbyVisionLevel = host.intProperty(prefix + "source-dolby-vision-level", C.INDEX_UNSET);
        boolean selected = host.booleanProperty(prefix + "selected", false);
        int width = host.intProperty(prefix + "demux-w", C.LENGTH_UNSET);
        int height = host.intProperty(prefix + "demux-h", C.LENGTH_UNSET);
        float frameRate = type == C.TRACK_TYPE_VIDEO ? host.getVideoFrameRate() : C.RATE_UNSET;
        if (type == C.TRACK_TYPE_VIDEO) {
            VideoSize vs = host.getVideoSize();
            if (width <= 0) width = vs.width > 0 ? vs.width : host.intProperty("width", C.LENGTH_UNSET);
            if (height <= 0) height = vs.height > 0 ? vs.height : host.intProperty("height", C.LENGTH_UNSET);
        }
        int sampleRate = host.intProperty(prefix + "demux-samplerate", C.RATE_UNSET_INT);
        int channels = host.intProperty(prefix + "demux-channel-count", C.LENGTH_UNSET);
        int bitrate = host.intProperty(prefix + "demux-bitrate", C.LENGTH_UNSET);
        if (bitrate <= 0 && type == C.TRACK_TYPE_VIDEO) bitrate = host.intProperty("video-bitrate", C.LENGTH_UNSET);
        if (bitrate <= 0 && type == C.TRACK_TYPE_AUDIO) bitrate = host.intProperty("audio-bitrate", C.LENGTH_UNSET);
        int hlsBitrate = host.intProperty(prefix + "hls-bitrate", C.LENGTH_UNSET);
        ColorInfo colorInfo = type == C.TRACK_TYPE_VIDEO ? videoColorInfo() : null;
        return new TrackInfo(type, id, demuxId, srcId, title, lang, codec,
                decoder, dolbyVisionProfile, dolbyVisionLevel,
                sourceDolbyVisionProfile, sourceDolbyVisionLevel,
                selected, width, height, frameRate, sampleRate, channels,
                bitrate, hlsBitrate, colorInfo);
    }

    @Nullable
    private ColorInfo videoColorInfo() {
        int colorSpace = mpvColorSpace();
        int colorRange = mpvColorRange();
        int colorTransfer = mpvColorTransfer();
        if (colorSpace == C.LENGTH_UNSET && colorRange == C.LENGTH_UNSET && colorTransfer == C.LENGTH_UNSET) return null;
        ColorInfo.Builder builder = new ColorInfo.Builder();
        if (colorSpace != C.LENGTH_UNSET) builder.setColorSpace(colorSpace);
        if (colorRange != C.LENGTH_UNSET) builder.setColorRange(colorRange);
        if (colorTransfer != C.LENGTH_UNSET) builder.setColorTransfer(colorTransfer);
        return builder.build();
    }

    private int mpvColorSpace() {
        String primaries = lowerProperty("video-params/primaries");
        String matrix = lowerProperty("video-params/colormatrix");
        String value = !TextUtils.isEmpty(primaries) ? primaries : matrix;
        if (value.contains("bt.2020") || value.contains("bt2020") || value.contains("2020")) return C.COLOR_SPACE_BT2020;
        if (value.contains("bt.709") || value.contains("bt709") || value.contains("709")) return C.COLOR_SPACE_BT709;
        if (value.contains("bt.601") || value.contains("bt601") || value.contains("601") || value.contains("smpte-170m") || value.contains("smpte170m")) return C.COLOR_SPACE_BT601;
        return C.LENGTH_UNSET;
    }

    private int mpvColorRange() {
        String value = lowerProperty("video-params/colorlevels");
        if (value.contains("full") || value.contains("pc")) return C.COLOR_RANGE_FULL;
        if (value.contains("limited") || value.contains("tv")) return C.COLOR_RANGE_LIMITED;
        return C.LENGTH_UNSET;
    }

    private int mpvColorTransfer() {
        String value = lowerProperty("video-params/gamma");
        if (value.contains("pq") || value.contains("st.2084") || value.contains("st2084")) return C.COLOR_TRANSFER_ST2084;
        if (value.contains("hlg")) return C.COLOR_TRANSFER_HLG;
        if (value.contains("srgb")) return C.COLOR_TRANSFER_SRGB;
        if (value.contains("linear")) return C.COLOR_TRANSFER_LINEAR;
        if (value.contains("gamma2.2") || value.contains("bt.470m") || value.contains("bt470m")) return C.COLOR_TRANSFER_GAMMA_2_2;
        if (value.contains("bt.1886") || value.contains("bt1886") || value.contains("709") || value.contains("601")) return C.COLOR_TRANSFER_SDR;
        return C.LENGTH_UNSET;
    }

    private String lowerProperty(String property) {
        return host.stringProperty(property, "").toLowerCase(Locale.US);
    }

    private static int mediaTrackType(String mpvType) {
        if ("video".equals(mpvType)) return C.TRACK_TYPE_VIDEO;
        if ("audio".equals(mpvType)) return C.TRACK_TYPE_AUDIO;
        if ("sub".equals(mpvType)) return C.TRACK_TYPE_TEXT;
        return C.TRACK_TYPE_UNKNOWN;
    }

    private String sampleMimeType(TrackInfo info) {
        String codec = info.codec == null ? "" : info.codec.toLowerCase(Locale.US);
        if (info.type == C.TRACK_TYPE_TEXT) {
            if (codec.contains("pgs") || codec.contains("hdmv")) return MimeTypes.APPLICATION_PGS;
            if (codec.contains("dvd") || codec.contains("vobsub")) return MimeTypes.APPLICATION_VOBSUB;
            if (codec.contains("dvb")) return MimeTypes.APPLICATION_DVBSUBS;
            if (codec.contains("ass") || codec.contains("ssa")) return MimeTypes.TEXT_SSA;
            if (codec.contains("webvtt") || codec.contains("vtt")) return MimeTypes.TEXT_VTT;
            if (codec.contains("srt") || codec.contains("subrip")) return MimeTypes.APPLICATION_SUBRIP;
            if (codec.contains("ttml")) return MimeTypes.APPLICATION_TTML;
            return TextUtils.isEmpty(codec) ? MimeTypes.TEXT_UNKNOWN : MimeTypes.BASE_TYPE_TEXT + "/" + codec;
        }
        if (info.type == C.TRACK_TYPE_AUDIO) {
            return MpvAudioMimeTypes.fromCodec(codec);
        }
        if (codec.contains("hevc") || codec.contains("h265")) return MimeTypes.VIDEO_H265;
        if (codec.contains("h264") || codec.contains("avc")) return MimeTypes.VIDEO_H264;
        if (codec.contains("av1")) return MimeTypes.VIDEO_AV1;
        if (codec.contains("vp9")) return MimeTypes.VIDEO_VP9;
        if (codec.contains("vp8")) return MimeTypes.VIDEO_VP8;
        if (codec.contains("mpeg2")) return MimeTypes.VIDEO_MPEG2;
        return MimeTypes.BASE_TYPE_VIDEO + "/" + (TextUtils.isEmpty(codec) ? "unknown" : codec);
    }

    private void logTrackSnapshot(List<TrackInfo> infos, String selectedVideo, String selectedAudio, String selectedText, Tracks tracks) {
        if (!host.shouldCollectDebugDetails()) return;
        String rawVid = host.propertyStringOrInt("vid");
        String rawAid = host.propertyStringOrInt("aid");
        String rawSid = host.propertyStringOrInt("sid");
        String rawSecondarySid = host.propertyStringOrInt("secondary-sid");
        StringBuilder builder = new StringBuilder();
        builder.append("tracks snapshot ");
        builder.append("vid=").append(selectedVideo).append(" aid=").append(selectedAudio).append(" sid=").append(selectedText);
        builder.append(" rawVid=").append(rawVid);
        builder.append(" rawAid=").append(rawAid);
        builder.append(" rawSid=").append(rawSid);
        builder.append(" secondarySid=").append(rawSecondarySid);
        builder.append(" currentVideo=").append(host.propertyStringOrInt("current-tracks/video/id"));
        builder.append(" currentAudio=").append(host.propertyStringOrInt("current-tracks/audio/id"));
        builder.append(" currentSub=").append(isDisabledTrackChoice(rawSid) ? rawSid : host.propertyStringOrInt("current-tracks/sub/id"));
        builder.append(" currentSub2=").append(isDisabledTrackChoice(rawSecondarySid) ? rawSecondarySid : host.propertyStringOrInt("current-tracks/sub2/id"));
        VideoSize vs = host.getVideoSize();
        builder.append(" size=").append(vs.width).append("x").append(vs.height);
        builder.append(" width=").append(host.intProperty("width", C.LENGTH_UNSET));
        builder.append(" height=").append(host.intProperty("height", C.LENGTH_UNSET));
        builder.append(" fps=").append(host.getVideoFrameRate());
        builder.append(" color primaries=").append(host.stringProperty("video-params/primaries", ""));
        builder.append(" gamma=").append(host.stringProperty("video-params/gamma", ""));
        builder.append(" levels=").append(host.stringProperty("video-params/colorlevels", ""));
        builder.append(" matrix=").append(host.stringProperty("video-params/colormatrix", ""));
        for (int i = 0; i < infos.size(); i++) {
            TrackInfo info = infos.get(i);
            builder.append(" | track[").append(i).append("]");
            builder.append(" type=").append(trackTypeName(info.type));
            builder.append(" id=").append(info.id);
            builder.append(" demuxId=").append(info.demuxId);
            builder.append(" srcId=").append(info.srcId);
            builder.append(" rawSelected=").append(info.selected);
            builder.append(" finalSelected=").append(isTrackSelectedInSnapshot(tracks, info));
            builder.append(" title=").append(info.title);
            builder.append(" lang=").append(info.lang);
            builder.append(" codec=").append(info.codec);
            builder.append(" decoder=").append(info.decoder);
            builder.append(" doviProfile=").append(info.dolbyVisionProfile);
            builder.append(" doviLevel=").append(info.dolbyVisionLevel);
            Format format = info.toFormat();
            builder.append(" label=").append(format.label);
            builder.append(" formatLang=").append(format.language);
            builder.append(" size=").append(info.width).append("x").append(info.height);
            builder.append(" fps=").append(info.frameRate);
            builder.append(" sr=").append(info.sampleRate);
            builder.append(" ch=").append(info.channels);
            builder.append(" br=").append(info.bitrate);
            builder.append(" color=").append(info.colorInfo == null ? "" : info.colorInfo.toLogString());
        }
        String text = builder.toString();
        Log.d(TAG, text);
        PlaybackTrace.log("mpv", host.getPlaybackTraceId(), "%s", text);
    }

    private boolean isTrackSelectedInSnapshot(Tracks tracks, TrackInfo info) {
        if (tracks == null || tracks.isEmpty()) return false;
        String groupId = "mpv:" + info.type + ":" + info.id;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.length <= 0 || !group.getMediaTrackGroup().id.equals(groupId)) continue;
            return group.isTrackSelected(0);
        }
        return false;
    }

    private static String trackTypeName(int type) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> "video";
            case C.TRACK_TYPE_AUDIO -> "audio";
            case C.TRACK_TYPE_TEXT -> "text";
            default -> String.valueOf(type);
        };
    }

    public final class TrackInfo {
        final int type;
        final String id;
        final int demuxId;
        final int srcId;
        final String title;
        final String lang;
        final String codec;
        final String decoder;
        final int dolbyVisionProfile;
        final int dolbyVisionLevel;
        final int sourceDolbyVisionProfile;
        final int sourceDolbyVisionLevel;
        final boolean selected;
        final int width;
        final int height;
        final float frameRate;
        final int sampleRate;
        final int channels;
        final int bitrate;
        final int hlsBitrate;
        final ColorInfo colorInfo;

        TrackInfo(int type, String id, int demuxId, int srcId, String title,
                  String lang, String codec, String decoder,
                  int dolbyVisionProfile, int dolbyVisionLevel,
                  int sourceDolbyVisionProfile, int sourceDolbyVisionLevel,
                  boolean selected, int width, int height, float frameRate,
                  int sampleRate, int channels, int bitrate, int hlsBitrate,
                  @Nullable ColorInfo colorInfo) {
            this.type = type;
            this.id = id;
            this.demuxId = demuxId;
            this.srcId = srcId;
            this.title = title;
            this.lang = lang;
            this.codec = codec;
            this.decoder = decoder;
            this.dolbyVisionProfile = dolbyVisionProfile;
            this.dolbyVisionLevel = dolbyVisionLevel;
            this.sourceDolbyVisionProfile = sourceDolbyVisionProfile;
            this.sourceDolbyVisionLevel = sourceDolbyVisionLevel;
            this.selected = selected;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitrate = bitrate;
            this.hlsBitrate = hlsBitrate;
            this.colorInfo = colorInfo;
        }

        Format toFormat() {
            String label = TextUtils.isEmpty(title) ? (TextUtils.isEmpty(lang) ? trackLabel() : null) : title;
            Format.Builder builder = new Format.Builder()
                    .setId(type + ":" + id)
                    .setLabel(label)
                    .setCodecs(TextUtils.isEmpty(codec) ? null : codec)
                    .setLanguage(TextUtils.isEmpty(lang) ? null : lang)
                    .setSampleMimeType(sampleMimeType(this));
            if (width > 0) builder.setWidth(width);
            if (height > 0) builder.setHeight(height);
            if (frameRate > 0) builder.setFrameRate(frameRate);
            if (colorInfo != null) builder.setColorInfo(colorInfo);
            if (sampleRate > 0) builder.setSampleRate(sampleRate);
            if (channels > 0) builder.setChannelCount(channels);
            if (bitrate > 0) builder.setAverageBitrate(bitrate);
            return builder.build();
        }

        MpvPlayer.VideoTrackDiagnostics toVideoTrackDiagnostics() {
            int sourceProfile = sourceDolbyVisionProfile > 0 ? sourceDolbyVisionProfile : dolbyVisionProfile;
            int sourceLevel = sourceDolbyVisionProfile > 0 ? sourceDolbyVisionLevel : dolbyVisionLevel;
            return new MpvPlayer.VideoTrackDiagnostics(
                    sourceCodecs(sourceProfile, sourceLevel),
                    dolbyVisionProfile, dolbyVisionLevel,
                    sourceProfile, sourceLevel,
                    codec, decoder, colorInfo);
        }

        AudioPlaybackDiagnostics.Track toAudioPlaybackTrack(String profile) {
            return AudioPlaybackDiagnostics.track(codec, title, profile,
                    sampleMimeType(this), channels, sampleRate, lang, bitrate);
        }

        private String sourceCodecs(int profile, int level) {
            if (profile <= 0) return codec;
            String value = String.format(Locale.US, "dvhe.%02d", profile);
            return level >= 0 ? value + String.format(Locale.US, ".%02d", level) : value;
        }

        private String trackLabel() {
            String prefix = switch (type) {
                case C.TRACK_TYPE_VIDEO -> "Video";
                case C.TRACK_TYPE_AUDIO -> "Audio";
                case C.TRACK_TYPE_TEXT -> "Subtitle";
                default -> "Track";
            };
            return prefix + " " + id;
        }
    }
}
