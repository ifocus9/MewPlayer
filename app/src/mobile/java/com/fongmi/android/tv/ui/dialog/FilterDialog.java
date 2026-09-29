package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.databinding.DialogFilterBinding;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.ui.adapter.FilterAdapter;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

public class FilterDialog extends BaseBottomSheetDialog {

    private DialogFilterBinding binding;
    private FilterListener listener;
    private List<Filter> filter;

    public static FilterDialog create() {
        return new FilterDialog();
    }

    public FilterDialog filter(List<Filter> filter) {
        this.filter = filter;
        return this;
    }

    public void show(Fragment fragment) {
        for (Fragment f : fragment.getChildFragmentManager().getFragments()) if (f instanceof FilterDialog) return;
        show(fragment.getChildFragmentManager(), null);
        this.listener = (FilterListener) fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogFilterBinding.inflate(inflater, container, false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog.getWindow());
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) configureWindow(getDialog().getWindow());
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return false;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        configureWindow(dialog.getWindow());
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @Override
    protected void initView() {
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(new FilterAdapter(listener, filter));
    }

    private void configureWindow(Window window) {
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.4f;
        window.setAttributes(params);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }
}
