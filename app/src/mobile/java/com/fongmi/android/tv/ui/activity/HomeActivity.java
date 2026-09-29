package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
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
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.HistoryFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
        private static final String STATE_CURRENT_POSITION = "currentPosition";

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;
    
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
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
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
        mBinding.navVod.setOnClickListener(v -> {
            setNavigationVisible(true);
            selectNavigation(0);
        });
        mBinding.navKeep.setOnClickListener(v -> {
            setNavigationVisible(true);
            selectNavigation(3);
        });
        mBinding.navSetting.setOnClickListener(v -> {
            setNavigationVisible(true);
            selectNavigation(1);
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
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> HistoryFragment.newInstance();
            case 4 -> SettingDanmakuFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
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
        mBinding.navSetting.setVisibility(View.VISIBLE);
        syncNavigationSelection();
    }

    public void change(int position) {
        if (position != 0) hideLoading();
        setNavigationVisible(true);
        if (position == 0 || position == 1 || position == 3) selectNavigation(position);
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
        mBinding.navVod.setSelected(position == 0);
        mBinding.navKeep.setSelected(position == 3);
        mBinding.navSetting.setSelected(position == 1);
    }

    public boolean isSettingActive() {
        return currentPosition == 1 || currentPosition == 2 || currentPosition == 4;
    }

    private void updateWindowBackground(int position) {
        int color = (position == 1 || position == 2 || position == 4) ? ResUtil.getColor(R.color.bg_setting) : ResUtil.getColor(R.color.white);
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

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
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
        selectNavigation(0);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    
    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
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
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (isLoading()) {
            hideLoading();
            return;
        } else if (mBinding.navVod.getVisibility() != View.VISIBLE) {
            setNavigation();
        
        } else if (currentPosition == 2 || currentPosition == 4 || mManager.isVisible(2) || mManager.isVisible(4)) {
            change(1);
        } else if (currentPosition == 3 || mManager.isVisible(3)) {
            if (mManager.canBack(3)) change(0);
        } else if (currentPosition == 1 || mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mChrome != null) mChrome.destroy();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
