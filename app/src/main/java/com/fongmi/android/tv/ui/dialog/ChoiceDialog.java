package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import java.util.Arrays;

public final class ChoiceDialog extends DialogFragment {

    private CharSequence title;
    private CharSequence message;
    private String positive;
    private String negative;
    private String neutral;
    private CharSequence[] items;
    private boolean[] checked;
    private int selected = -1;
    private boolean multi;
    private boolean showCancel = true;
    private boolean dismissOnChoice = true;
    private OnChoice choice;
    private OnApply apply;
    private OnItemEnabled itemEnabled;
    private OnNeutral neutralAction;
    private Runnable positiveAction;

    public interface OnChoice {
        void onChoice(int which);
    }

    public interface OnApply {
        void onApply(boolean[] checked);
    }

    public interface OnItemEnabled {
        boolean isEnabled(int which, boolean[] checked);
    }

    public interface OnNeutral {
        CharSequence onNeutral();
    }

    public static void showSingle(Fragment fragment, int titleRes, CharSequence[] items, int selected, OnChoice choice) {
        showSingle(fragment.getChildFragmentManager(), fragment.getString(titleRes), items, selected, choice);
    }

    public static void showSingle(FragmentActivity activity, int titleRes, CharSequence[] items, int selected, OnChoice choice) {
        showSingle(activity.getSupportFragmentManager(), activity.getString(titleRes), items, selected, choice);
    }

    public static void showSingleNoCancel(Fragment fragment, int titleRes, CharSequence[] items, int selected, OnChoice choice) {
        showSingle(fragment.getChildFragmentManager(), fragment.getString(titleRes), items, selected, false, choice);
    }

    public static void showSingleNoCancel(FragmentActivity activity, int titleRes, CharSequence[] items, int selected, OnChoice choice) {
        showSingle(activity.getSupportFragmentManager(), activity.getString(titleRes), items, selected, false, choice);
    }

    public static void showSingle(FragmentManager manager, CharSequence title, CharSequence[] items, int selected, OnChoice choice) {
        showSingle(manager, title, items, selected, true, choice);
    }

    private static void showSingle(FragmentManager manager, CharSequence title, CharSequence[] items, int selected, boolean showCancel, OnChoice choice) {
        ChoiceDialog dialog = new ChoiceDialog();
        dialog.title = title;
        dialog.items = items == null ? new CharSequence[0] : Arrays.copyOf(items, items.length);
        dialog.selected = selected;
        dialog.showCancel = showCancel;
        dialog.choice = choice;
        dialog.show(manager, ChoiceDialog.class.getSimpleName());
    }

    public static void showSingle(FragmentManager manager, CharSequence title, CharSequence[] items, int selected, String neutral, OnNeutral neutralAction, OnChoice choice) {
        ChoiceDialog dialog = new ChoiceDialog();
        dialog.title = title;
        dialog.items = items == null ? new CharSequence[0] : Arrays.copyOf(items, items.length);
        dialog.selected = selected;
        dialog.neutral = neutral;
        dialog.neutralAction = neutralAction;
        dialog.choice = choice;
        dialog.positive = ResUtil.getString(R.string.dialog_positive);
        dialog.dismissOnChoice = false;
        dialog.show(manager, ChoiceDialog.class.getSimpleName());
    }

    public static void showMulti(FragmentActivity activity, int titleRes, CharSequence[] items, boolean[] checked, OnApply apply) {
        showMulti(activity.getSupportFragmentManager(), activity.getString(titleRes), items, checked, apply);
    }

    public static void showMulti(Fragment fragment, int titleRes, CharSequence[] items, boolean[] checked, OnApply apply) {
        showMulti(fragment.getChildFragmentManager(), fragment.getString(titleRes), items, checked, apply);
    }

    public static void showMulti(FragmentManager manager, CharSequence title, CharSequence[] items, boolean[] checked, OnApply apply) {
        showMulti(manager, title, items, checked, null, apply);
    }

