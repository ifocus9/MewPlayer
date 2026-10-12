package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;

import androidx.annotation.ColorInt;
import androidx.appcompat.widget.AppCompatImageView;

public class ContextWallImageView extends AppCompatImageView {

    // 可读性遮罩：大幅提高模糊与遮罩深度，彻底消除具象文字/图案残留，营造柔和环境色辉光
    private static final float[] SCRIM_STOPS = {0f, 0.45f, 1f};
    private static final int[] SCRIM_ALPHAS_LIGHT = {0xFD, 0xF6, 0xEE};
    private static final int[] SCRIM_ALPHAS_DARK = {0xF6, 0xEE, 0xE0};
    private static final float SCRIM_BLUR_DP = 28f;

    private final Matrix matrix;
    private final Paint scrimPaint;
    private boolean scrimEnabled;
    @ColorInt
    private int scrimColor;

    public ContextWallImageView(Context context) {
        this(context, null);
    }

    public ContextWallImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ContextWallImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        matrix = new Matrix();
        scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setScaleType(ScaleType.MATRIX);
    }

    /**
     * 在海报上叠一层与页面背景同色的渐变遮罩（Android 12+ 额外做轻度模糊），
     * 让叠在上面的深色文字保持可读。默认关闭，由需要的页面主动开启。
     */
    public void setReadableScrim(@ColorInt int color) {
        scrimEnabled = true;
        scrimColor = color;
        updateScrimShader();
        updateBlur();
        invalidate();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateMatrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMatrix();
        updateScrimShader();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scrimEnabled || getDrawable() == null || scrimPaint.getShader() == null) return;
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
    }

    private void updateScrimShader() {
        int height = getHeight();
        if (!scrimEnabled || height <= 0) {
            scrimPaint.setShader(null);
            return;
        }
        int r = Color.red(scrimColor);
        int g = Color.green(scrimColor);
        int b = Color.blue(scrimColor);
        boolean isLight = (r * 299 + g * 587 + b * 114) / 1000 > 160;
        int[] alphas = isLight ? SCRIM_ALPHAS_LIGHT : SCRIM_ALPHAS_DARK;
        int[] colors = new int[alphas.length];
        for (int i = 0; i < alphas.length; i++) {
            colors[i] = Color.argb(alphas[i], r, g, b);
        }
        scrimPaint.setShader(new LinearGradient(0, 0, 0, height, colors, SCRIM_STOPS, Shader.TileMode.CLAMP));
    }

    private void updateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float radius = SCRIM_BLUR_DP * getResources().getDisplayMetrics().density;
        setRenderEffect(scrimEnabled ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
    }

    private void updateMatrix() {
        Drawable drawable = getDrawable();
        int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) return;

        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) return;

        float viewRatio = viewWidth / (float) viewHeight;
        float drawableRatio = drawableWidth / (float) drawableHeight;
        boolean narrow = drawableRatio < Math.min(1.2f, viewRatio * 0.75f);
        float scale;
        float dx;
        float dy;

        if (narrow) {
            scale = viewWidth / (float) drawableWidth;
            dx = 0f;
            dy = 0f;
        } else {
            scale = Math.max(viewWidth / (float) drawableWidth, viewHeight / (float) drawableHeight);
            dx = (viewWidth - drawableWidth * scale) * 0.5f;
            dy = (viewHeight - drawableHeight * scale) * 0.5f;
        }

        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx) + getPaddingLeft(), Math.round(dy) + getPaddingTop());
        setImageMatrix(matrix);
    }
}
