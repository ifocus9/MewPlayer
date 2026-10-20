package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.ui.PlayerView;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.playback.PlaybackEventCollector;
import com.fongmi.android.tv.playback.PlaybackOrientation;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeMoreAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeSizing;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.ParseAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.CustomKeyDown;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.custom.CustomSeekView;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.CastDialog;
import com.fongmi.android.tv.ui.dialog.CodecCapabilityDialog;
import com.fongmi.android.tv.ui.dialog.ControlDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeSheetDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeDownloadDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeListDialog;
import com.fongmi.android.tv.ui.dialog.InfoDialog;
import com.fongmi.android.tv.ui.dialog.PlayerKernelDialog;
import com.fongmi.android.tv.ui.dialog.QuickSearchDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.TitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.ui.dialog.VideoContentDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.EpisodeTitleCompact;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PiP;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Timer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoActivity extends PlaybackActivity implements Clock.Callback, CustomKeyDown.Listener, TrackDialog.Listener, ControlDialog.Listener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, EpisodeListDialog.Host, EpisodeDownloadDialog.Host, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, ParseAdapter.OnClickListener, CastDialog.Listener, InfoDialog.Listener {

    private static final String SIZE_TAG = "MPV_SIZE";
    // 剧集数达到该值时，选集横排末尾显示“更多”卡片
    private static final int EPISODE_MORE_MIN_COUNT = 6;

    private ActivityVideoBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private int mFrameHeight;
    private Observer<Result> mObserveDetail;
    private Observer<Result> mObservePlayer;
    private Observer<Result> mObserveSearch;
    private EpisodeAdapter mEpisodeAdapter;
    // 选集横排末尾的“更多”卡片（剧集数 >= EPISODE_MORE_MIN_COUNT 时显示）
    private EpisodeMoreAdapter mEpisodeMoreAdapter;
    private QualityAdapter mQualityAdapter;
    private QuickAdapter mQuickAdapter;
    private QuickSearchDialog mQuickSearchDialog;
    private String mQuickSearchKeyword;
    private ParseAdapter mParseAdapter;
    private boolean mRestoringConfigurationPlayback;
    private String mPosterUrl;
    private Vod mPendingDetailVod;
    private Result mPendingPlayerResult;
    private Episode mPendingDownloadEpisode;
    private Map<String, View> mActionButtons;
    private final List<View> mCustomActionViews = new ArrayList<>();
    private HorizontalScrollView mCustomLeftButtons;
    private HorizontalScrollView mCustomRightButtons;
    private HorizontalScrollView mCustomPortraitButtons;
    private LinearLayout mCustomLeftButtonRow;
    private LinearLayout mCustomRightButtonRow;
    private LinearLayout mCustomPortraitButtonRow;
    private SiteViewModel mViewModel;
    private FlagAdapter mFlagAdapter;
    private CustomKeyDown mKeyDown;
    private List<String> mBroken;
    private History mHistory;
    private boolean fullscreen;
    private boolean initAuto;
    private boolean autoMode;
    private boolean revealManualSearch;
    private boolean useParse;
    private boolean rotate;
    private boolean detailHealthRecorded;
    private boolean playHealthRecorded;
    private boolean playerKernelSwitchRefreshing;
    private boolean decodeSwitchRefreshing;
    private int deferredFullscreenOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int mStatusBarInset;
    private int mEpisodeBottomInset;
    private int mNavigationRightInset;
    private int mEpisodeMaxHeight;
    private Runnable mHideControlRunnable;
    private Runnable mTrafficRunnable;
    private Runnable mOrientRunnable;
    private Runnable mLoadingTimeoutRunnable;
    private Clock mClock;
    private MpvPlayer mDiscMenuPlayer;
    private MpvPlayer mCustomButtonPlayer;
    private final Runnable mCustomButtonStateListener = this::updateCustomButtonStates;
    private final Runnable mDiscMenuStateListener = this::updateDiscMenuTools;
    private PiP mPiP;
    private String playHealthKey;
    private long detailStartTime;
    private long playerStartTime;

    public static void push(FragmentActivity activity, String text) {
        if (FileChooser.isValid(activity, Uri.parse(text))) file(activity, FileChooser.getPathFromUri(Uri.parse(text)));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, String path) {
        if (TextUtils.isEmpty(path)) return;
        String name = new File(path).getName();
        start(activity, SiteApi.PUSH, "file://" + path, name);
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, history.getWallPic());
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic, String wallPic) {
        start(activity, key, id, name, pic, null, true, wallPic);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, String wallPic) {
        start(activity, key, id, name, pic, mark, false, wallPic);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, String wallPic, String content) {
        start(activity, key, id, name, pic, mark, false, wallPic, content);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        start(activity, key, id, name, pic, mark, collect, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, String wallPic) {
        start(activity, key, id, name, pic, mark, collect, wallPic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, String wallPic, String content) {
        ImgUtil.preload(activity, pic);
        if (Setting.isPlaybackArtworkWall() && !TextUtils.isEmpty(wallPic) && !TextUtils.equals(wallPic, pic)) ImgUtil.preload(activity, wallPic);
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("wallPic", wallPic);
        intent.putExtra("content", content);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivity(intent);
    }

    public String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getWallPic() {
        return Objects.toString(getIntent().getStringExtra("wallPic"), "");
    }

    private String getContent() {
        return Objects.toString(getIntent().getStringExtra("content"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    public String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    public Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    public Flag getFlag() {
        return mFlagAdapter == null || mFlagAdapter.isEmpty() ? null : mFlagAdapter.getActivated();
    }

    public Episode getEpisode() {
        Flag flag = getFlag();
        if (flag != null) {
            List<Episode> items = flag.getEpisodes();
            for (Episode item : items) if (item.isSelected()) return item;
            if (!items.isEmpty()) return items.get(0);
        }
        return mEpisodeAdapter == null || mEpisodeAdapter.isEmpty() ? null : mEpisodeAdapter.getActivated();
    }

    private String getEpisodePlayFlag(Flag flag, Episode episode) {
        return flag == null ? "" : flag.getFlag();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private boolean isReplay() {
        return Setting.getReset() == 1;
    }

    private boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    private boolean isAutoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    public boolean isLand() {
        return mBinding.getRoot().getTag().equals("land");
    }

    private boolean isPort() {
        return mBinding.getRoot().getTag().equals("port");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }

    @Override
    protected CustomSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        setPlayerKernel();
        setDecode();
        applyDeferredFullscreenOrientation();
        checkLand();
        if (consumePendingPlaybackResult()) return;
        checkId();
    }

    @Override
    protected void onControllerReady(Player controller) {
    }

    @Override
    protected void onPlayerRebuilt() {
        setPlayerKernel();
        setDecode();
        refreshControlDialog();
    }

    private void refreshControlDialog() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ControlDialog dialog) dialog.setPlayer();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId)) return;
        mBinding.swipeLayout.setRefreshing(true);
        saveHistory();
        if (service() != null) {
            player().reset();
            player().stop();
            hideProgress();
        }
        getIntent().putExtras(intent);
        setOrient();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mRestoringConfigurationPlayback = savedInstanceState != null;
        applyLightStatusBarIcons();
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> setStatusBar(insets));
        mKeyDown = CustomKeyDown.create(this, mBinding.exo);
        mFrameParams = mBinding.video.getLayoutParams();
        mFrameHeight = mFrameParams.height;
        mBinding.swipeLayout.setEnabled(false);
        applyDetailForceDark();
        mObserveDetail = this::setDetail;
        mObservePlayer = this::setPlayer;
        mObserveSearch = this::setSearch;
        mBroken = new ArrayList<>();
        mClock = Clock.create();
        mHideControlRunnable = this::hideControl;
        mTrafficRunnable = this::setTraffic;
        mOrientRunnable = this::setOrient;
        mLoadingTimeoutRunnable = this::showEmpty;
        mPiP = new PiP();
        setRecyclerView();
        setVideoView();
        setViewModel();
        setShortDisplay();
        showInitialPreview();
        mBinding.progressLayout.showProgress();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.name.setOnClickListener(view -> onName());
        mBinding.download.setOnClickListener(view -> onDownload());
        mBinding.episodeCount.setOnClickListener(view -> onMore());
        mBinding.other.setOnClickListener(view -> onOther());
        mBinding.director.setOnClickListener(view -> onDirector());
        mBinding.actor.setOnClickListener(view -> onActor());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.contentLayout.setOnClickListener(view -> onContent());
        mBinding.name.setOnLongClickListener(view -> onChange());
        mBinding.content.setOnLongClickListener(view -> onCopy());
        mBinding.contentLayout.setOnLongClickListener(view -> onCopy());
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> checkNext());
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.setting.setOnClickListener(view -> onSetting());
        mBinding.control.title.setOnLongClickListener(view -> onChange());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.fullscreen.setOnClickListener(view -> onFullscreen());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.change2.setOnClickListener(view -> onChange());
        mBinding.control.action.title.setOnClickListener(view -> onTitle());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.discMenu.setOnClickListener(view -> {
            hideControl();
            openDiscMenu();
        });
        mBinding.control.action.discMenu.setOnLongClickListener(view -> {
            hideControl();
            showDiscMenuControls();
            return true;
        });
        mBinding.discTools.fullscreen.setOnClickListener(view -> onFullscreen());
        mBinding.control.action.player.setOnClickListener(view -> onPlayerKernel());
        mBinding.control.action.player.setOnLongClickListener(view -> onChooseLong());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.text.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.reset.setOnLongClickListener(view -> onResetToggle());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) ->
                (!isVisible(mBinding.control.getRoot()) && dispatchDiscMenuTouch(event))
                        || mKeyDown.onTouchEvent(event));
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.control.action.actionScroll.setOnTouchListener(this::onActionTouch);
        mBinding.swipeLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private WindowInsetsCompat setStatusBar(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
        int bottom = nav.bottom;
        mStatusBarInset = top;
        mNavigationRightInset = nav.right;
        applyStatusBarSpacer();
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        lp.height = top;
        mBinding.statusBar.setLayoutParams(lp);
        setEpisodeBottomInset(bottom);
        return insets;
    }

    // 播放页顶部是黑色状态栏占位 + 视频区域，状态栏图标固定用白色（BaseActivity 的 SystemBarStyle.auto 在浅色主题下会给深色图标）
    private void applyLightStatusBarIcons() {
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
    }

    private void applyStatusBarSpacer() {
        if (mBinding == null) return;
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        int height = mStatusBarInset;
        if (lp.height == height) return;
        lp.height = height;
        mBinding.statusBar.setLayoutParams(lp);
    }

    private void setEpisodeBottomInset(int bottom) {
        mEpisodeBottomInset = bottom;
        int padding = ResUtil.dp2px(12);
        mBinding.episode.setPaddingRelative(mBinding.episode.getPaddingStart(), mBinding.episode.getPaddingTop(), mBinding.episode.getPaddingEnd(), padding);
        mBinding.episode.post(this::updateEpisodeViewportHeight);
    }

    private void updateEpisodeViewportHeight() {
        if (mBinding == null || mBinding.episode == null) return;
        mBinding.episode.setMaxHeight(0);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    private boolean isPointInside(View view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && rect.contains((int) event.getRawX(), (int) event.getRawY());
    }

    private void setRecyclerView() {
        mBinding.flag.setHasFixedSize(true);
        mBinding.flag.setItemAnimator(null);
        mBinding.flag.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        // 选集：横向一排（全部剧集 + 末尾“更多”卡片），完整列表在抽屉 EpisodeSheetDialog 里
        mBinding.episode.setNestedScrollingEnabled(false);
        mBinding.episode.setHasFixedSize(false);
        mBinding.episode.setItemAnimator(null);
        mBinding.episode.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.episode.addItemDecoration(new SpaceItemDecoration(8));
        mEpisodeAdapter = new EpisodeAdapter(this, ViewType.ROW);
        mEpisodeMoreAdapter = new EpisodeMoreAdapter(this::onMore);
        mBinding.episode.setAdapter(new ConcatAdapter(mEpisodeAdapter, mEpisodeMoreAdapter));
        mBinding.quality.setHasFixedSize(true);
        mBinding.quality.setItemAnimator(null);
        mBinding.quality.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.control.parse.setHasFixedSize(true);
        mBinding.control.parse.setItemAnimator(null);
        mBinding.control.parse.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.control.parse.setAdapter(mParseAdapter = new ParseAdapter(this, ViewType.DARK));
    }

    private int getEpisodeGridMaxSpan() {
        return EpisodeGridLayoutPolicy.getMaxSpan(isLand(), ResUtil.isPad());
    }

    private void setVideoView() {
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[Setting.getReset()]);
        setupActionButtons();
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            mPiP.update(this, view);
            updateCustomButtonLayout();
            Log.d(SIZE_TAG, "video layout new=" + (right - left) + "x" + (bottom - top)
                    + " old=" + (oldRight - oldLeft) + "x" + (oldBottom - oldTop)
                    + " fullscreen=" + isFullscreen()
                    + " land=" + isLand()
                    + " scale=" + getScale()
                    + " player=" + (service() == null ? "none" : player().getPlayerText()));
        });
    }

    private void setupActionButtons() {
        mActionButtons = new HashMap<>();
        addActionButton(PlayerButtonSetting.PLAYER, mBinding.control.action.player);
        addActionButton(PlayerButtonSetting.DECODE, mBinding.control.action.decode);
        addActionButton(PlayerButtonSetting.SPEED, mBinding.control.action.speed);
        addActionButton(PlayerButtonSetting.SCALE, mBinding.control.action.scale);
        addActionButton(PlayerButtonSetting.RESET, mBinding.control.action.reset);
        addActionButton(PlayerButtonSetting.REPEAT, mBinding.control.action.repeat);
        addActionButton(PlayerButtonSetting.TEXT, mBinding.control.action.text);
        addActionButton(PlayerButtonSetting.AUDIO, mBinding.control.action.audio);
        addActionButton(PlayerButtonSetting.VIDEO, mBinding.control.action.video);
        addActionButton(PlayerButtonSetting.OPENING, mBinding.control.action.opening);
        addActionButton(PlayerButtonSetting.ENDING, mBinding.control.action.ending);
        addActionButton(PlayerButtonSetting.TITLE, mBinding.control.action.title);
        addActionButton(PlayerButtonSetting.CHAPTER, mBinding.control.action.chapter);
        addActionButton(PlayerButtonSetting.PREV, mBinding.control.action.prev);
        addActionButton(PlayerButtonSetting.NEXT, mBinding.control.action.next);
        addActionButton(PlayerButtonSetting.EPISODES, mBinding.control.action.episodes);
        addActionButton(PlayerButtonSetting.CHANGE, mBinding.control.action.change2);
        PlayerButtonSetting.applyOrder(mBinding.control.action.container, mActionButtons);
        setupCustomActionButtons();
    }

    private void addActionButton(String id, View view) {
        mActionButtons.put(id, view);
    }

    private void setupCustomActionButtons() {
        ensureCustomButtonContainers();
        for (View view : mCustomActionViews) {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        }
        mCustomActionViews.clear();
        mCustomLeftButtonRow.removeAllViews();
        mCustomRightButtonRow.removeAllViews();
        mCustomPortraitButtonRow.removeAllViews();
        List<MpvConfigStore.CustomButton> buttons = MpvConfigStore.customButtons();
        boolean landscape = isLand();
        for (int index = 0; index < buttons.size(); index++) {
            MpvConfigStore.CustomButton button = buttons.get(index);
            if (!button.isButtonVisible()) continue;
            TextView view = new TextView(this);
            view.setTextSize(13);
            view.setTextColor(Color.WHITE);
            view.setGravity(Gravity.CENTER);
            view.setMinHeight(ResUtil.dp2px(40));
            view.setMinWidth(ResUtil.dp2px(56));
            view.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(4), ResUtil.dp2px(10), ResUtil.dp2px(4));
            view.setBackgroundResource(R.drawable.selector_control_sheet_button);
            view.setText(button.title);
            view.setSingleLine(true);
            view.setMaxWidth(ResUtil.dp2px(144));
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setContentDescription(button.title);
            view.setTag(button.id);
            view.setOnClickListener(item -> {
                player().sendMpvCustomButton(button.id, false);
                setR1Callback();
            });
            view.setOnLongClickListener(item -> {
                player().sendMpvCustomButton(button.id, true);
                setR1Callback();
                return true;
            });
            if (Util.isLeanback()) {
                view.setFocusable(true);
                view.setFocusableInTouchMode(true);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(40));
            params.setMargins(ResUtil.dp2px(4), ResUtil.dp2px(2), ResUtil.dp2px(4), ResUtil.dp2px(2));
            LinearLayout target = landscape
                    ? (index < 4 ? mCustomLeftButtonRow : mCustomRightButtonRow)
                    : mCustomPortraitButtonRow;
            target.addView(view, params);
            mCustomActionViews.add(view);
        }
        updateCustomButtonLayout();
        updateCustomButtonVisibility();
    }

    private void updateCustomButtonStates() {
        MpvPlayer mpv = service() != null && isOwner()
                && player().getPlayer() instanceof MpvPlayer active ? active : null;
        if (mCustomButtonPlayer != mpv) {
            if (mCustomButtonPlayer != null) mCustomButtonPlayer.removeCustomButtonStateListener(mCustomButtonStateListener);
            mCustomButtonPlayer = mpv;
            if (mpv != null) mpv.addCustomButtonStateListener(mCustomButtonStateListener);
        }
        for (View view : mCustomActionViews) {
            view.setSelected(mpv != null && mpv.isCustomButtonActive((String) view.getTag()));
        }
    }

    private void ensureCustomButtonContainers() {
        if (mCustomLeftButtons != null) return;
        mCustomLeftButtons = createCustomButtonScroll();
        mCustomRightButtons = createCustomButtonScroll();
        mCustomPortraitButtons = createCustomButtonScroll();
        mCustomLeftButtonRow = createCustomButtonRow(mCustomLeftButtons);
        mCustomRightButtonRow = createCustomButtonRow(mCustomRightButtons);
        mCustomPortraitButtonRow = createCustomButtonRow(mCustomPortraitButtons);
        mCustomRightButtons.setFillViewport(true);
        mCustomRightButtonRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mCustomRightButtonRow.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;

        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        int margin = ResUtil.dp2px(8);
        leftParams.setMargins(margin, 0, margin, 0);
        rightParams.setMargins(margin, 0, margin, 0);
        mBinding.video.addView(mCustomLeftButtons, leftParams);
        mBinding.video.addView(mCustomRightButtons, rightParams);

        RelativeLayout.LayoutParams portraitParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        portraitParams.addRule(RelativeLayout.ABOVE, R.id.bottom);
        portraitParams.setMargins(margin, 0, margin, ResUtil.dp2px(2));
        ((ViewGroup) mBinding.control.getRoot()).addView(mCustomPortraitButtons, portraitParams);
    }

    private HorizontalScrollView createCustomButtonScroll() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return scroll;
    }

    private LinearLayout createCustomButtonRow(HorizontalScrollView scroll) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void updateCustomButtonLayout() {
        if (mCustomLeftButtons == null || mBinding.video.getWidth() <= 0) return;
        boolean landscape = isLand();
        if (!landscape) return;

        int margin = ResUtil.dp2px(8);
        int width = Math.max(ResUtil.dp2px(96), mBinding.video.getWidth() / 2 - margin * 2);
        int height = Math.max(mCustomLeftButtons.getMeasuredHeight(), ResUtil.dp2px(40));
        int top = Math.max(margin, Math.round(mBinding.video.getHeight() * 0.65f - height / 2f));
        FrameLayout.LayoutParams leftParams = (FrameLayout.LayoutParams) mCustomLeftButtons.getLayoutParams();
        FrameLayout.LayoutParams rightParams = (FrameLayout.LayoutParams) mCustomRightButtons.getLayoutParams();
        leftParams.width = width;
        rightParams.width = width;
        leftParams.topMargin = top;
        rightParams.topMargin = top;
        mCustomLeftButtons.setLayoutParams(leftParams);
        mCustomRightButtons.setLayoutParams(rightParams);
    }

    private void updateCustomButtonVisibility() {
        updateCustomButtonStates();
        boolean visible = service() != null && player().isMpv() && isVisible(mBinding.control.getRoot());
        if (mCustomLeftButtons != null) mCustomLeftButtons.setVisibility(visible && isLand() ? View.VISIBLE : View.GONE);
        if (mCustomRightButtons != null) mCustomRightButtons.setVisibility(visible && isLand() ? View.VISIBLE : View.GONE);
        if (mCustomPortraitButtons != null) mCustomPortraitButtons.setVisibility(visible && !isLand() ? View.VISIBLE : View.GONE);
        updateCustomButtonLayout();
    }

    public void applyActionButtonVisibility() {
        if (mActionButtons != null) PlayerButtonSetting.applyVisibility(mActionButtons);
        updateCustomButtonVisibility();
    }

    private void setVideoView(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        } else {
            setVideoView();
        }
    }

    private void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    private void setDecodeSwitchPending(boolean pending) {
        mBinding.control.action.decode.setEnabled(!pending);
        mBinding.control.action.decode.setAlpha(pending ? 0.65f : 1.0f);
    }

    private void setNextDecodeText() {
        int next = player().isHardDecode() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        mBinding.control.action.decode.setText(ResUtil.getStringArray(R.array.select_decode)[next]);
    }

    private void setPlayerKernel() {
        mBinding.control.action.player.setText(player().getPlayerText());
    }

    private void setScale(int scale) {
        if (mHistory != null) mHistory.setScale(scale);
        if (SiteApi.PUSH.equals(getKey())) PlayerSetting.putScale(scale);
        applyResizeMode(scale);
        mBinding.exo.post(() -> applyResizeMode(scale));
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observeForever(mObserveDetail);
        mViewModel.getPlayer().observeForever(mObservePlayer);
        mViewModel.getSearch().observeForever(mObserveSearch);
    }

    private void checkId() {
        if (getId().startsWith("push://")) getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", getId().substring(7));
        if (getId().isEmpty() || getId().startsWith("msearch:")) setEmpty(false);
        else getDetail();
    }

    private void checkLand() {
        if (isPort() && ResUtil.isLand(this)) enterFullscreen();
    }

    private void getDetail() {
        detailStartTime = System.currentTimeMillis();
        detailHealthRecorded = false;
        mBinding.progressLayout.showProgress();
        SpiderDebug.log("video-flow", "detail start key=%s id=%s name=%s", getKey(), getId(), getName());
        mViewModel.detailContent(getKey(), getId());
    }

    private void getDetail(Vod item) {
        revealManualSearch = false;
        if (!isAutoMode()) mViewModel.stopSearch();
        saveHistory();
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        mBinding.swipeLayout.setRefreshing(true);
        mBinding.swipeLayout.setEnabled(false);
        mBinding.progressLayout.showProgress();
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        if (service() != null) {
            player().reset();
            player().stop();
            hideProgress();
        }
        getDetail();
    }

    private void setDetail(Result result) {
        long cost = System.currentTimeMillis() - detailStartTime;
        SpiderDebug.log("video-flow", "detail finish cost=%dms empty=%s msg=%s", cost, result.getList().isEmpty(), result.getMsg());
        recordDetailHealth(result, cost);
        mBinding.swipeLayout.setRefreshing(false);
        if (result.getList().isEmpty()) setEmpty(result.hasMsg());
        else setDetail(result.getVod());
        Notify.show(result.getMsg());
    }

    private void setEmpty(boolean finish) {
        if (isFromCollect() || finish) {
            finish();
        } else if (getName().isEmpty()) {
            showEmpty();
        } else {
            mBinding.name.setText(getName());
            App.post(mLoadingTimeoutRunnable, 10000);
            checkSearch(false);
        }
    }

    private void showEmpty() {
        showError(getString(R.string.error_detail));
        mBinding.swipeLayout.setEnabled(true);
        mBinding.progressLayout.showEmpty();
    }

    private void setDetail(Vod item) {
        if (service() == null) {
            mPendingDetailVod = item;
            return;
        }
        item.checkPic(getPic());
        item.checkName(getName());
        item.checkContent(getContent());
        mBinding.name.setText(item.getName());
        mFlagAdapter.addAll(item.getFlags());
        if (mBinding.flagLayout != null) mBinding.flagLayout.setVisibility(item.getFlags().isEmpty() ? View.GONE : View.VISIBLE);
        if (mBinding.flag != null) mBinding.flag.setVisibility(item.getFlags().isEmpty() ? View.GONE : View.VISIBLE);
        App.removeCallbacks(mLoadingTimeoutRunnable);
        checkHistory(item);
        mBinding.progressLayout.showContent();
        checkFlag(item);
        setText(item);
    }

    private void setText(Vod item) {
        // 站源以小胶囊标签展示，只显示名称
        setText(mBinding.site, 0, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.content, 0, item.getContent());
        setOther(mBinding.other, item);
        mBinding.other.setMaxLines(1);
        mBinding.director.setMaxLines(1);
        mBinding.actor.setMaxLines(1);
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (view == mBinding.content) setContentVisible();
        // 链接（演员/导演名等）用详情页强调色，日夜模式各自取 values / values-night 的 detail_accent
        view.setLinkTextColor(ContextCompat.getColor(this, R.color.detail_accent));
        CustomMovement.bind(view);
    }

    private void setContentVisible() {
        mBinding.contentLayout.setVisibility(mBinding.content.getVisibility());
    }

    /**
     * 详情页的颜色全部来自 values / values-night 里的 detail_* 资源，日夜两套都是手工配好的。
     * 这里关闭系统“强制深色”（Android 10+ 的 forceDark 自动反色），避免两套机制叠加导致颜色不可控：
     * 浅色模式下不受影响；深色模式下直接使用 values-night 的配色。
     * 播放器区域本身就是深色设计（黑底白字），弹窗是独立窗口，都不受这里影响。
     */
    private void applyDetailForceDark() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        mBinding.getRoot().setForceDarkAllowed(false);
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                FolderActivity.start(getActivity(), getKey(), result);
                ((TextView) view).setMaxLines(Integer.MAX_VALUE);
                setRedirect(true);
            }
        };
    }

    private void setOther(TextView view, Vod item) {
        // 合成一行：2026 · 大陆 · 剧情 · 全40集
        List<String> parts = new ArrayList<>();
        if (!item.getYear().isEmpty()) parts.add(item.getYear());
        if (!item.getArea().isEmpty()) parts.add(item.getArea());
        if (!item.getTypeName().isEmpty()) parts.add(item.getTypeName());
        if (!item.getRemarks().isEmpty()) parts.add(item.getRemarks());
        String text = TextUtils.join(" · ", parts);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        view.setText(text);
    }

    private void getPlayer(Flag flag, Episode episode) {
        mBinding.control.title.setText(getString(R.string.detail_title, mBinding.name.getText(), episode.getName()));
        playerStartTime = System.currentTimeMillis();
        beginPlayHealth();
        String playFlag = getEpisodePlayFlag(flag, episode);
        SpiderDebug.log("video-flow", "player start key=%s flag=%s episode=%s url=%s", getKey(), playFlag, episode.getName(), episode.getUrl());
        mViewModel.playerContent(getKey(), playFlag, episode.getUrl());
        mBinding.control.title.setSelected(true);
        updateHistory(episode);
        showProgress();
    }

    private void setPlayer(Result result) {
        if (isFinishing() || isDestroyed()) return;
        if (service() == null) {
            mPendingPlayerResult = result;
            return;
        }
        SpiderDebug.log("video-flow", "player finish cost=%dms useParse=%s multi=%s msg=%s", System.currentTimeMillis() - playerStartTime, result.shouldUseParse(), result.getUrl().isMulti(), result.getMsg());
        mQualityAdapter.addAll(result);
        setUseParse(result.shouldUseParse());
        mBinding.swipeLayout.setRefreshing(false);
        setQualityVisible(result.getUrl().isMulti());
        result.getUrl().set(mQualityAdapter.getPosition());
        if (result.hasArtwork() && !shouldKeepPushArtwork()) setArtwork(result.getArtwork());
        if (result.hasPosition()) mHistory.setPosition(result.getPosition());
        if (result.hasDesc()) {
            setText(mBinding.content, 0, result.getDesc());
        }
        mBinding.control.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());
        if (mPendingDownloadEpisode != null && mPendingDownloadEpisode.equals(getEpisode()) && !result.shouldUseParse() && !result.getRealUrl().isEmpty()) {
            Episode ep = mPendingDownloadEpisode;
            mPendingDownloadEpisode = null;
            startDownloadWithUrl(ep, result.getRealUrl(), result.getHeader());
        }
    }

    private boolean consumePendingPlaybackResult() {
        boolean consumed = false;
        Vod detail = mPendingDetailVod;
        if (detail != null) {
            mPendingDetailVod = null;
            setDetail(detail);
            consumed = true;
        }
        Result result = mPendingPlayerResult;
        if (result != null) {
            mPendingPlayerResult = null;
            if (player().isEmpty()) setPlayer(result);
            consumed = true;
        }
        return consumed;
    }

    private void recordDetailHealth(Result result, long cost) {
        if (detailHealthRecorded) return;
        detailHealthRecorded = true;
        boolean success = result != null && !result.getList().isEmpty();
        String error = result == null ? "" : result.hasMsg() ? result.getMsg() : success ? "" : "empty";
        SiteHealthStore.recordDetail(getKey(), success, cost, error);
    }

    private void beginPlayHealth() {
        playHealthKey = getKey();
        playHealthRecorded = false;
    }

    private void recordPlayHealth(boolean success, String error) {
        if (playHealthRecorded) return;
        playHealthRecorded = true;
        SiteHealthStore.recordPlay(TextUtils.isEmpty(playHealthKey) ? getKey() : playHealthKey, success, error);
    }

    @Override
    public void onItemClick(Flag item) {
        if (item.isSelected()) return;
        mFlagAdapter.setSelected(item);
        scrollToPosition(mBinding.flag, mFlagAdapter.getPosition());
        setEpisodeAdapter(item.getEpisodes());
        scrollEpisodeToSelected();
        setQualityVisible(false);
        seamless(item);
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        Flag flag = getFlag();
        if (mFlagAdapter != null) mFlagAdapter.toggle(item);
        if (flag != null) setEpisodeAdapter(flag.getEpisodes());
        // 横排刷新后保持当前集可见（居中）
        scrollEpisodeToSelected();
        if (isFullscreen()) Notify.show(getString(R.string.play_ready, item.getName()));
        onRefresh();
    }

    @Override
    public void onItemClick(Result result) {
        beginPlayHealth();
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());
    }

    @Override
    public void onItemClick(Vod item) {
        setAutoMode(false);
        applySearchArtwork(item);
        getDetail(item);
    }

    @Override
    public void onItemClick(Parse item) {
        setParse(item);
        onRefresh();
    }

    private void setParse(Parse item) {
        VodConfig.get().setParse(item);
        notifyItemChanged(mBinding.control.parse, mParseAdapter);
    }

    public void setEpisodeAdapter(List<Episode> items) {
        int size = items.size();
        mBinding.control.action.episodes.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.action.next.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.action.prev.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        applyActionButtonVisibility();
        mBinding.control.next.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.prev.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        mBinding.episodeCount.setText(getString(R.string.detail_episode_count, size));
        mBinding.episodeCount.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (mBinding.episodeLayout != null) mBinding.episodeLayout.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        setEpisodeItems(items);
        mBinding.episode.post(this::updateEpisodeViewportHeight);
        refreshEpisodeSheet();
    }

    private void setEpisodeItems(List<Episode> items) {
        mEpisodeAdapter.addAll(items);
        updateEpisodeRowSize();
    }

    /**
     * 横排卡片按整列表最长标题统一宽度；只有一集时自适应宽度。
     * 需在 addAll（会执行 EpisodeTitleCompact.apply）之后调用，保证按显示名计算。
     */
    private void updateEpisodeRowSize() {
        if (mEpisodeAdapter == null) return;
        List<Episode> items = mEpisodeAdapter.getItems();
        int width = items.size() <= 1 ? 0 : ResUtil.dp2px(EpisodeSizing.getRowItemWidthDp(EpisodeSizing.getMaxTitleLength(items)));
        mEpisodeAdapter.setItemWidth(width);
        if (mEpisodeMoreAdapter == null) return;
        mEpisodeMoreAdapter.setLongTitle(mEpisodeAdapter.isLongTitle());
        mEpisodeMoreAdapter.setVisible(items.size() >= EPISODE_MORE_MIN_COUNT);
    }

    /**
     * 横排滚动到指定剧集并尽量居中。index 为完整剧集列表下标（横排不分段，即适配器位置）。
     */
    private void scrollEpisodeToPosition(int index) {
        if (mEpisodeAdapter == null || mEpisodeAdapter.getItemCount() == 0) return;
        int position = Math.max(0, Math.min(index, mEpisodeAdapter.getItemCount() - 1));
        RecyclerView.LayoutManager manager = mBinding.episode.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            mBinding.episode.scrollToPosition(position);
            return;
        }
        int item = mEpisodeAdapter.getItemWidth() > 0 ? mEpisodeAdapter.getItemWidth() : ResUtil.dp2px(72);
        int visible = mBinding.episode.getWidth() - mBinding.episode.getPaddingStart() - mBinding.episode.getPaddingEnd();
        int offset = visible > item ? (visible - item) / 2 : 0;
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, offset);
    }

    private void scrollEpisodeToSelected() {
        mBinding.episode.post(() -> scrollEpisodeToPosition(mEpisodeAdapter.getPosition()));
    }

    /**
     * 抽屉打开时同步刷新（换线路、切集、倒序、精简、列数设置变化等）。
     */
    private void refreshEpisodeSheet() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof EpisodeSheetDialog dialog) dialog.refresh();
            else if (fragment instanceof EpisodeDownloadDialog dialog) dialog.refresh();
            else if (fragment instanceof EpisodeListDialog panel) panel.refresh();
        }
    }

    @Override
    public List<Flag> getEpisodePanelFlags() {
        return mFlagAdapter == null ? new ArrayList<>() : mFlagAdapter.getItems();
    }

    @Override
    public List<Episode> getEpisodeSheetItems() {
        Flag flag = getFlag();
        if (flag != null) return flag.getEpisodes();
        return mEpisodeAdapter == null ? new ArrayList<>() : mEpisodeAdapter.getItems();
    }

    @Override
    public boolean isEpisodeSheetReverse() {
        return mHistory != null && mHistory.isRevSort();
    }

    @Override
    public int getEpisodeSheetMaxSpan() {
        return getEpisodeGridMaxSpan();
    }

    @Override
    public void onEpisodeSheetReverse() {
        if (mHistory == null || getFlag() == null) return;
        onReverse();
    }

    @Override
    public void onEpisodeSheetCompact() {
        onShortDisplay();
    }

    public int getSelectedEpisodePosition(List<Episode> items) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return 0;
    }

    private void syncSelectedEpisode(Flag flag) {
        if (flag == null || mHistory == null) return;
        Episode episode = flag.find(mHistory.getEpisode(), false);
        if (episode != null) flag.toggle(true, episode);
    }

    private int getEpisodeCount() {
        Flag flag = getFlag();
        return flag == null ? mEpisodeAdapter.size() : flag.getEpisodes().size();
    }

    private void seamless(Flag flag) {
        Episode episode = getMark().isEmpty() ? flag.find(mHistory.getEpisode(), true) : flag.find(mHistory.getVodRemarks(), false);
        setQualityVisible(episode != null && episode.isSelected() && mQualityAdapter.getItemCount() > 1);
        if (episode == null || episode.isSelected()) return;
        mHistory.setVodRemarks(episode.getName());
        mHistory.setEpisodeUrl(episode.getUrl());
        onItemClick(episode);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.qualityText.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void reverseEpisode(boolean scroll) {
        Flag flag = getFlag();
        if (flag == null) return;
        mFlagAdapter.reverse();
        setEpisodeAdapter(flag.getEpisodes());
        if (scroll) scrollEpisodeToSelected();
    }

    private void onName() {
        String name = mBinding.name.getText().toString();
        Notify.show(getString(R.string.detail_search, name));
        showQuickSearch(name);
        initSearch(name, false);
    }

    private void onSearch() {
        onName();
    }

    private void onShortDisplay() {
        Setting.putCompactEpisodeTitle(!Setting.isCompactEpisodeTitle());
        setShortDisplay();
        refreshEpisodeTitles();
    }

    private void setShortDisplay() {
        // “精简”开关已移入选集抽屉，开关状态由抽屉自己读取 Setting；这里只需同步已打开的抽屉
        refreshEpisodeSheet();
    }

    private void onMore() {
        if (getEpisodeSheetItems().size() < 2) return;
        Flag flag = getFlag();
        if (flag != null) syncSelectedEpisode(flag);
        EpisodeSheetDialog.create().show(this);
    }

    private void onOther() {
        mBinding.other.setMaxLines(mBinding.other.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onDirector() {
        mBinding.director.setMaxLines(mBinding.director.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onActor() {
        mBinding.actor.setMaxLines(mBinding.actor.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onContent() {
        CharSequence content = mBinding.content.getText();
        if (TextUtils.isEmpty(content)) return;
        VideoContentDialog.create().content(content).show(this);
    }

    private void showQuickSearch(String keyword) {
        mQuickSearchKeyword = TextUtils.isEmpty(mQuickSearchKeyword) ? keyword : mQuickSearchKeyword;
        mQuickSearchDialog = QuickSearchDialog.create()
                .title(getString(R.string.detail_search, mQuickSearchKeyword))
                .keyword(mQuickSearchKeyword)
                .listener(this)
                .searchListener(this::onQuickSearch)
                .items(mQuickAdapter.getItems());
        mQuickSearchDialog.show(this);
    }

    private void onQuickSearch(String keyword) {
        initSearch(keyword, false);
    }

    private void onReverse() {
        mHistory.setRevSort(!mHistory.isRevSort());
        // 倒序后横排顺序整体翻转，需重新把当前集滚到可见位置
        reverseEpisode(true);
    }

    private boolean onChange() {
        checkSearch(true);
        return true;
    }

    private boolean onCopy() {
        Util.copy(mBinding.content.getText().toString());
        return true;
    }

    private void onBack() {
        if (isFullscreen()) exitFullscreen();
        else finishVideoPlayback();
    }

    private void finishVideoPlayback() {
        finishVideoPlaybackNow();
    }

    public void finishVideoPlaybackNow() {
        saveHistory(true);
        finishPlayback();
    }

    public void onCast() {
        if (mHistory == null || TextUtils.isEmpty(mHistory.getVodId()) || service() == null || player().isEmpty() || TextUtils.isEmpty(player().getUrl())) {
            Notify.show(R.string.cast_not_ready);
            return;
        }
        CastVideo video = new CastVideo(mBinding.name.getText().toString(), player().getUrl(), player().getPosition(), player().getHeaders());
        CastDialog.create().history(mHistory).video(video).fm(true).show(this);
    }

    public void onInfo() {
        InfoDialog.create().title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show(this);
    }

    public void checkPlay() {
        setR1Callback();
        debugPlaybackControl("checkPlay");
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
    }

    public void checkNext() {
        checkNext(true);
    }

    public void checkNext(boolean notify) {
        setR1Callback();
        Episode item = getAdjacentEpisode(1);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("audio-auto-next", "fallback next notify=%s selected=%s name=%s adapter=%d", notify, item.isSelected(), item.getName(), mEpisodeAdapter.getItemCount());
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(R.string.error_play_next);
    }

    public void checkPrev() {
        setR1Callback();
        Episode item = getAdjacentEpisode(-1);
        if (!item.isSelected()) onItemClick(item);
        else Notify.show(R.string.error_play_prev);
    }

    private Episode getAdjacentEpisode(int offset) {
        Flag flag = getFlag();
        List<Episode> items = flag == null ? mEpisodeAdapter.getItems() : flag.getEpisodes();
        if (items.isEmpty()) return new Episode();
        int position = getSelectedEpisodePosition(items) + offset;
        position = Math.max(0, Math.min(position, items.size() - 1));
        return items.get(position);
    }

    public boolean hasAdjacentEpisode(int offset) {
        Flag flag = getFlag();
        List<Episode> items = flag == null ? mEpisodeAdapter.getItems() : flag.getEpisodes();
        if (items.isEmpty()) return false;
        int position = getSelectedEpisodePosition(items) + offset;
        return position >= 0 && position < items.size();
    }

    public void onSetting() {
        setTrackVisible();
        ControlDialog.create().parent(mBinding).history(mHistory).parse(isUseParse()).player(player()).show(this);
    }

    private void onLock() {
        setLock(!isLock());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(PlaybackOrientation.getRotateOrientation(isRotate()));
    }

    private void onFullscreen() {
        if (isFullscreen()) exitFullscreen();
        else enterFullscreen();
        showControl();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    public void onTrack(int type) {
        TrackDialog.create().type(type).player(player()).show(this);
        hideControl();
    }

    @Override
    public void onTrackPanel(int type) {
        TrackDialog.create().type(type).player(player()).show(this);
    }

    private void onTitle() {
        TitleDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl();
    }

    @Override
    public void onTitlePanel() {
        TitleDialog.create().player(player()).show(this);
    }

    @Override
    public void onChapterPanel() {
        onChapter();
    }

    @Override
    public void onCodecCapabilityPanel() {
        CodecCapabilityDialog.show(this, player());
    }

    @Override
    public ActivityVideoBinding getControlBinding() {
        return mBinding;
    }

    @Override
    public PlayerManager getControlPlayer() {
        return service() == null ? null : player();
    }

    public PlaybackService getService() {
        return service();
    }

    public SiteViewModel getViewModel() {
        return mViewModel;
    }

    @Override
    public boolean isAudioOnly() {
        return super.isAudioOnly();
    }

    public History getHistory() {
        return mHistory;
    }

    @Override
    public History getControlHistory() {
        return mHistory;
    }

    @Override
    public boolean isControlParseEnabled() {
        return isUseParse();
    }

    public void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(player().addSpeed());
        saveDefaultSpeed();
        setR1Callback();
    }

    private boolean onSpeedLong() {
        mBinding.control.action.speed.setText(player().toggleSpeed());
        saveDefaultSpeed();
        setR1Callback();
        return true;
    }

    private void saveDefaultSpeed() {
        PlayerSetting.putDefaultSpeed(player().getSpeed());
        mHistory.setSpeed(player().getSpeed());
    }

    private void onReset() {
        if (isReplay()) onReplay();
        else onRefresh();
    }

    private void onReplay() {
        mHistory.setPosition(C.TIME_UNSET);
        if (player().isEmpty()) onRefresh();
        else player().setMediaItem();
    }

    private void onRefresh() {
        saveHistory();
        player().stop();
        player().clear();
        mClock.setCallback(null);
        if (mFlagAdapter.isEmpty()) return;
        if (mEpisodeAdapter.isEmpty()) return;
        Flag flag = getFlag();
        if (flag != null) getPlayer(flag, getEpisode());
    }

    private boolean onResetToggle() {
        Setting.putReset(Math.abs(Setting.getReset() - 1));
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[Setting.getReset()]);
        return true;
    }

    private void onDecode() {
        if (refreshAndSwitchDecode()) return;
        mClock.setCallback(null);
        player().toggleDecode();
        setR1Callback();
        setDecode();
    }

    private boolean refreshAndSwitchDecode() {
        if (decodeSwitchRefreshing) return true;
        if (getFlag() == null || getEpisode() == null) return false;
        long position = player().getPosition();
        float speed = player().getSpeed();
        boolean repeat = player().isRepeatOne();
        String key = getKey();
        String flag = getFlag().getFlag();
        String episode = getEpisode().getUrl();
        MediaMetadata metadata = buildMetadata();
        decodeSwitchRefreshing = true;
        setNextDecodeText();
        setDecodeSwitchPending(true);
        mClock.setCallback(null);
        SpiderDebug.log("video-flow", "switch decode refresh start key=%s flag=%s episode=%s", key, flag, episode);
        Task.execute(() -> {
            try {
                Result result = SiteApi.playerContent(key, flag, episode);
                App.post(() -> switchDecodeWithResult(result, position, speed, repeat, metadata));
            } catch (Throwable e) {
                App.post(() -> {
                    decodeSwitchRefreshing = false;
                    setDecodeSwitchPending(false);
                    setDecode();
                    Notify.show(e.getMessage());
                });
            }
        });
        return true;
    }

    private void switchDecodeWithResult(Result result, long position, float speed, boolean repeat, MediaMetadata metadata) {
        decodeSwitchRefreshing = false;
        if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
            player().toggleDecode();
        } else {
            player().switchDecode(result, getHistoryKey(), metadata, isUseParse(), position, speed, repeat);
        }
        setR1Callback();
        setDecodeSwitchPending(false);
        setDecode();
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
        setR1Callback();
    }

    private boolean onEndingReset() {
        setR1Callback();
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mHistory.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
        setR1Callback();
    }

    private boolean onOpeningReset() {
        setR1Callback();
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mHistory.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEpisodes() {
        syncSelectedEpisode(getFlag());
        EpisodeListDialog.create().show(this);
    }

    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.control.title.getText());
        setRedirect(true);
    }

    @Override
    public List<Episode> getEpisodeDownloadItems() {
        return getEpisodeSheetItems();
    }

    @Override
    public int getEpisodeDownloadMaxSpan() {
        return getEpisodeGridMaxSpan();
    }

    @Override
    public void onEpisodeDownload(Episode item) {
        startDownload(item);
    }

    private void onDownload() {
        if (getEpisodeDownloadItems().isEmpty()) {
            Notify.show(R.string.error_play_url);
            return;
        }
        Flag flag = getFlag();
        if (flag != null) syncSelectedEpisode(flag);
        EpisodeDownloadDialog.create().show(this);
    }

    private void startDownload(Episode episode) {
        if (episode == null) return;
        if (episode.equals(getEpisode()) && player() != null && !TextUtils.isEmpty(player().getUrl())) {
            startDownloadWithUrl(episode, player().getUrl(), player().getHeaders());
            return;
        }
        mPendingDownloadEpisode = episode;
        Notify.show(getString(R.string.detail_download_parsing, episode.getName()));
        if (!episode.equals(getEpisode())) {
            onItemClick(episode);
        }
    }

    private void checkPendingDownload() {
        if (mPendingDownloadEpisode == null) return;
        if (mPendingDownloadEpisode.equals(getEpisode()) && player() != null && !TextUtils.isEmpty(player().getUrl())) {
            Episode ep = mPendingDownloadEpisode;
            mPendingDownloadEpisode = null;
            startDownloadWithUrl(ep, player().getUrl(), player().getHeaders());
        }
    }

    private void startDownloadWithUrl(Episode episode, String url, Map<String, String> playerHeaders) {
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.error_play_url);
            return;
        }
        setRedirect(true);

        String vodName = mBinding.name.getText() != null ? mBinding.name.getText().toString().trim() : "";
        String epName = episode != null && !TextUtils.isEmpty(episode.getName()) ? episode.getName().trim() : "";

        String title;
        if (epName.isEmpty() || epName.equalsIgnoreCase(vodName)) {
            title = vodName;
        } else {
            title = vodName + " - " + epName;
        }
        if (TextUtils.isEmpty(title)) {
            title = mBinding.control.title.getText() != null ? mBinding.control.title.getText().toString().replace("：", " - ") : "video";
        }

        Map<String, String> headers = new HashMap<>();
        if (getSite() != null && getSite().getHeader() != null) {
            headers.putAll(getSite().getHeader());
        }
        if (playerHeaders != null) {
            headers.putAll(playerHeaders);
        }
        boolean hasReferer = headers.keySet().stream().anyMatch(k -> "referer".equalsIgnoreCase(k));
        if (!hasReferer && getSite() != null && !TextUtils.isEmpty(getSite().getApi()) && getSite().getApi().startsWith("http")) {
            headers.put("Referer", getSite().getApi());
        }

        PlayerHelper.download(this, url, headers, title);
    }

    private boolean onChooseLong() {
        onChoose();
        return true;
    }

    private void onPlayerKernel() {
        if (playerKernelSwitchRefreshing) return;
        PlayerKernelDialog.show(this, player().getPlayerType(), this::switchPlayerKernel);
    }

    private boolean canSwitchPlayerKernelDirectly(int nextType) {
        if (player() == null || player().isEmpty()) return false;
        PlaySpec spec = player().getSpec();
        if (spec == null || TextUtils.isEmpty(spec.getUrl())) return false;
        return !spec.isDash();
    }

    private void switchPlayerKernel(int type) {
        int nextType = PlayerSetting.sanitizePlayer(type);
        if (nextType == player().getPlayerType()) return;
        if (canSwitchPlayerKernelDirectly(nextType)) {
            mClock.setCallback(null);
            showProgress();
            SpiderDebug.log("video-flow", "switch player direct type=%d position=%d url=%s", nextType, player().getPosition(), player().getUrl());
            player().switchPlayer(nextType);
            setPlayerKernel();
            setDecode();
            setR1Callback();
            refreshControlDialog();
            return;
        }
        if (refreshAndSwitchPlayerKernel(type)) return;
        mClock.setCallback(null);
        showProgress();
        player().switchPlayer(type);
        setPlayerKernel();
        setDecode();
        setR1Callback();
        refreshControlDialog();
    }

    private boolean refreshAndSwitchPlayerKernel(int type) {
        if (playerKernelSwitchRefreshing) return true;
        Flag currentFlag = getFlag();
        Episode currentEpisode = getEpisode();
        if (currentFlag == null || currentEpisode == null || TextUtils.isEmpty(currentFlag.getFlag()) || TextUtils.isEmpty(currentEpisode.getUrl())) return false;
        int nextType = PlayerSetting.sanitizePlayer(type);
        long position = getPlayerSwitchPosition();
        float speed = player().getSpeed();
        boolean repeat = player().isRepeatOne();
        String key = getKey();
        String flag = currentFlag.getFlag();
        String episode = currentEpisode.getUrl();
        MediaMetadata metadata = buildMetadata();
        playerKernelSwitchRefreshing = true;
        mClock.setCallback(null);
        showProgress();
        SpiderDebug.log("video-flow", "switch player refresh start type=%d key=%s flag=%s episode=%s", nextType, key, flag, episode);
        Task.execute(() -> {
            try {
                Result result = SiteApi.playerContent(key, flag, episode, nextType);
                App.post(() -> switchPlayerKernelWithResult(nextType, result, position, speed, repeat, metadata));
            } catch (Throwable e) {
                App.post(() -> {
                    playerKernelSwitchRefreshing = false;
                    setPlayerKernel();
                    setDecode();
                    setR1Callback();
                    refreshControlDialog();
                    Notify.show(e.getMessage());
                });
            }
        });
        return true;
    }

    private long getPlayerSwitchPosition() {
        return Math.max(0, player().getPosition());
    }

    private void switchPlayerKernelWithResult(int type, Result result, long position, float speed, boolean repeat, MediaMetadata metadata) {
        playerKernelSwitchRefreshing = false;
        if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
            Notify.show(result != null && result.hasMsg() ? result.getMsg() : getString(R.string.error_play_url));
        } else {
            player().switchPlayer(type, result, getHistoryKey(), metadata, isUseParse(), position, speed, repeat);
        }
        setPlayerKernel();
        setDecode();
        setR1Callback();
        refreshControlDialog();
    }

    private boolean onTextLong() {
        if (!player().haveTrack(C.TRACK_TYPE_TEXT)) return false;
        onSubtitleClick();
        return true;
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        setR1Callback();
        return false;
    }

    private void onSwipeRefresh() {
        if (mBinding.progressLayout.isEmpty()) getDetail();
        else onRefresh();
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        if (isFullscreen()) return;
        if (service() == null) {
            SpiderDebug.log("video-flow", "fullscreen enter deferred reason=player-not-ready");
            return;
        }
        logVideoFrame("enterFullscreen before");
        setFullscreen(true);
        if (isLand() && !player().isPortrait()) setTransition();
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        setRequestedOrientation(PlaybackOrientation.getEnterFullscreenOrientation(player().isPortrait()));
        mBinding.control.title.setVisibility(View.VISIBLE);
        setSizeText();
        setRotate(player().isPortrait());
        mKeyDown.resetScale();
        App.post(mOrientRunnable, 2000);
        hideControl();
        logVideoFrame("enterFullscreen after");
    }

    private void exitFullscreen() {
        if (!isFullscreen()) return;
        if (service() == null) {
            SpiderDebug.log("video-flow", "fullscreen exit deferred reason=player-not-ready");
            return;
        }
        logVideoFrame("exitFullscreen before");
        setFullscreen(false);
        if (isLand() && !player().isPortrait()) setTransition();
        setRequestedOrientation(PlaybackOrientation.getExitFullscreenOrientation(isPort()));
        mBinding.episode.postDelayed(this::scrollEpisodeToSelected, 100);
        mBinding.control.title.setVisibility(View.INVISIBLE);
        setSizeText();
        mBinding.video.setLayoutParams(mFrameParams);
        mKeyDown.resetScale();
        App.post(mOrientRunnable, 2000);
        setRotate(false);
        hideControl();
        logVideoFrame("exitFullscreen after");
    }

    private void setTransition() {
        if (!shouldAnimateVideoFrameTransition()) {
            Log.d(SIZE_TAG, "video transition skipped native player=" + player().getPlayerText());
            return;
        }
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(150);
        ViewGroup parent = (ViewGroup) mBinding.video.getParent();
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    private boolean shouldAnimateVideoFrameTransition() {
        return service() == null || !player().isNativePlayer();
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mTrafficRunnable, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mTrafficRunnable);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    public void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        setTrackVisible();
        hideWidgetOverlay();
        mBinding.control.setting.setVisibility(View.GONE);
        mBinding.control.right.rotate.setVisibility(isFullscreen() && !isLock() ? View.VISIBLE : View.GONE);
        // 全屏时不再显示进度条后的退出全屏按钮（返回键/返回手势仍可退出），进度条行随之收紧
        mBinding.control.fullscreen.setVisibility(isLock() || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.parse.setVisibility(isFullscreen() && isUseParse() ? View.VISIBLE : View.GONE);
        mBinding.control.action.getRoot().setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.right.lock.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        // 投屏入口：播放器右上角图标（详情页下方的功能按钮行已移除）
        mBinding.control.cast.setVisibility(View.VISIBLE);
        // 全屏隐藏了系统状态栏，在右上角补上电量和时间
        mBinding.control.batteryTime.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        updateDiscMenuTools();
        updateCustomButtonVisibility();
        checkFullscreenImg();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        updateDiscMenuTools();
        updateCustomButtonVisibility();
        App.removeCallbacks(mHideControlRunnable);
    }

    private void hideWidgetOverlay() {
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.speed.clearAnimation();
        mBinding.widget.speed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    private void hideSheet() {
        getSupportFragmentManager().getFragments().stream().filter(fragment -> fragment instanceof BottomSheetDialogFragment).map(fragment -> (BottomSheetDialogFragment) fragment).forEach(BottomSheetDialogFragment::dismiss);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mTrafficRunnable, 1000);
    }

    private void setOrient() {
        if (isPort() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getPortAutoRotateOrientation());
        if (isLand() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getLandAutoRotateOrientation());
    }

    public void setR1Callback() {
        App.post(mHideControlRunnable, Constant.INTERVAL_HIDE);
    }

    private void setArtwork(String url) {
        if (mHistory != null) mHistory.setVodPic(url);
        setPoster(url);
    }

    // 详情头部左侧海报：用影片级封面（vod_pic），不跟随单集/音频队列封面变化；无图或加载失败时显示片名首字占位
    private void setPoster(String url) {
        String pic = Objects.toString(url, "");
        if (!pic.isEmpty() && TextUtils.equals(mPosterUrl, pic)) return;
        mPosterUrl = pic;
        CharSequence name = mBinding.name.getText();
        ImgUtil.load(name == null ? "" : name.toString(), pic, mBinding.poster, ResUtil.dp2px(150), ResUtil.dp2px(225));
    }

    private void setArtwork() {
        if (mHistory == null) return;
        setArtwork(mHistory.getVodPic());
    }

    private void checkFlag(Vod item) {
        boolean empty = item.getFlags().isEmpty();
        mBinding.flag.setVisibility(empty ? View.GONE : View.VISIBLE);
        boolean preservePlayback = mRestoringConfigurationPlayback && service() != null && !player().isEmpty();
        if (empty) {
            if (!preservePlayback) startFlow();
        } else if (preservePlayback) {
            restoreFlagSelectionWithoutPlayback();
        } else {
            onItemClick(mHistory.getFlag());
            if (mHistory.isRevSort()) reverseEpisode(true);
        }
        mRestoringConfigurationPlayback = false;
    }

    private void restoreFlagSelectionWithoutPlayback() {
        mFlagAdapter.setSelected(mHistory.getFlag());
        Flag flag = getFlag();
        if (flag == null) return;
        syncSelectedEpisode(flag);
        setEpisodeAdapter(flag.getEpisodes());
        scrollEpisodeToSelected();
        setQualityVisible(false);
        if (mHistory.isRevSort()) reverseEpisode(true);
    }

    private void checkHistory(Vod item) {
        mHistory = History.find(getHistoryKey());
        mHistory = mHistory == null ? createHistory(item) : mHistory;
        if (!TextUtils.isEmpty(getWallPic())) mHistory.setWallPic(getWallPic());
        if (!TextUtils.isEmpty(getMark())) mHistory.setVodRemarks(getMark());
        if (Setting.isIncognito() && mHistory.getKey().equals(getHistoryKey())) mHistory.delete();
        mBinding.control.action.opening.setText(mHistory.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
        mBinding.control.action.ending.setText(mHistory.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSetting.getDefaultSpeed()));
        mHistory.setSpeed(player().getSpeed());
        mHistory.setVodName(item.getName());
        PlaybackEventCollector.get().updateHistory(mHistory);
        setArtwork(getInitialArtwork(item));
        setScale(getScale());
    }

    private boolean shouldKeepPushArtwork() {
        return SiteApi.PUSH.equals(getKey()) && !TextUtils.isEmpty(getPic());
    }

    private String getInitialArtwork(Vod item) {
        return shouldKeepPushArtwork() ? getPic() : item.getPic();
    }

    private void applySearchArtwork(Vod item) {
        String pic = getSearchArtworkPic();
        if (!TextUtils.isEmpty(pic)) item.setPic(pic);
    }

    private String getSearchArtworkPic() {
        if (!TextUtils.isEmpty(getPic())) return getPic();
        if (mHistory != null && !TextUtils.isEmpty(mHistory.getVodPic())) return mHistory.getVodPic();
        return "";
    }

    private boolean hasInitialPreview() {
        return !getName().isEmpty() || !getPic().isEmpty() || !getWallPic().isEmpty();
    }

    private void showInitialPreview() {
        mBinding.name.setText(getName());
        setText(mBinding.content, 0, getContent());
        if (!getPic().isEmpty()) setArtwork(getPic());
        else if (!getWallPic().isEmpty()) setArtwork(getWallPic());
    }

    private History createHistory(Vod item) {
        History history = new History();
        history.setKey(getHistoryKey());
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.setVodPic(getInitialArtwork(item));
        history.setWallPic(getWallPic());
        history.findEpisode(item.getFlags());
        return history;
    }

    private void saveHistory() {
        saveHistory(false);
    }

    private void saveHistory(boolean exit) {
        if (mHistory == null || Setting.isIncognito()) return;
        if (service() != null && isOwner()) {
            updatePlaybackHistoryPosition();
            mHistory.setCreateTime(System.currentTimeMillis());
        }
        if (exit && service() != null) PlaybackEventCollector.get().onStop(player());
        if (!canSavePlaybackHistory(mHistory)) return;
        History history = mHistory.copy();
        Task.execute(() -> {
            if (history.getDuration() > 0) history.merge().save();
            else history.save();
            if (exit) RefreshEvent.history();
        });
    }

    private void syncHistory() {
        if (mHistory == null || Setting.isIncognito()) return;
        History history = mHistory.copy();
        Task.execute(history::save);
    }

    private void updateHistory(Episode item) {
        Flag flag = getFlag();
        String vodFlag = flag == null ? "" : flag.getFlag();
        boolean sameEpisode = item.matchesName(mHistory.getEpisode());
        boolean sameFlag = TextUtils.equals(mHistory.getVodFlag(), vodFlag);
        if ((!sameEpisode || !sameFlag) && service() != null) {
            updatePlaybackHistoryPosition();
            PlaybackEventCollector.get().onStop(player());
        }
        mHistory.setPosition(sameEpisode ? mHistory.getPosition() : C.TIME_UNSET);
        if (!sameEpisode) mHistory.setDuration(C.TIME_UNSET);
        mHistory.setVodFlag(vodFlag);
        mHistory.setVodRemarks(item.getName());
        mHistory.setEpisodeUrl(item.getUrl());
        PlaybackEventCollector.get().updateHistory(mHistory);
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void checkFullscreenImg() {
        mBinding.control.fullscreen.setImageResource(isFullscreen() ? R.drawable.ic_control_fullscreen_exit : R.drawable.ic_control_fullscreen);
    }

    private void updateVod(Vod item) {
        boolean id = !item.getId().isEmpty();
        boolean pic = !item.getPic().isEmpty();
        boolean name = !item.getName().isEmpty();
        if (id) getIntent().putExtra("id", item.getId());
        if (id) mHistory.replace(getHistoryKey());
        if (name) mHistory.setVodName(item.getName());
        if (name) mBinding.name.setText(item.getName());
        if (name) mBinding.control.title.setText(item.getName());
        updateFlag(getFlag(), item.getFlags());
        if (pic) setArtwork(item.getPic());
        if (pic || name) setMetadata();
        if (pic || name) syncHistory();
        if (id) updateNavigationKey();
        PlaybackEventCollector.get().updateHistory(mHistory);
        setText(item);
    }

    private void updateFlag(Flag activated, List<Flag> items) {
        items.forEach(item -> mFlagAdapter.getItems().stream()
                .filter(item::equals).findFirst().ifPresentOrElse(target -> {
                    target.mergeEpisodes(item.getEpisodes(), mHistory.isRevSort());
                    if (target.equals(activated)) setEpisodeAdapter(target.getEpisodes());
                }, () -> mFlagAdapter.add(item)));
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }

        @Override
        public void onAudio() {
            setAudioOnly(true);
            syncPiPForPlaybackMode();
            Util.moveToBackground(VideoActivity.this);
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setDecode();
        setPosition();
        checkPendingDownload();
    }

    @Override
    protected void onTracksChanged() {
        updateAudioOnlyState();
        syncPiPForPlaybackMode();
        setTrackVisible();
        mClock.setCallback(this);
    }

    public void updateAudioOnlyState() {
        if (service() == null) return;
        setAudioOnly(player().haveTrack(C.TRACK_TYPE_AUDIO) && !player().haveTrack(C.TRACK_TYPE_VIDEO));
    }


    private void debugPlaybackControl(String event) {
        if (!SpiderDebug.isEnabled()) return;
        if (service() == null || player().isEmpty()) {
            SpiderDebug.log("playback-control", "video.%s noPlayer owner=%s service=%s", event, isOwner(), service() != null);
            return;
        }
        SpiderDebug.log("playback-control", "video.%s pos=%d dur=%d state=%d playing=%s playWhenReady=%s repeat=%s owner=%s controller=%s",
                event, player().getPosition(), player().getDuration(), player().getPlaybackState(), player().isPlaying(), player().getPlayer().getPlayWhenReady(), player().isRepeatOne(), isOwner(), controller() != null);
    }




    @Override
    protected void onTitlesChanged() {
        setTitleVisible();
    }

    @Override
    protected void onError(String msg) {
        mPendingDownloadEpisode = null;
        recordPlayHealth(false, msg);
        mBinding.swipeLayout.setEnabled(true);
        Track.delete(player().getKey());
        mClock.setCallback(null);
        player().resetTrack();
        player().reset();
        player().stop();
        showError(msg);
        startFlow();
    }

    @Override
    protected void onReload(String msg) {
        super.onReload(msg);
    }

    @Override
    protected void onReclaim() {
        Result result = mViewModel.getPlayer().getValue();
        if (result != null) setPlayer(result);
    }

    @Override
    protected void onStateChanged(int state) {
        debugPlaybackControl("stateChanged=" + state);
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                recordPlayHealth(true, "");
                hideProgress();
                checkControl();
                player().reset();
                break;
            case Player.STATE_ENDED:
                checkEnded(true);
                updatePlayControl(false, syncPiPForPlaybackMode());
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        debugPlaybackControl("playingChanged=" + isPlaying);
        boolean audioMode = syncPiPForPlaybackMode();
        if (isPlaying || isPaused()) updatePlayControl(isPlaying, audioMode);
    }

    private void updatePlayControl(boolean isPlaying, boolean audioMode) {
        if (!audioMode) mPiP.update(this, isPlaying);
        mBinding.control.play.setImageResource(isPlaying ? androidx.media3.ui.R.drawable.exo_icon_pause : androidx.media3.ui.R.drawable.exo_icon_play);
    }

    @Override
    protected void onPlayerPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        debugPlaybackControl("positionDiscontinuity=" + reason + " old=" + oldPosition.positionMs + " new=" + newPosition.positionMs);
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        logVideoFrame("onSizeChanged before size=" + size.width + "x" + size.height);
        mPiP.update(this, size.width, size.height, getScale());
        setSizeText();
        updateVideoHeight();
        applyResizeMode(getScale());
        checkOrientation();
        logVideoFrame("onSizeChanged after size=" + size.width + "x" + size.height);
    }

    @Override
    protected void onSurfaceAttached() {
        logVideoFrame("onSurfaceAttached before");
        applyResizeMode(getScale());
        logVideoFrame("onSurfaceAttached after");
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).player(player()).show(this);
        hideControl();
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner()) return;
        long position, duration;
        mHistory.setCreateTime(time);
        if (hasDiscNavigationTimeline()) {
            if (canSavePlaybackHistory(mHistory) && mHistory.canSync()) syncHistory();
            return;
        }
        updatePlaybackHistoryPosition();
        position = mHistory.getPosition();
        duration = mHistory.getDuration();
        PlaybackEventCollector.get().onProgress(mHistory, player());
        if (mHistory.canSave() && mHistory.canSync()) syncHistory();
        long ending = mHistory.getEnding() > 0 && mHistory.getEnding() < duration ? mHistory.getEnding() : 0;
        if (mHistory.getEnding() > 0 && duration > 0 && mHistory.getEnding() + position >= duration) {
            checkEnded(false);
        }
    }

    private void updatePlaybackHistoryPosition() {
        if (mHistory == null || hasDiscNavigationTimeline()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position > 0) mHistory.setPosition(position);
        if (duration > 0) mHistory.setDuration(duration);
        else if (mHistory.getDuration() < 0) mHistory.setDuration(0);
        PlaybackEventCollector.get().updateHistory(mHistory);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) getDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) onRefresh();
        else if (event.getType() == RefreshEvent.Type.VOD) updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (isRedirect() || !event.isVod() || mParseAdapter == null) return;
        mParseAdapter.reload();
    }

    private void setPosition() {
        if (mHistory == null || hasDiscMenu()) return;
        if (mHistory.isNearEnding()) {
            SpiderDebug.log("video-flow", "reset near-end history position=%d duration=%d key=%s", mHistory.getPosition(), mHistory.getDuration(), getHistoryKey());
            mHistory.resetPlaybackPosition();
            syncHistory();
        }
        long position = Math.max(mHistory.getOpening(), mHistory.getPosition());
        if (position > 0) player().seekTo(position);
    }

    private void checkOrientation() {
        if (isFullscreen() && !isRotate() && player().isPortrait()) {
            setRequestedOrientation(PlaybackOrientation.getPortraitVideoSizeOrientation());
            setRotate(true);
        } else if (isFullscreen() && isRotate() && player().isLandscape()) {
            setRequestedOrientation(PlaybackOrientation.getLandscapeVideoSizeOrientation());
            setRotate(false);
        }
    }

    private void updateVideoHeight() {
        if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
        if (mFrameHeight <= 0 || mFrameParams.height == mFrameHeight) return;
        logVideoFrame("updateVideoHeight restore from=" + mFrameParams.height + " to=" + mFrameHeight);
        mFrameParams.height = mFrameHeight;
        mBinding.video.setLayoutParams(mFrameParams);
    }

    private void logVideoFrame(String step) {
        if (mBinding == null) return;
        Log.d(SIZE_TAG, "video " + step
                + " frameParam=" + (mFrameParams == null ? "null" : mFrameParams.width + "x" + mFrameParams.height)
                + " frameHeight=" + mFrameHeight
                + " video=" + viewSize(mBinding.video)
                + " exo=" + viewSize(mBinding.exo)
                + " fullscreen=" + isFullscreen()
                + " land=" + isLand()
                + " scale=" + getScale()
                + " player=" + (service() == null ? "none" : player().getPlayerText()));
    }

    private static String viewSize(View view) {
        if (view == null) return "null";
        return view.getWidth() + "x" + view.getHeight();
    }

    private void checkEnded(boolean notify) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("audio-auto-next", "checkEnded notify=%s adapter=%d", notify, mEpisodeAdapter.getItemCount());
        checkNext(notify);
    }

    private boolean hasNextEpisode() {
        return !getAdjacentEpisode(1).isSelected();
    }

    private void setTrackVisible() {
        mBinding.control.action.discMenu.setVisibility(hasDiscMenu() ? View.VISIBLE : View.GONE);
        updateDiscMenuTools();
        mBinding.control.action.text.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.video.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
        setTitleVisible();
        applyActionButtonVisibility();
    }

    private void updateDiscMenuTools() {
        updateCustomButtonStates();
        MpvPlayer mpv = service() != null && isOwner()
                && player().getPlayer() instanceof MpvPlayer active ? active : null;
        if (mDiscMenuPlayer != mpv) {
            if (mDiscMenuPlayer != null) mDiscMenuPlayer.removeDiscMenuStateListener(mDiscMenuStateListener);
            mDiscMenuPlayer = mpv;
            if (mpv != null) mpv.addDiscMenuStateListener(mDiscMenuStateListener);
        }
        boolean visible = mpv != null && mpv.isDiscMenuActive() && !isLock() && !isInPictureInPictureMode()
                && !isVisible(mBinding.control.getRoot());
        mBinding.discTools.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.discTools.fullscreen.setImageResource(isFullscreen()
                ? R.drawable.ic_control_fullscreen_exit : R.drawable.ic_control_fullscreen);
        mBinding.discTools.fullscreen.setContentDescription(getString(isFullscreen()
                ? R.string.play_exit_fullscreen : R.string.play_fullscreen));
    }

    private void setTitleVisible() {
        boolean haveChapter = player().haveChapter();
        mBinding.control.action.chapter.setVisibility(haveChapter ? View.VISIBLE : View.GONE);
        mBinding.control.action.title.setVisibility(!haveChapter && player().haveTitle() ? View.VISIBLE : View.GONE);
        applyActionButtonVisibility();
    }

    private void setSizeText() {
        String text = player().getSizeText();
        boolean hasTitle = !TextUtils.isEmpty(mBinding.control.title.getText());
        mBinding.control.title.setVisibility(hasTitle ? View.VISIBLE : View.INVISIBLE);
        mBinding.control.size.setText(text);
        mBinding.control.size.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private MediaMetadata buildMetadata() {
        return PlayerManager.buildMetadata(mBinding.control.title.getText().toString(), "", "");
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void startFlow() {
        if (!PlayerSetting.isAutoChange()) return;
        if (!getSite().isChangeable()) return;
        if (isUseParse()) checkParse();
        else checkFlag();
    }

    private void checkParse() {
        int position = mParseAdapter.getPosition();
        boolean last = position == mParseAdapter.getItemCount() - 1;
        boolean pass = position == 0 || last;
        if (last) initParse();
        if (pass) checkFlag();
        else nextParse(position);
    }

    private void initParse() {
        if (mParseAdapter.isEmpty()) return;
        setParse(mParseAdapter.first());
    }

    private void checkFlag() {
        int position = isGone(mBinding.flag) ? -1 : mFlagAdapter.getPosition();
        if (position == mFlagAdapter.getItemCount() - 1) checkSearch(false);
        else nextFlag(position);
    }

    private void checkSearch(boolean force) {
        if (!force && !PlayerSetting.isAutoChange()) return;
        if (mQuickAdapter.isEmpty()) initSearch(mBinding.name.getText().toString(), true);
        else if (isAutoMode() || force) nextSite();
    }

    private void initSearch(String keyword, boolean auto) {
        setAutoMode(auto);
        setInitAuto(auto);
        revealManualSearch = !auto;
        startSearch(keyword);
    }

    private boolean isPass(Site item) {
        if (isAutoMode() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private void startSearch(String keyword) {
        mQuickSearchKeyword = keyword;
        mQuickAdapter.clear();
        mBinding.quick.setVisibility(View.GONE);
        if (isQuickSearchVisible()) mQuickSearchDialog.clear();
        List<Site> sites = new ArrayList<>();
        for (Site item : VodConfig.get().getSites()) if (isPass(item)) sites.add(item);
        SiteHealthStore.sortSites(sites);
        mViewModel.searchContent(sites, keyword, true);
    }

    private void setSearch(Result result) {
        List<Vod> items = result.getList();
        items.removeIf(this::mismatch);
        mBinding.quick.setVisibility(View.GONE);
        mQuickAdapter.addAll(items);
        if (isQuickSearchVisible()) mQuickSearchDialog.addAll(items);
        if (revealManualSearch && !items.isEmpty()) revealManualSearch = false;
        if (isInitAuto() && PlayerSetting.isAutoChange()) nextSite();
        if (items.isEmpty()) return;
        App.removeCallbacks(mLoadingTimeoutRunnable);
    }

    private boolean isQuickSearchVisible() {
        return mQuickSearchDialog != null && mQuickSearchDialog.isActive();
    }

    private boolean mismatch(Vod item) {
        if (getId().equals(item.getId())) return true;
        if (mBroken.contains(item.getId())) return true;
        String keyword = TextUtils.isEmpty(mQuickSearchKeyword) ? mBinding.name.getText().toString() : mQuickSearchKeyword;
        if (isAutoMode()) return !item.getName().equals(keyword);
        else return !item.getName().contains(keyword);
    }

    private void nextParse(int position) {
        Parse parse = mParseAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_parse, parse.getName()));
        onItemClick(parse);
    }

    private void nextFlag(int position) {
        Flag flag = mFlagAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
        onItemClick(flag);
    }

    private void nextSite() {
        if (mQuickAdapter.isEmpty()) return;
        int position = mQuickAdapter.getBestPosition();
        Vod item = mQuickAdapter.get(position);
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
        mQuickAdapter.remove(position);
        mBroken.add(getId());
        setInitAuto(false);
        applySearchArtwork(item);
        getDetail(item);
    }

    private void onPaused() {
        debugPlaybackControl("onPaused");
        controller().pause();
    }

    private void onPlay() {
        debugPlaybackControl("onPlay.before");
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
        debugPlaybackControl("onPlay.after");
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        Util.toggleFullscreen(this, this.fullscreen = fullscreen);
        updateDiscMenuTools();
    }

    private boolean isInitAuto() {
        return initAuto;
    }

    private void setInitAuto(boolean initAuto) {
        this.initAuto = initAuto;
    }

    private boolean isAutoMode() {
        return autoMode;
    }

    private void setAutoMode(boolean autoMode) {
        this.autoMode = autoMode;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (fullscreen && !rotate) setPadding(mBinding.control.getRoot());
        else noPadding(mBinding.control.getRoot());
    }

    private void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyItemRangeChanged(0, adapter.getItemCount()));
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onCasted() {
        player().stop();
    }

    @Override
    public void onScale(int tag) {
        mKeyDown.resetScale();
        setScale(tag);
    }

    @Override
    public void onEpisodeColumn(int column) {
        PlayerSetting.putEpisodeColumn(column);
        refreshEpisodeTitles();
    }

    @Override
    public void onCompactEpisodeTitleChanged() {
        setShortDisplay();
        refreshEpisodeTitles();
    }

    private void refreshEpisodeTitles() {
        if (mEpisodeAdapter == null) return;
        if (mFlagAdapter == null || mFlagAdapter.isEmpty()) {
            EpisodeTitleCompact.apply(mEpisodeAdapter.getItems());
            mEpisodeAdapter.refreshTitleMode();
            updateEpisodeRowSize();
            mEpisodeAdapter.notifyItemRangeChanged(0, mEpisodeAdapter.getItemCount());
        } else {
            Flag flag = getFlag();
            if (flag != null) setEpisodeItems(flag.getEpisodes());
        }
        scrollEpisodeToSelected();
        mBinding.episode.post(this::updateEpisodeViewportHeight);
        refreshEpisodeSheet();
    }

    @Override
    public void onParse(Parse item) {
        onItemClick(item);
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSetting.getSpeed()));
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSetting.getDefaultSpeed()));
        mHistory.setSpeed(player().getSpeed());
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (getEpisodeCount() == 1) onRefresh();
        else checkNext();
    }

    @Override
    public void onFlingDown() {
        if (getEpisodeCount() == 1) onRefresh();
        else checkPrev();
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.seek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTap() {
        if (isLock()) return;
        if (!isFullscreen()) {
            enterFullscreen();
        } else if (player().isPlaying()) {
            showControl();
            onPaused();
        } else {
            hideControl();
            onPlay();
        }
    }

    @Override
    public void onTouchEnd() {
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.speed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onShare(CharSequence title) {
        PlayerHelper.share(this, player().getUrl(), player().getHeaders(), title);
        setRedirect(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            preparePiP("userLeaveHint");
        } else {
            requestPiP("userLeaveHint");
        }
    }

    @Override
    public boolean onPictureInPictureRequested() {
        return requestPiP("systemRequest");
    }

    private boolean preparePiP(String reason) {
        if (isRedirect() || isPlaybackExiting()) return false;
        if (syncPiPForPlaybackMode()) return false;
        if (service() == null || !player().haveTrack(C.TRACK_TYPE_VIDEO)) return false;
        mPiP.update(this, player().getVideoWidth(), player().getVideoHeight(), getScale());
        return true;
    }

    private boolean requestPiP(String reason) {
        if (!preparePiP(reason)) return false;
        if (isLock()) App.post(this::onLock, 500);
        return enterPiP(reason);
    }

    private boolean enterPiP(String reason) {
        if (syncPiPForPlaybackMode()) return false;
        if (service() == null || !player().haveTrack(C.TRACK_TYPE_VIDEO)) return false;
        return mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), getScale());
    }

    private boolean syncPiPForPlaybackMode() {
        boolean audioMode = isAudioBackgroundMode();
        if (mPiP != null) mPiP.setAudioMode(this, audioMode);
        return audioMode;
    }

    private boolean isAudioBackgroundMode() {
        return isAudioOnly();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (!isFullscreen()) setVideoView(isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            hideControl();
            hideSheet();
        } else {
            if (isStop()) finish();
        }
        updateDiscMenuTools();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyLightStatusBarIcons();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        syncFullscreenForOrientation(newConfig.orientation);
        setupCustomActionButtons();
        applyLightStatusBarIcons();
        if (isFullscreen()) Util.hideSystemUI(this);
    }

    private void syncFullscreenForOrientation(int orientation) {
        if (!isAutoRotate() || !isPort()) {
            deferredFullscreenOrientation = Configuration.ORIENTATION_UNDEFINED;
            return;
        }
        if (service() == null) {
            deferredFullscreenOrientation = orientation;
            SpiderDebug.log("video-flow", "fullscreen orientation deferred orientation=%d reason=player-not-ready", orientation);
            return;
        }
        deferredFullscreenOrientation = Configuration.ORIENTATION_UNDEFINED;
        if (orientation == Configuration.ORIENTATION_PORTRAIT && !isRotate() && !isLock()) exitFullscreen();
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) enterFullscreen();
    }

    private void applyDeferredFullscreenOrientation() {
        int orientation = deferredFullscreenOrientation;
        if (orientation == Configuration.ORIENTATION_UNDEFINED) return;
        SpiderDebug.log("video-flow", "fullscreen orientation resume orientation=%d", orientation);
        syncFullscreenForOrientation(orientation);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isFullscreen() && hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isFullscreen() && !isLock()) {
            exitFullscreen();
        } else if (!isLock()) {
            finishVideoPlaybackFromSystemBack();
        }
    }

    public void finishVideoPlaybackFromSystemBack() {
        mViewModel.stopSearch();
        saveHistory(true);
        markPlaybackExiting();
        stopPlayback();
        if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (mCustomButtonPlayer != null) {
            mCustomButtonPlayer.removeCustomButtonStateListener(mCustomButtonStateListener);
            mCustomButtonPlayer = null;
        }
        if (mDiscMenuPlayer != null) {
            mDiscMenuPlayer.removeDiscMenuStateListener(mDiscMenuStateListener);
            mDiscMenuPlayer = null;
        }
        mClock.release();
        saveHistory(true);
        Timer.get().reset();
        App.removeCallbacks(mHideControlRunnable, mTrafficRunnable, mOrientRunnable, mLoadingTimeoutRunnable);
        mViewModel.getResult().removeObserver(mObserveDetail);
        mViewModel.getPlayer().removeObserver(mObservePlayer);
        mViewModel.getSearch().removeObserver(mObserveSearch);
        SiteHealthStore.flush();
        super.onDestroy();
    }
}