    public static void showMulti(FragmentManager manager, CharSequence title, CharSequence[] items, boolean[] checked, OnItemEnabled itemEnabled, OnApply apply) {
        ChoiceDialog dialog = new ChoiceDialog();
        dialog.title = title;
        dialog.items = items == null ? new CharSequence[0] : Arrays.copyOf(items, items.length);
        dialog.checked = checked == null ? new boolean[dialog.items.length] : Arrays.copyOf(checked, dialog.items.length);
        dialog.multi = true;
        dialog.apply = apply;
        dialog.itemEnabled = itemEnabled;
        dialog.positive = ResUtil.getString(R.string.dialog_positive);
        dialog.negative = ResUtil.getString(R.string.dialog_negative);
        dialog.show(manager, ChoiceDialog.class.getSimpleName());
    }

    public static void showConfirm(FragmentActivity activity, int titleRes, CharSequence message, Runnable positiveAction) {
        showConfirm(activity, titleRes, message, R.string.dialog_positive, positiveAction);
    }

    public static void showConfirm(FragmentActivity activity, int titleRes, CharSequence message, int positiveRes, Runnable positiveAction) {
        ChoiceDialog dialog = new ChoiceDialog();
        dialog.title = activity.getString(titleRes);
        dialog.message = message;
        dialog.positive = activity.getString(positiveRes);
        dialog.negative = activity.getString(R.string.dialog_negative);
        dialog.positiveAction = positiveAction;
        dialog.show(activity.getSupportFragmentManager(), ChoiceDialog.class.getSimpleName());
    }

    public static void showConfirm(Fragment fragment, int titleRes, CharSequence message, Runnable positiveAction) {
        showConfirm(fragment, titleRes, message, R.string.dialog_positive, positiveAction);
    }

    public static void showConfirm(Fragment fragment, int titleRes, CharSequence message, int positiveRes, Runnable positiveAction) {
        showConfirm(fragment.getChildFragmentManager(), fragment.getString(titleRes), message, fragment.getString(positiveRes), positiveAction);
    }

    public static void showConfirm(FragmentManager manager, CharSequence title, CharSequence message, String positive, Runnable positiveAction) {
        ChoiceDialog dialog = new ChoiceDialog();
        dialog.title = title;
        dialog.message = message;
        dialog.positive = positive;
        dialog.negative = ResUtil.getString(R.string.dialog_negative);
        dialog.positiveAction = positiveAction;
        dialog.show(manager, ChoiceDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createView(LayoutInflater.from(requireContext())));
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        params.width = Math.min((int) (screenWidth * (land ? 0.48f : 0.86f)), dp(land ? 400 : 360));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.45f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        window.getDecorView().post(this::focusSelectedItem);
    }

    private View createView(LayoutInflater inflater) {
        if (showCancel && !multi && items != null && items.length > 0 && negative == null) negative = getString(R.string.dialog_negative);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        int vertical = dp(22);
        int horizontal = dp(actionCount() >= 3 ? 18 : 22);
        root.setPadding(horizontal, vertical, horizontal, vertical);

        MaterialTextView titleView = new MaterialTextView(requireContext());
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#1F2329"));
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setSingleLine(false);
        root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (message != null) addMessage(root);
        if (items != null && items.length > 0) addItems(root);
        if (positive != null || negative != null) addActions(root);
        return root;
    }

    private void addMessage(LinearLayout root) {
        MaterialTextView messageView = new MaterialTextView(requireContext());
        messageView.setText(message);
        messageView.setTextColor(Color.parseColor("#5F6368"));
        messageView.setTextSize(14);
        messageView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        root.addView(messageView, params);
    }

