package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.databinding.ActivityWebBinding;

public class WebActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private ActivityWebBinding mBinding;

    public static void start(Context context, String url) {
        start(context, url, null);
    }

    public static void start(Context context, String url, String title) {
        if (context == null || TextUtils.isEmpty(url)) return;
        Intent intent = new Intent(context, WebActivity.class);
        intent.putExtra(EXTRA_URL, url);
        if (!TextUtils.isEmpty(title)) intent.putExtra(EXTRA_TITLE, title);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        com.fongmi.android.tv.utils.Util.hideSystemUI(this);
        mBinding = ActivityWebBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        initWebView();
        initBack();

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (!TextUtils.isEmpty(url)) {
            mBinding.webView.loadUrl(url);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) com.fongmi.android.tv.utils.Util.hideSystemUI(this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = mBinding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        mBinding.webView.setFocusable(true);
        mBinding.webView.setFocusableInTouchMode(true);
        mBinding.webView.requestFocus();

        mBinding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri != null ? uri.getScheme() : "";
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (Throwable ignored) {
                    return true;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mBinding.progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mBinding.progress.setVisibility(View.GONE);
            }
        });

        mBinding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mBinding.progress.setProgress(newProgress);
                if (newProgress >= 100) {
                    mBinding.progress.setVisibility(View.GONE);
                }
            }
        });
    }

    private void initBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mBinding != null && mBinding.webView.canGoBack()) {
                    mBinding.webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) mBinding.webView.onResume();
    }

    @Override
    protected void onPause() {
        if (mBinding != null) mBinding.webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mBinding != null) {
            ViewGroup parent = (ViewGroup) mBinding.webView.getParent();
            if (parent != null) parent.removeView(mBinding.webView);
            mBinding.webView.stopLoading();
            mBinding.webView.clearHistory();
            mBinding.webView.destroy();
            mBinding = null;
        }
        super.onDestroy();
    }
}
