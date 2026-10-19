package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogWebPageBinding;
import com.fongmi.android.tv.impl.WebPageListener;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.web.page.WebPage;
import com.fongmi.android.tv.web.page.WebPageManager;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 新增 / 编辑网页。保存后回调宿主 Fragment 的 {@link WebPageListener#onWebPageSaved(WebPage)}。
 */
public class WebPageEditDialog extends BaseAlertDialog {

    private static final String ARG_ID = "id";

    private DialogWebPageBinding binding;
    private WebPage page;
    private boolean append = true;

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) return;
        String url = "file:/" + path.replace(Path.rootPath(), "");
        binding.url.setText(url);
        binding.url.setSelection(url.length());
        if (TextUtils.isEmpty(text(binding.name))) {
            String fileName = new File(path).getName();
            int dot = fileName.lastIndexOf('.');
            binding.name.setText(dot > 0 ? fileName.substring(0, dot) : fileName);
        }
    });

    public static void show(Fragment fragment, @Nullable WebPage page) {
        WebPageEditDialog dialog = new WebPageEditDialog();
        Bundle args = new Bundle();
        args.putString(ARG_ID, page == null ? "" : page.getId());
        dialog.setArguments(args);
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String id = getArguments() == null ? "" : getArguments().getString(ARG_ID, "");
        page = WebPageManager.get(id);
        String title = getString(page == null ? R.string.web_page_add : R.string.web_page_edit);
        Dialog dialog = LightDialog.create(requireContext(), title, getBinding().getRoot(), getString(R.string.dialog_positive), view -> onPositive(null, 0), getString(R.string.dialog_negative), view -> dismiss());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebPageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(page == null ? R.string.web_page_add : R.string.web_page_edit).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        if (page == null) return;
        binding.name.setText(page.getName());
        binding.url.setText(page.getUrl());
        binding.trusted.setChecked(page.isTrusted());
    }

    @Override
    protected void initEvent() {
        binding.urlLayout.setEndIconOnClickListener(this::onChoose);
        binding.url.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                binding.urlLayout.setError(null);
            }
        });
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show();
    }

    private void detect(String s) {
        if (append && "h".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    private void onPositive(DialogInterface dialog, int which) {
        String url = text(binding.url);
        if (!WebPage.isValidUrl(url)) {
            binding.urlLayout.setError(getString(R.string.web_page_url_invalid));
            binding.url.requestFocus();
            return;
        }
        String name = text(binding.name);
        boolean trusted = binding.trusted.isChecked();
        WebPage result = page;
        // 编辑界面不再提供 UA / 请求头输入：新建为空，编辑时沿用已保存的值
        if (result == null) result = WebPage.create(name, url, "", null, trusted);
        else result.update(name, url, result.getUa(), result.getHeaders(), trusted);
        WebPageManager.save(result);
        if (getParentFragment() instanceof WebPageListener listener) listener.onWebPageSaved(result);
        else if (getActivity() instanceof WebPageListener listener) listener.onWebPageSaved(result);
        dismiss();
    }

    private static String text(EditText editText) {
        Editable text = editText.getText();
        return text == null ? "" : text.toString().trim();
    }
}
