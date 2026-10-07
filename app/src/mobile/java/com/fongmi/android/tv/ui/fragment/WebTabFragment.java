package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentWebTabBinding;
import com.fongmi.android.tv.impl.WebPageListener;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.base.WebChromeHost;
import com.fongmi.android.tv.ui.dialog.WebPageEditDialog;
import com.fongmi.android.tv.ui.dialog.WebPageListDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.WebHomeChrome;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.fongmi.android.tv.web.page.WebPage;
import com.fongmi.android.tv.web.page.WebPageManager;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部「网页」Tab：承载用户自定义网页（本地 9988 服务或在线网页）。
 * <ul>
 *     <li>WebView 由 {@link HomeWebController#forPage} 管理；只有 trusted 网页才注入 window.fongmi；</li>
 *     <li>沉浸 / 全屏统一交给 HomeActivity 的 WebHomeChromeController（本类实现 {@link WebChromeHost} 接收模式与安全区）；</li>
 *     <li>FragmentStateManager 用 hide/show 切 Tab，Fragment 一直处于 RESUMED，因此用 onHiddenChanged 暂停 / 恢复 WebView；</li>
 *     <li>切走 Tab 或打开播放器时不销毁 WebView（player.playVodInline 需要回调页面 JS）。</li>
 * </ul>
 */
public class WebTabFragment extends BaseFragment implements HomeWebController.Listener, WebChromeHost, WebPageListener {

    private static final int NAV_CAPSULE_HEIGHT_DP = 56;
    private static final int NAV_CAPSULE_MARGIN_DP = 18;
    private static final String PAUSE_MEDIA = "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});}catch(e){}})();";

    private final ActivityResultLauncher<Intent> mFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onFileResult);

    private FragmentWebTabBinding mBinding;
    private HomeWebController mWeb;
    private WebPage mPage;
    private WebHomeViewport mViewport = WebHomeViewport.EMPTY;
    private String mChromeMode = WebHomeChrome.NORMAL;
    private ValueCallback<Uri[]> mFileCallback;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private boolean mCustomViewImmersive;

    public static WebTabFragment newInstance() {
        return new WebTabFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentWebTabBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mWeb = HomeWebController.forPage(requireActivity(), mBinding.web, this);
        syncChrome();
        loadPage(WebPageManager.getDefault(), false);
    }

    @Override
    protected void initEvent() {
        mBinding.pageCapsule.setOnClickListener(view -> WebPageListDialog.show(this, mPage == null ? "" : mPage.getId()));
        mBinding.fullscreen.setOnClickListener(view -> onFullscreen());
        mBinding.refresh.setOnClickListener(view -> onRefresh());
        mBinding.add.setOnClickListener(view -> WebPageEditDialog.show(this, null));
        mBinding.emptyAdd.setOnClickListener(view -> WebPageEditDialog.show(this, null));
        mBinding.retry.setOnClickListener(view -> onRetry());
    }

    private void loadPage(WebPage page, boolean force) {
        if (mBinding == null || mWeb == null) return;
        mPage = page;
        hideError();
        if (page == null) {
            mBinding.title.setText(R.string.nav_web);
            mBinding.progress.setVisibility(View.GONE);
            mBinding.empty.setVisibility(View.VISIBLE);
            mWeb.evaluate(PAUSE_MEDIA, null);
            mWeb.hide();
            return;
        }
        mBinding.empty.setVisibility(View.GONE);
        mBinding.title.setText(page.getDisplayName());
        mWeb.loadPage(page, force);
    }

    private void onFullscreen() {
        HomeActivity activity = homeActivity();
        if (activity != null && mPage != null) activity.setWebHomeLegacyToolbar(false);
    }

    private void onRefresh() {
        if (mPage == null || mWeb == null) return;
        hideError();
        mWeb.reload();
    }

    private void onRetry() {
        hideError();
        if (mPage != null && mWeb != null) mWeb.loadPage(mPage, true);
    }

    private void showError(String description) {
        if (mBinding == null) return;
        mBinding.progress.setVisibility(View.GONE);
        mBinding.errorText.setText(description == null ? "" : description);
        mBinding.errorText.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);
        mBinding.error.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (mBinding != null) mBinding.error.setVisibility(View.GONE);
    }

    private void updateContentInsets() {
        if (mBinding == null) return;
        // WebView 始终铺满到屏幕底部，底部胶囊悬浮在网页之上；
        // normal 模式下把「胶囊 + 底边距」叠加进 --fm-safe-bottom，网页用它给底部留白即可不被遮挡。
        boolean hidden = WebHomeChrome.hidesNativeChrome(mChromeMode);
        mBinding.toolbar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (mBinding.content.getPaddingBottom() != 0) mBinding.content.setPadding(0, 0, 0, 0);
        if (mWeb != null) mWeb.setViewport(pageViewport());
    }

    private WebHomeViewport pageViewport() {
        if (WebHomeChrome.hidesNativeChrome(mChromeMode)) return mViewport;
        return mViewport.withBottomOverlay(ResUtil.dp2px(NAV_CAPSULE_HEIGHT_DP + NAV_CAPSULE_MARGIN_DP));
    }

    private void syncChrome() {
        HomeActivity activity = homeActivity();
        if (activity == null) return;
        applyWebHomeChrome(activity.getWebHomeChromeMode());
        applyWebHomeViewport(activity.getWebHomeViewport());
    }

    private HomeActivity homeActivity() {
        return getActivity() instanceof HomeActivity ? (HomeActivity) getActivity() : null;
    }

    // ---- WebChromeHost ----

    @Override
    public void applyWebHomeChrome(String mode) {
        mChromeMode = WebHomeChrome.normalize(mode, WebHomeChrome.NORMAL);
        // 全屏视频期间若沉浸被外部（返回键 consumeBack）恢复，则同时退出全屏视频
        if (mCustomView != null && mCustomViewImmersive && !WebHomeChrome.IMMERSIVE.equals(mChromeMode)) {
            mCustomViewImmersive = false;
            exitCustomView(true);
        }
        updateContentInsets();
    }

    @Override
    public void applyWebHomeViewport(WebHomeViewport viewport) {
        mViewport = viewport == null ? WebHomeViewport.EMPTY : viewport;
        updateContentInsets();
    }

    // ---- 生命周期 ----

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (mWeb == null) return;
        if (hidden) {
            exitCustomView(true);
            mWeb.evaluate(PAUSE_MEDIA, null);
            mWeb.onPause();
        } else {
            mWeb.onResume();
            syncChrome();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWeb != null && !isHidden()) mWeb.onResume();
    }

    @Override
    public void onPause() {
        if (mWeb != null && !isHidden()) mWeb.onPause();
        super.onPause();
    }

    /**
     * 返回键最先处理：HTML5 全屏视频（含网页自动 requestFullscreen）时先退出全屏，回到网页本身。
     */
    public boolean consumeFullscreenBack() {
        if (mCustomView == null) return false;
        exitCustomView(true);
        return true;
    }

    @Override
    public boolean canBack() {
        if (mCustomView != null) {
            exitCustomView(true);
            return false;
        }
        return mWeb == null || !mWeb.handleBack();
    }

    @Override
    public void onDestroyView() {
        exitCustomView(true);
        if (mFileCallback != null) {
            mFileCallback.onReceiveValue(null);
            mFileCallback = null;
        }
        if (mWeb != null) mWeb.destroy();
        mWeb = null;
        mBinding = null;
        super.onDestroyView();
    }

    // ---- HomeWebController.Listener ----

    @Override
    public void onWebLoading() {
        if (mBinding == null) return;
        hideError();
        mBinding.progress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onWebReady() {
        if (mBinding != null) mBinding.progress.setVisibility(View.GONE);
    }

    @Override
    public void onWebPageError(int code, String description) {
        showError(description);
    }

    @Override
    public void setToolbar(boolean visible) {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeLegacyToolbar(visible);
    }

    @Override
    public void setChrome(JsonObject payload) {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeChrome(payload);
    }

    @Override
    public void restoreChrome() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.restoreWebHomeChrome();
    }

    @Override
    public WebHomeViewport getViewport() {
        return pageViewport();
    }

    @Override
    public void openVod() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.openVod();
    }

    @Override
    public void openSetting() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.change(HomeActivity.TAB_SETTING);
    }

    @Override
    public boolean onShowFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (mFileCallback != null) mFileCallback.onReceiveValue(null);
        mFileCallback = callback;
        try {
            mFileLauncher.launch(params.createIntent());
            return true;
        } catch (ActivityNotFoundException e) {
            mFileCallback = null;
            Notify.show(R.string.web_page_no_app);
            return false;
        }
    }

    private void onFileResult(ActivityResult result) {
        if (mFileCallback == null) return;
        Uri[] uris = null;
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                List<Uri> items = new ArrayList<>();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) items.add(uri);
                }
                uris = items.toArray(new Uri[0]);
            } else {
                uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), data);
            }
        }
        mFileCallback.onReceiveValue(uris);
        mFileCallback = null;
    }

    @Override
    public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (mBinding == null || mCustomView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        mCustomView = view;
        mCustomCallback = callback;
        mBinding.fullscreenContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mBinding.fullscreenContainer.setVisibility(View.VISIBLE);
        HomeActivity activity = homeActivity();
        // 先置标记再切换沉浸：setWebHomeLegacyToolbar 会同步回调 applyWebHomeChrome
        mCustomViewImmersive = activity != null && !WebHomeChrome.IMMERSIVE.equals(mChromeMode);
        if (mCustomViewImmersive) activity.setWebHomeLegacyToolbar(false);
    }

    @Override
    public void onHideCustomView() {
        exitCustomView(false);
    }

    /**
     * @param notify 是否通知网页退出全屏（由 WebView 发起的 onHideCustomView 无需再通知）
     */
    private void exitCustomView(boolean notify) {
        if (mCustomView == null) return;
        View view = mCustomView;
        WebChromeClient.CustomViewCallback callback = mCustomCallback;
        mCustomView = null;
        mCustomCallback = null;
        if (mBinding != null) {
            mBinding.fullscreenContainer.removeView(view);
            mBinding.fullscreenContainer.setVisibility(View.GONE);
        }
        if (mCustomViewImmersive) {
            mCustomViewImmersive = false;
            HomeActivity activity = homeActivity();
            if (activity != null) activity.restoreWebHomeChrome();
        }
        if (notify && callback != null) callback.onCustomViewHidden();
    }

    // ---- WebPageListener ----

    @Override
    public void onWebPageSaved(WebPage page) {
        if (page == null) return;
        WebPage saved = WebPageManager.get(page.getId());
        loadPage(saved == null ? WebPageManager.getDefault() : saved, true);
    }

    @Override
    public void onWebPageSelected(WebPage page) {
        if (page != null) loadPage(page, false);
    }

    @Override
    public void onWebPagesChanged() {
        WebPage current = mPage == null ? null : WebPageManager.get(mPage.getId());
        if (current == null) loadPage(WebPageManager.getDefault(), false);
        else if (mBinding != null) mBinding.title.setText(current.getDisplayName());
    }
}
