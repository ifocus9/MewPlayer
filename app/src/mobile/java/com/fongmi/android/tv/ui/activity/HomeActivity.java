package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.base.WebChromeHost;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.HistoryFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.ui.fragment.WebTabFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends BaseActivity implements WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    // FragmentStateManager 的位置索引（与 Fragment tag 绑定，不要重新编号）
    public static final int TAB_VOD = 0;
    public static final int TAB_SETTING = 1;
    public static final int TAB_SETTING_PLAYER = 2;
    public static final int TAB_KEEP = 3;
    public static final int TAB_SETTING_DANMAKU = 4;
    public static final int TAB_WEB = 5;

    private static final long NAV_INDICATOR_DURATION = 300;
    private static final long NAV_ICON_BOUNCE_DURATION = 320;
    private static final Interpolator NAV_INDICATOR_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;
    private int navIndicatorPosition = -1;
    
    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        updateWindowBackground(currentPosition);
        mStartupConfig = Config.vod();
        // 启动时一律 normal：网页 chrome 只在网页 Tab 可见时生效，不再按「配置 + 首页站点」恢复 edge/immersive
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState);
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        setNavigation();
        initFragment(savedInstanceState);
        initConfig();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
                outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void initEvent() {
        bindNavItem(mBinding.navVod, mBinding.navVodIcon, TAB_VOD);
        bindNavItem(mBinding.navKeep, mBinding.navKeepIcon, TAB_KEEP);
        bindNavItem(mBinding.navWeb, mBinding.navWebIcon, TAB_WEB);
        bindNavItem(mBinding.navSetting, mBinding.navSettingIcon, TAB_SETTING);
        // 底栏宽度变化（首次布局、旋转、分屏）时，选中气泡直接对齐到当前 Tab
        mBinding.navItems.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) moveNavIndicator(navIndicatorPosition, false);
        });
    }

    private void bindNavItem(View item, View icon, int position) {
        item.setOnClickListener(v -> {
            bounceNavIcon(icon);
            setNavigationVisible(true);
            selectNavigation(position);
        });
    }

    private void checkAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        VideoActivity.push(this, intent.getData().toString());
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case TAB_VOD -> VodFragment.newInstance();
            case TAB_SETTING -> SettingFragment.newInstance();
            case TAB_SETTING_PLAYER -> SettingPlayerFragment.newInstance();
            case TAB_KEEP -> HistoryFragment.newInstance();
            case TAB_SETTING_DANMAKU -> SettingDanmakuFragment.newInstance();
            case TAB_WEB -> WebTabFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(TAB_VOD);
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        changeFragment(position <= 0 ? 0 : position);
    }

    public void showLoading() {
        showLoading(getString(R.string.loading_source));
    }

    public void showLoading(String text) {
        App.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            mBinding.loadingText.setText(text);
            mBinding.loadingLayout.setVisibility(View.VISIBLE);
        });
    }

    public void hideLoading() {
        App.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            mBinding.loadingLayout.setVisibility(View.GONE);
        });
    }

    public boolean isLoading() {
        return mBinding.loadingLayout != null && mBinding.loadingLayout.getVisibility() == View.VISIBLE;
    }

    private void initConfig() {
        showLoading();
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                hideLoading();
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void setNavigation() {
        mBinding.navVod.setVisibility(View.VISIBLE);
        mBinding.navKeep.setVisibility(View.VISIBLE);
        mBinding.navWeb.setVisibility(View.VISIBLE);
        mBinding.navSetting.setVisibility(View.VISIBLE);
        syncNavigationSelection();
    }

    public void change(int position) {
        if (position != TAB_VOD) hideLoading();
        setNavigationVisible(true);
        if (position == TAB_VOD || position == TAB_SETTING || position == TAB_KEEP || position == TAB_WEB) selectNavigation(position);
        else changeFragment(position);
    }

    public void setNavigationVisible(boolean visible) {
        if (mBinding.navCard != null) {
            mBinding.navCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        } else {
            mBinding.navigation.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        mBinding.getRoot().requestApplyInsets();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideLoading();
                break;
            case PROGRESS:
                showLoading();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }


    private void selectNavigation(int position) {
        syncNavigationSelection(position);
        changeFragment(position);
    }

    private void syncNavigationSelection() {
        syncNavigationSelection(currentPosition);
    }

    private void syncNavigationSelection(int position) {
        mBinding.navVod.setSelected(position == TAB_VOD);
        mBinding.navKeep.setSelected(position == TAB_KEEP);
        mBinding.navWeb.setSelected(position == TAB_WEB);
        mBinding.navSetting.setSelected(position == TAB_SETTING);
        moveNavIndicator(position, true);
    }

    private View getNavItem(int position) {
        return switch (position) {
            case TAB_VOD -> mBinding.navVod;
            case TAB_KEEP -> mBinding.navKeep;
            case TAB_WEB -> mBinding.navWeb;
            case TAB_SETTING -> mBinding.navSetting;
            default -> null;
        };
    }

    /**
     * Telegram 风格选中气泡：在 Tab 之间滑动。
     * 子设置页（播放器 / 弹幕）没有对应 Tab，隐藏气泡；回到 Tab 时直接出现在目标位置。
     */
    private void moveNavIndicator(int position, boolean animate) {
        View indicator = mBinding.navIndicator;
        boolean visible = indicator.getVisibility() == View.VISIBLE;
        // 同一 Tab 重复同步（selectNavigation 与 changeFragment 各调一次）时不打断正在进行的滑动
        if (animate && visible && position == navIndicatorPosition) return;
        navIndicatorPosition = position;
        indicator.animate().cancel();
        View item = getNavItem(position);
        if (item == null) {
            indicator.setVisibility(View.INVISIBLE);
            return;
        }
        // 尚未完成首次布局：由 navItems 的布局回调再定位
        if (item.getWidth() == 0) return;
        ViewGroup.LayoutParams params = indicator.getLayoutParams();
        if (params.width != item.getWidth()) {
            params.width = item.getWidth();
            indicator.setLayoutParams(params);
        }
        // 气泡自带 4dp 左边距，与 navItems 的左内边距抵消
        float x = item.getLeft() - mBinding.navItems.getPaddingLeft();
        indicator.setVisibility(View.VISIBLE);
        if (animate && visible) {
            indicator.animate().translationX(x).setDuration(NAV_INDICATOR_DURATION).setInterpolator(NAV_INDICATOR_INTERPOLATOR).start();
        } else {
            indicator.setTranslationX(x);
        }
    }

    /** 点击 Tab 时图标轻微回弹 */
    private void bounceNavIcon(View icon) {
        icon.animate().cancel();
        icon.setScaleX(0.8f);
        icon.setScaleY(0.8f);
        icon.animate().scaleX(1f).scaleY(1f).setDuration(NAV_ICON_BOUNCE_DURATION).setInterpolator(new OvershootInterpolator(3f)).start();
    }

    public boolean isSettingActive() {
        return currentPosition == TAB_SETTING || currentPosition == TAB_SETTING_DANMAKU;
    }

    private void updateWindowBackground(int position) {
        int color = (position == TAB_SETTING || position == TAB_SETTING_DANMAKU) ? ResUtil.getColor(R.color.bg_setting) : ResUtil.getColor(R.color.white);
        mBinding.getRoot().setBackgroundColor(color);
    }

    private boolean changeFragment(int position) {
        boolean changed = mManager.change(position);
        if (changed) {
            currentPosition = position;
            syncNavigationSelection(position);
        }
        updateWindowBackground(position);
        refreshWebHomeChromeLayout();
        return changed;
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setChrome(payload);
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        selectNavigation(TAB_VOD);
        if (mManager.getFragment(TAB_VOD) instanceof VodFragment fragment) fragment.openVodHome();
    }

    
    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(TAB_WEB);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        for (WebChromeHost host : getWebChromeHosts()) host.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        for (WebChromeHost host : getWebChromeHosts()) host.applyWebHomeViewport(viewport);
    }

    private List<WebChromeHost> getWebChromeHosts() {
        List<WebChromeHost> hosts = new ArrayList<>();
        if (mManager == null) return hosts;
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof WebChromeHost host && fragment.isAdded()) hosts.add(host);
        }
        return hosts;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        // 网页 Tab：先退出 HTML5 全屏，再退出 edge / immersive 回到普通模式，最后才是网页后退 / 切 Tab
        if (mManager != null && mManager.isVisible(TAB_WEB) && mManager.getFragment(TAB_WEB) instanceof WebTabFragment web && web.consumeFullscreenBack()) {
            return;
        } else if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (isLoading()) {
            hideLoading();
            return;
        } else if (mBinding.navVod.getVisibility() != View.VISIBLE) {
            setNavigation();
        
        } else if (currentPosition == TAB_SETTING_PLAYER || currentPosition == TAB_SETTING_DANMAKU || mManager.isVisible(TAB_SETTING_PLAYER) || mManager.isVisible(TAB_SETTING_DANMAKU)) {
            change(TAB_SETTING);
        } else if (currentPosition == TAB_KEEP || mManager.isVisible(TAB_KEEP)) {
            if (mManager.canBack(TAB_KEEP)) change(TAB_VOD);
        } else if (currentPosition == TAB_WEB || mManager.isVisible(TAB_WEB)) {
            // 网页 Tab：先退出 HTML5 全屏视频 / 网页内后退，无可后退时回到发现页（不退出 App）
            if (mManager.canBack(TAB_WEB)) change(TAB_VOD);
        } else if (currentPosition == TAB_SETTING || mManager.isVisible(TAB_SETTING)) {
            change(TAB_VOD);
        } else if (mManager.canBack(TAB_VOD)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mChrome != null) mChrome.destroy();
        VodConfig.get().clear();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
