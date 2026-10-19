package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 底部平滑透明淡出容器（采用 DST_IN Alpha 遮罩，严格对齐 AVBox 融合效果）。
 * 将内部子视图（海报轮播）从下往上渐进淡化至 100% 透明，彻底消除硬切边缘与色块脏斑。
 */
public class FadingFrameLayout extends FrameLayout {

    private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient mGradient;
    private int mWidth;
    private int mHeight;

    public FadingFrameLayout(@NonNull Context context) {
        this(context, null);
    }

    public FadingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FadingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mMaskPaint.setStyle(Paint.Style.FILL);
        mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
        updateGradient();
    }

    private void updateGradient() {
        if (mWidth <= 0 || mHeight <= 0) return;
        // 从海报约 45% 高度处开始平滑淡出，到底部 100% 高度达到完全透明（alpha = 0）
        mGradient = new LinearGradient(
                0, 0,
                0, mHeight,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xE6FFFFFF, 0x99FFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[]{0.0f, 0.45f, 0.62f, 0.78f, 0.90f, 1.0f},
                Shader.TileMode.CLAMP
        );
        mMaskPaint.setShader(mGradient);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        if (w != mWidth || h != mHeight || mGradient == null) {
            mWidth = w;
            mHeight = h;
            updateGradient();
        }
        int saveCount = canvas.saveLayer(0f, 0f, (float) w, (float) h, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0f, 0f, (float) w, (float) h, mMaskPaint);
        canvas.restoreToCount(saveCount);
    }
}
