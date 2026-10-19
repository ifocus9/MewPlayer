package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.api.tmdb.TmdbCache;
import com.fongmi.android.tv.api.tmdb.bean.TmdbDetail;
import com.fongmi.android.tv.api.tmdb.bean.TmdbMedia;
import com.fongmi.android.tv.api.tmdb.bean.TmdbResult;
import com.fongmi.android.tv.databinding.DialogTmdbRematchBinding;
import com.fongmi.android.tv.ui.adapter.TmdbSearchResultAdapter;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 手动搜索与重新匹配弹窗。
 * 当自动刮削未命中或匹配错误时，用户可输入片名自主搜索并选取正确影视作品。
 */
public class TmdbRematchDialog extends BaseBottomSheetDialog implements TmdbSearchResultAdapter.OnClickListener {

    public interface OnRematchListener {
        void onRematched(TmdbDetail detail);
    }

    private DialogTmdbRematchBinding mBinding;
    private TmdbSearchResultAdapter mAdapter;
    private OnRematchListener mListener;
    private String mInitialKeyword;
    private String mOriginalVodName;

    public static TmdbRematchDialog create(String initialKeyword, String originalVodName, OnRematchListener listener) {
        TmdbRematchDialog dialog = new TmdbRematchDialog();
        dialog.mInitialKeyword = initialKeyword;
        dialog.mOriginalVodName = originalVodName;
        dialog.mListener = listener;
        return dialog;
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) {
            if (f instanceof TmdbRematchDialog) return;
        }
        show(activity.getSupportFragmentManager(), "TmdbRematchDialog");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = DialogTmdbRematchBinding.inflate(inflater, container, false);
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected void initView() {
        mAdapter = new TmdbSearchResultAdapter();
        mAdapter.setListener(this);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mBinding.recycler.setAdapter(mAdapter);

        if (!TextUtils.isEmpty(mInitialKeyword)) {
            mBinding.keyword.setText(mInitialKeyword);
            mBinding.keyword.setSelection(mInitialKeyword.length());
            mBinding.clear.setVisibility(View.VISIBLE);
            search(mInitialKeyword);
        }
    }

    @Override
    protected void initEvent() {
        mBinding.close.setOnClickListener(v -> dismiss());

        mBinding.clear.setOnClickListener(v -> {
            mBinding.keyword.setText("");
            mBinding.clear.setVisibility(View.GONE);
            mAdapter.clear();
            mBinding.empty.setVisibility(View.GONE);
        });

        mBinding.keyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mBinding.clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        mBinding.keyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(mBinding.keyword.getText().toString());
                return true;
            }
            return false;
        });

        mBinding.searchBtn.setOnClickListener(v -> search(mBinding.keyword.getText().toString()));
    }

    private void search(String keyword) {
        if (TextUtils.isEmpty(keyword) || keyword.trim().isEmpty()) {
            Notify.show(R.string.tmdb_rematch_hint);
            return;
        }

        mBinding.loading.setVisibility(View.VISIBLE);
        mBinding.empty.setVisibility(View.GONE);
        mAdapter.clear();

        TmdbApi.get().searchMulti(keyword.trim(), new TmdbApi.SearchCallback() {
            @Override
            public void onSuccess(TmdbResult result) {
                App.post(() -> {
                    if (!isAdded() || mBinding == null) return;
                    mBinding.loading.setVisibility(View.GONE);
                    List<TmdbMedia> validMedia = new ArrayList<>();
                    if (result != null && result.getResults() != null) {
                        for (TmdbMedia m : result.getResults()) {
                            if (m != null && !TextUtils.isEmpty(m.getDisplayTitle())) {
                                validMedia.add(m);
                            }
                        }
                    }
                    if (validMedia.isEmpty()) {
                        mBinding.empty.setVisibility(View.VISIBLE);
                    } else {
                        mBinding.empty.setVisibility(View.GONE);
                        mAdapter.setItems(validMedia);
                    }
                });
            }

            @Override
            public void onError(String message) {
                App.post(() -> {
                    if (!isAdded() || mBinding == null) return;
                    mBinding.loading.setVisibility(View.GONE);
                    mBinding.empty.setVisibility(View.VISIBLE);
                    Notify.show(message);
                });
            }
        });
    }

    @Override
    public void onItemClick(TmdbMedia item) {
        if (item == null) return;
        mBinding.loading.setVisibility(View.VISIBLE);

        TmdbApi.get().fetchDetail(item.getMediaType(), item.getId(), new TmdbApi.DetailCallback() {
            @Override
            public void onSuccess(TmdbDetail detail) {
                if (detail == null) return;

                // 写入缓存，解除旧片名可能存在的负缓存
                String rawName = !TextUtils.isEmpty(mOriginalVodName) ? mOriginalVodName : detail.getDisplayTitle();
                String cleaned = TmdbApi.cleanTitle(rawName);
                if (!TextUtils.isEmpty(cleaned)) {
                    TmdbCache.get().removeNegative(cleaned);
                    TmdbCache.get().putDetailByTitle(cleaned, detail);
                }
                TmdbCache.get().putDetail(item.getMediaType(), item.getId(), detail);

                App.post(() -> {
                    if (!isAdded() || mBinding == null) return;
                    mBinding.loading.setVisibility(View.GONE);
                    if (mListener != null) {
                        mListener.onRematched(detail);
                    }
                    Notify.show(getString(R.string.tmdb_rematch_success, detail.getDisplayTitle()));
                    dismiss();
                });
            }

            @Override
            public void onError(String message) {
                App.post(() -> {
                    if (!isAdded() || mBinding == null) return;
                    mBinding.loading.setVisibility(View.GONE);
                    Notify.show(message);
                });
            }
        });
    }
}