    private void addItems(LinearLayout root) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("choice_list");
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < items.length; i++) list.addView(createItem(i));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        int maxHeight = positive != null || negative != null || neutral != null ? dp(300) : dp(360);
        params.height = Math.min(maxHeight, Math.max(dp(56), items.length * dp(54)));
        root.addView(scroll, params);
    }

    private View createItem(int position) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setSingleLine(false);
        button.setMinHeight(dp(44));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setCornerRadius(dp(10));
        setItemEnabled(button, position);
        button.setText(itemText(position));
        button.setTextSize(15);
        styleItem(button, position);
        button.setOnFocusChangeListener((view, hasFocus) -> styleItem(button, position));
        button.setOnClickListener(view -> onItemClick(position));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        params.bottomMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private CharSequence itemText(int position) {
        return items[position];
    }

    private boolean itemSelected(int position) {
        return multi ? position < checked.length && checked[position] : position == selected;
    }

    private boolean itemEnabled(int position) {
        return itemEnabled == null || itemEnabled.isEnabled(position, Arrays.copyOf(checked, checked.length));
    }

    private void setItemEnabled(MaterialButton button, int position) {
        boolean enabled = itemEnabled(position);
        button.setEnabled(enabled);
        button.setFocusable(enabled);
        button.setFocusableInTouchMode(enabled && Util.isLeanback());
    }

    private void styleItem(MaterialButton button, int position) {
        if (!itemEnabled(position)) {
            button.setTextColor(ColorStateList.valueOf(Color.parseColor("#9AA0A6")));
            button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F4F5F7")));
            button.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#E5E7EB")));
            button.setStrokeWidth(dp(1));
            return;
        }
        boolean on = itemSelected(position);
        boolean focused = button.isFocused();
        int text = focused ? Color.WHITE : on ? Color.parseColor("#217AF4") : Color.parseColor("#2B303B");
        int bg = focused ? Color.parseColor("#217AF4") : on ? Color.parseColor("#EEF5FE") : Color.parseColor("#F7F8FA");
        int stroke = focused ? Color.parseColor("#217AF4") : on ? Color.parseColor("#217AF4") : Color.parseColor("#EAECEF");
        button.setTextColor(ColorStateList.valueOf(text));
        button.setBackgroundTintList(ColorStateList.valueOf(bg));
        button.setStrokeColor(ColorStateList.valueOf(stroke));
        button.setStrokeWidth(dp(1));
        if (on) {
            button.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            button.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void focusSelectedItem() {
        if (!Util.isLeanback() || multi || selected < 0) return;
        View root = viewRoot();
        View listView = root == null ? null : root.findViewWithTag("choice_list");
        if (!(listView instanceof ViewGroup list) || selected >= list.getChildCount()) return;
        list.getChildAt(selected).requestFocus();
    }

    private void onItemClick(int position) {
        if (!itemEnabled(position)) return;
        if (multi) {
            if (position >= 0 && position < checked.length) checked[position] = !checked[position];
            View root = viewRoot();
            refreshItems(root == null ? null : root.findViewWithTag("choice_list"));
        } else {
            if (choice != null) choice.onChoice(position);
            selected = position;
            if (dismissOnChoice) dismiss();
            else {
                View root = viewRoot();
                refreshItems(root == null ? null : root.findViewWithTag("choice_list"));
            }
        }
    }

    private View viewRoot() {
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        return window == null ? null : window.getDecorView();
    }

    private void refreshItems(ViewGroup list) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof MaterialButton button) {
                setItemEnabled(button, i);
                button.setText(itemText(i));
                styleItem(button, i);
            }
        }
    }

    private void addActions(LinearLayout root) {
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean compact = actionCount() >= 3;
        int index = 0;
        if (neutral != null) actions.addView(actionButton(neutral, false, view -> {
            if (neutralAction == null || !(view instanceof MaterialButton button)) return;
            CharSequence next = neutralAction.onNeutral();
            if (next != null) button.setText(next);
        }, compact, index++ == 0));
        if (negative != null) actions.addView(actionButton(negative, false, view -> dismiss(), compact, index++ == 0));
        if (positive != null) actions.addView(actionButton(positive, true, view -> {
            if (multi && apply != null) apply.onApply(Arrays.copyOf(checked, checked.length));
            if (positiveAction != null) positiveAction.run();
            dismiss();
        }, compact, index == 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        root.addView(actions, params);
    }

    private int actionCount() {
        return (positive == null ? 0 : 1) + (negative == null ? 0 : 1) + (neutral == null ? 0 : 1);
    }

    private MaterialButton actionButton(String text, boolean primary, View.OnClickListener listener, boolean compact, boolean first) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(compact ? 13 : 14);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(compact ? 8 : 16), 0, dp(compact ? 8 : 16), 0);
        if (compact) TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(button, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setMinWidth(compact ? 0 : dp(80));
        button.setMinimumWidth(0);
        button.setMinHeight(dp(40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(10));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setTextColor(ContextCompat.getColorStateList(requireContext(), primary ? R.color.dialog_primary_button_text : R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), primary ? R.color.dialog_primary_button_bg : R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = compact ? new LinearLayout.LayoutParams(0, dp(40), 1) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.leftMargin = first ? 0 : dp(compact ? 6 : 10);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
