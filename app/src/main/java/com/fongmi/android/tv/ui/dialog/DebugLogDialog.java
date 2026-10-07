package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.DiagnosticControls;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.diagnostics.DiagnosticCapture;
import com.github.catvod.crawler.diagnostics.DiagnosticCategories;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

public final class DebugLogDialog {

    private static final int COLOR_TITLE = Color.parseColor("#202124");
    private static final int COLOR_BODY = Color.parseColor("#5F6368");
    private static final int COLOR_HINT = Color.parseColor("#80868B");
    private static final int COLOR_CARD = Color.parseColor("#F6F7F9");
    private static final int COLOR_DIVIDER = Color.parseColor("#E8EAED");
    private static final int COLOR_FOCUS = Color.parseColor("#E2E5EA");
    private static final int COLOR_ACCENT = Color.parseColor("#217AF4");
    private static final int COLOR_SUCCESS = Color.parseColor("#1E8E3E");
    private static final int COLOR_SUCCESS_BG = Color.parseColor("#E6F4EA");

    private DebugLogDialog() {
    }

    public static void show(Fragment fragment) {
        show(fragment.requireActivity());
    }

    public static void show(FragmentActivity activity) {
        Server.get().start();
        String localUrl = Server.get().getAddress("/debug/logs");
        String lanUrl = Server.get().getAddress(false) + "/debug/logs";
        SpiderDebug.log("debug", "logs service ready url=%s lan=%s", localUrl, lanUrl);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(status(activity), wrap(0));
        panel.addView(addressCard(activity, lanUrl, localUrl), match(12));
        panel.addView(text(activity, activity.getString(R.string.debug_log_lan_hint), 12, COLOR_HINT), match(8));

        panel.addView(section(activity, activity.getString(R.string.debug_log_section_category)), match(20));
        panel.addView(categoryCard(activity), match(8));

        panel.addView(section(activity, activity.getString(R.string.debug_log_section_diagnose)), match(20));
        panel.addView(text(activity, "标准日志按容量轮转；深度统计只保留数值，不保存画面或声音。", 12, COLOR_HINT), match(4));
        panel.addView(diagnoseActions(activity), match(12));

        ScrollView scroll = new MaxHeightScrollView(activity, (int) (ResUtil.getScreenHeight(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.66f)));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = LightDialog.create(activity, activity.getString(R.string.setting_debug_log), scroll, activity.getString(R.string.debug_log_open_browser), v -> open(activity, localUrl), activity.getString(R.string.dialog_negative), null, activity.getString(R.string.debug_log_copy_url), v -> copy(activity, lanUrl));
        dialog.show();
    }

