package com.fongmi.android.tv.ui.custom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

import java.util.Date;

/**
 * 播放器全屏时右上角显示的系统电量 + 时间。
 * 全屏会隐藏系统状态栏，这里自行监听电量与分钟变化广播；仅在 attach 到窗口期间注册。
 */
public class BatteryTimeView extends LinearLayout {

    private final BatteryIcon battery;
    private final MaterialTextView time;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) updateBattery(intent);
            else updateTime();
        }
    };

    public BatteryTimeView(Context context) {
        this(context, null);
    }

    public BatteryTimeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        battery = new BatteryIcon(context);
        // 内容整体约 20dp 高，与左侧 24dp 控制图标的字形高度一致；外层在布局中占 48dp 方格居中
        addView(battery, new LayoutParams(dp(20), dp(10)));
        time = new MaterialTextView(context);
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        time.setSingleLine(true);
        time.setIncludeFontPadding(false);
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(1);
        addView(time, params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        register();
    }

    @Override
    protected void onDetachedFromWindow() {
        unregister();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // 重新可见时立即刷新，避免显示过期的时间
        if (visibility == VISIBLE && registered) updateTime();
    }

    private void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        try {
            // ACTION_BATTERY_CHANGED 是粘性广播，注册时直接返回当前电量
            Intent sticky = getContext().registerReceiver(receiver, filter);
            registered = true;
            if (sticky != null) updateBattery(sticky);
        } catch (Throwable ignored) {
        }
        updateTime();
    }

    private void unregister() {
        if (!registered) return;
        try {
            getContext().unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    private void updateTime() {
        time.setText(DateFormat.getTimeFormat(getContext()).format(new Date()));
    }

    private void updateBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int percent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        battery.set(percent, charging);
        battery.setContentDescription(percent < 0 ? null : percent + "%");
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** 画一个电池外框 + 按电量比例填充的内芯。 */
    private static class BatteryIcon extends View {

        private static final int COLOR_LOW = Color.parseColor("#FF5252");
        private static final int COLOR_CHARGING = Color.parseColor("#4CD964");

        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int percent = -1;
        private boolean charging;

        BatteryIcon(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
        }

        void set(int percent, boolean charging) {
            if (this.percent == percent && this.charging == charging) return;
            this.percent = percent;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float sw = 1.2f * density;
            float tipW = 1.5f * density;
            float radius = 2f * density;
            stroke.setStrokeWidth(sw);

            // 外框
            float bodyRight = w - tipW - density * 0.5f;
            rect.set(sw / 2, sw / 2, bodyRight - sw / 2, h - sw / 2);
            canvas.drawRoundRect(rect, radius, radius, stroke);

            // 正极凸起
            fill.setColor(Color.WHITE);
            float tipH = h * 0.4f;
            rect.set(bodyRight, (h - tipH) / 2, w, (h + tipH) / 2);
            canvas.drawRoundRect(rect, density, density, fill);

            // 电量内芯
            if (percent <= 0) return;
            float inset = sw + 1f * density;
            float left = inset;
            float right = bodyRight - inset;
            float fillRight = left + (right - left) * Math.min(percent, 100) / 100f;
            fill.setColor(charging ? COLOR_CHARGING : percent <= 20 ? COLOR_LOW : Color.WHITE);
            rect.set(left, inset, fillRight, h - inset);
            canvas.drawRoundRect(rect, density, density, fill);
        }
    }
}
