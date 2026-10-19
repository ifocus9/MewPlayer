package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.databinding.DialogTmdbBinding;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class TmdbDialog extends BaseAlertDialog {

    public interface Listener {
        void onTmdbSaved();
    }

    private DialogTmdbBinding mBinding;
    private Listener mListener;

    public static void show(FragmentActivity activity) {
        new TmdbDialog().show(activity.getSupportFragmentManager(), null);
    }

    public static void show(Fragment fragment, Listener listener) {
        TmdbDialog dialog = new TmdbDialog();
        dialog.mListener = listener;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getString(R.string.setting_tmdb), getBinding().getRoot(),
                getString(R.string.dialog_positive), view -> onPositive(null, 0),
                getString(R.string.dialog_negative), view -> dismiss());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = DialogTmdbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                .setTitle(R.string.setting_tmdb)
                .setView(getBinding().getRoot())
                .setPositiveButton(R.string.dialog_positive, this::onPositive)
                .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        mBinding.enableSwitch.setChecked(Setting.isTmdbEnable());
        mBinding.apiKeyText.setText(Setting.getTmdbApiKey());
        mBinding.apiBaseText.setText(Setting.getTmdbApiBase());
        mBinding.imageBaseText.setText(Setting.getTmdbImageBase());
    }

    @Override
    protected void initEvent() {
        mBinding.btnTestApi.setOnClickListener(v -> testApi());
        mBinding.btnTestImage.setOnClickListener(v -> testImage());
    }

    private void syncTempSettings() {
        if (mBinding.apiKeyText.getText() != null) {
            Setting.putTmdbApiKey(mBinding.apiKeyText.getText().toString().trim());
        }
        if (mBinding.apiBaseText.getText() != null) {
            Setting.putTmdbApiBase(mBinding.apiBaseText.getText().toString().trim());
        }
        if (mBinding.imageBaseText.getText() != null) {
            Setting.putTmdbImageBase(mBinding.imageBaseText.getText().toString().trim());
        }
    }

    private void setTesting(boolean testing) {
        if (mBinding == null) return;
        mBinding.btnTestApi.setEnabled(!testing);
        mBinding.btnTestImage.setEnabled(!testing);
    }

    private void testApi() {
        syncTempSettings();
        if (TextUtils.isEmpty(Setting.getTmdbApiKey())) {
            showTestResult("请先输入 API Key 或 Token", State.ERROR);
            return;
        }
        setTesting(true);
        showTestResult(getString(R.string.tmdb_testing), State.TESTING);
        TmdbApi.get().testConnectivity(new TmdbApi.ConnectCallback() {
            @Override
            public void onSuccess(String message) {
                App.post(() -> {
                    setTesting(false);
                    showTestResult(message, State.SUCCESS);
                });
            }

            @Override
            public void onError(String error) {
                App.post(() -> {
                    setTesting(false);
                    showTestResult(error, State.ERROR);
                });
            }
        });
    }

    private void testImage() {
        syncTempSettings();
        setTesting(true);
        showTestResult(getString(R.string.tmdb_testing), State.TESTING);
        TmdbApi.get().testImageCdn(new TmdbApi.ConnectCallback() {
            @Override
            public void onSuccess(String message) {
                App.post(() -> {
                    setTesting(false);
                    showTestResult(message, State.SUCCESS);
                });
            }

            @Override
            public void onError(String error) {
                App.post(() -> {
                    setTesting(false);
                    showTestResult(error, State.ERROR);
                });
            }
        });
    }

    private enum State {
        TESTING, SUCCESS, ERROR
    }

    private void showTestResult(String text, State state) {
        if (mBinding == null) return;
        mBinding.testResultText.setVisibility(View.VISIBLE);
        mBinding.testResultText.setText(text);
        int color;
        switch (state) {
            case SUCCESS:
                color = 0xFF2E7D32; // 质感深绿，浅色/深色背景均清晰易读
                break;
            case ERROR:
                color = 0xFFD32F2F; // 红色醒目警示
                break;
            case TESTING:
            default:
                color = 0xFF1E88E5; // 进度蓝
                break;
        }
        mBinding.testResultText.setTextColor(color);
    }

    private void onPositive(DialogInterface dialog, int which) {
        syncTempSettings();
        Setting.putTmdbEnable(mBinding.enableSwitch.isChecked());
        if (mListener != null) mListener.onTmdbSaved();
        dismiss();
    }
}
