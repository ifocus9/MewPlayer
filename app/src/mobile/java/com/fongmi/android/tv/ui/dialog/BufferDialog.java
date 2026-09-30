package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogBufferBinding;
import com.fongmi.android.tv.impl.BufferListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class BufferDialog extends BaseAlertDialog {

    private DialogBufferBinding binding;
    private int value;

    public static void show(FragmentActivity activity) {
        new BufferDialog().show(activity.getSupportFragmentManager(), null);
    }

    public static void show(Fragment fragment) {
        new BufferDialog().show(fragment.getChildFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getString(R.string.player_buffer), getBinding().getRoot(), getString(R.string.dialog_positive), view -> {
            onPositive(null, 0);
            dismiss();
        }, getString(R.string.dialog_negative), view -> {
            onNegative(null, 0);
            dismiss();
        });
        initView();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogBufferBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.player_buffer).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, this::onNegative);
    }

    @Override
    protected void initView() {
        binding.slider.setValue(value = PlayerSetting.getBuffer());
    }

    private void onPositive(DialogInterface dialog, int which) {
        int buffer = (int) binding.slider.getValue();
        if (getParentFragment() instanceof BufferListener) ((BufferListener) getParentFragment()).setBuffer(buffer);
        else if (getActivity() instanceof BufferListener) ((BufferListener) getActivity()).setBuffer(buffer);
    }

    private void onNegative(DialogInterface dialog, int which) {
        if (getParentFragment() instanceof BufferListener) ((BufferListener) getParentFragment()).setBuffer(value);
        else if (getActivity() instanceof BufferListener) ((BufferListener) getActivity()).setBuffer(value);
    }
}