    // 顶部状态胶囊：绿点 + "服务已启动"
    private static View status(Context context) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(5), dp(12), dp(5));
        chip.setBackground(round(COLOR_SUCCESS_BG, 999));
        View dot = new View(context);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(COLOR_SUCCESS);
        dot.setBackground(oval);
        chip.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        MaterialTextView label = text(context, context.getString(R.string.debug_log_status_ready), 12, COLOR_SUCCESS);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        chip.addView(label, params);
        return chip;
    }

    // 地址卡片：标签小字 + 地址主文本，两条之间细分隔线
    private static View addressCard(Context context, String lanUrl, String localUrl) {
        LinearLayout card = card(context);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        addAddress(context, card, context.getString(R.string.debug_log_lan_url), lanUrl);
        card.addView(divider(context), dividerParams(10, 10, 0));
        addAddress(context, card, context.getString(R.string.debug_log_local_url), localUrl);
        return card;
    }

    private static void addAddress(Context context, LinearLayout card, String label, String url) {
        card.addView(text(context, label, 12, COLOR_HINT), match(0));
        MaterialTextView value = text(context, url, 14, COLOR_TITLE);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(value, match(3));
    }

    // 记录分类：同一张卡片内的开关行，行间细线
    private static View categoryCard(Context context) {
        LinearLayout card = card(context);
        DiagnosticCategories.Category[] categories = DiagnosticCategories.Category.values();
        for (int i = 0; i < categories.length; i++) {
            DiagnosticCategories.Category category = categories[i];
            SwitchCompat toggle = new SwitchCompat(context);
            toggle.setText(category.title);
            toggle.setTextSize(15);
            toggle.setTextColor(COLOR_TITLE);
            toggle.setMinHeight(dp(48));
            toggle.setPadding(dp(14), 0, dp(10), 0);
            toggle.setBackground(rowBackground());
            toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{COLOR_ACCENT, Color.parseColor("#F1F3F4")}));
            toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Color.parseColor("#66217AF4"), Color.parseColor("#4D5F6368")}));
            toggle.setFocusable(true);
            toggle.setChecked(DiagnosticCategories.accepts(DebugLogStore.categories(), category));
            toggle.setOnCheckedChangeListener((button, checked) -> DebugLogStore.setCategory(category, checked));
            card.addView(toggle, match(0));
            if (i < categories.length - 1) card.addView(divider(context), dividerParams(0, 0, 14));
        }
        return card;
    }

    // 故障诊断：整行"标记" + 下方两按钮并排
    private static View diagnoseActions(FragmentActivity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        MaterialButton mark = actionButton(activity, "标记此刻故障", true);
        mark.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("选择当前现象")
                .setItems(DiagnosticControls.SYMPTOMS, (d, which) -> {
                    try { DiagnosticControls.mark(DiagnosticControls.SYMPTOMS[which]); Notify.show("已标记，继续记录后 15 秒"); }
                    catch (RuntimeException error) { Notify.show(error.getMessage()); }
                }).show());
        box.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton depth = actionButton(activity, "深度统计 60 秒", false);
        depth.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("限时深度统计")
                .setMessage("对当前播放做少量低分辨率画面和 PCM 数值统计，不保存图像或声音。到期、切换播放或关闭诊断自动停止。")
                .setNegativeButton("取消", null).setPositiveButton("开启 60 秒", (d, which) -> {
                    try { DiagnosticControls.startDepth(60); Notify.show("限时统计已开启"); }
                    catch (RuntimeException error) { Notify.show(error.getMessage()); }
                }).show());
        MaterialButton stop = actionButton(activity, "停止深度统计", false);
        stop.setOnClickListener(v -> { DiagnosticCapture.stop("user-stopped"); Notify.show("深度统计已停止"); });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(44), 1);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(44), 1);
        right.leftMargin = dp(10);
        row.addView(depth, left);
        row.addView(stop, right);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        box.addView(row, rowParams);
        return box;
    }

    // emphasis=true 用浅蓝强调色，其余用对话框默认浅灰按钮
    private static MaterialButton actionButton(Context context, String text, boolean emphasis) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(44));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setCornerRadius(dp(10));
        button.setStrokeWidth(0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        if (emphasis) {
            button.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Color.WHITE, COLOR_ACCENT}));
            button.setBackgroundTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}}, new int[]{COLOR_ACCENT, Color.parseColor("#D2E3FC"), Color.parseColor("#E8F0FE")}));
        } else {
            button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_text));
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_bg));
        }
        return button;
    }

    private static MaterialTextView section(Context context, String title) {
        MaterialTextView view = text(context, title, 13, COLOR_BODY);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static MaterialTextView text(Context context, String value, int sp, int color) {
        MaterialTextView view = new MaterialTextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(COLOR_CARD, 12));
        card.setClipToOutline(true);
        return card;
    }

    private static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(COLOR_DIVIDER);
        return view;
    }

    private static LinearLayout.LayoutParams dividerParams(int topDp, int bottomDp, int startDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        params.setMarginStart(dp(startDp));
        return params;
    }

    private static StateListDrawable rowBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(COLOR_FOCUS));
        drawable.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(COLOR_FOCUS));
        drawable.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return drawable;
    }

    private static GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private static LinearLayout.LayoutParams match(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private static LinearLayout.LayoutParams wrap(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private static int dp(int value) {
        return ResUtil.dp2px(value);
    }

    private static void open(FragmentActivity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Notify.show(R.string.debug_log_no_browser);
        }
    }

    private static void copy(FragmentActivity activity, String url) {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.setting_debug_log), url));
        Notify.show(R.string.debug_log_url_copied);
    }

    // 内容区限高，保证底部操作按钮在小屏上始终可见
    private static final class MaxHeightScrollView extends ScrollView {

        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int limit = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
