package com.quicinc.superresolution;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.ColorUtils;

public class GlowImageView extends AppCompatImageView {

    private Paint glowPaint;
    private float glowSizeScale = 1.0f;
    private int glowAlpha = 0;

    private AnimatorSet breathingAnimatorSet;
    private ValueAnimator flowAnimator;

    private boolean showGlow = false;
    private float blurRadius = 40f;
    private final float PADDING_FOR_GLOW = 10f;

    private Matrix shaderMatrix;
    private float gradientRotation = 0f;

    public GlowImageView(Context context) {
        super(context);
        init();
    }

    public GlowImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlowImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int padding = (int) PADDING_FOR_GLOW;
        setPadding(padding, padding, padding, padding);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(50f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        shaderMatrix = new Matrix();
    }

    public void createGradientShader() {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        int[] colors = new int[]{
                0xFFFF9800, // Orange
                0xFFFBC02D, // Yellow
                0xFFE91E63, // Pink
                0xFF9C27B0, // Purple
                0xFFFF9800
        };

        Shader shader = new SweepGradient(viewWidth / 2f, viewHeight / 2f, colors, null);
        glowPaint.setShader(shader);
        updateBlurRadius(this.blurRadius);
    }

    public void setGlowSizeScale(float scale) {
        this.glowSizeScale = scale;
        invalidate();
    }

    public void setGlowAlpha(int alpha) {
        this.glowAlpha = alpha;
        invalidate();
    }

    private void updateBlurRadius(float radius) {
        if (radius > 0) {
            glowPaint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        } else {
            glowPaint.setMaskFilter(null);
        }
    }

    public void startGlowEffect() {
        stopGlowEffect();
        showGlow = true;

        // UPDATED: Breathing only reduces to semi-transparent
        ObjectAnimator alphaAnimator = ObjectAnimator.ofInt(this, "glowAlpha", 70, 255);
        alphaAnimator.setDuration(3000);
        alphaAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        alphaAnimator.setRepeatCount(ValueAnimator.INFINITE);
        alphaAnimator.setRepeatMode(ValueAnimator.REVERSE);

        ObjectAnimator scaleAnimator = ObjectAnimator.ofFloat(this, "glowSizeScale", 1.0f, 1.08f);
        scaleAnimator.setDuration(3000);
        scaleAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scaleAnimator.setRepeatMode(ValueAnimator.REVERSE);

        breathingAnimatorSet = new AnimatorSet();
        breathingAnimatorSet.playTogether(alphaAnimator, scaleAnimator);
        breathingAnimatorSet.start();

        // UPDATED: Increased speed of flowing effect (shorter duration)
        flowAnimator = ValueAnimator.ofFloat(0, 360);
        flowAnimator.setDuration(3000); // Now 3 seconds for a full rotation
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flowAnimator.addUpdateListener(animation -> {
            gradientRotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        flowAnimator.start();
    }

    public void stopGlowEffect() {
        if (breathingAnimatorSet != null) breathingAnimatorSet.cancel();
        if (flowAnimator != null) flowAnimator.cancel();
        showGlow = false;
        setGlowAlpha(0);
        setGlowSizeScale(1.0f);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        createGradientShader();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (showGlow && glowPaint.getShader() != null) {
            shaderMatrix.setRotate(gradientRotation, getWidth() / 2f, getHeight() / 2f);
            glowPaint.getShader().setLocalMatrix(shaderMatrix);

            glowPaint.setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF, glowAlpha));

            float inset = PADDING_FOR_GLOW / 2;
            RectF glowRect = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);

            canvas.save();
            canvas.scale(glowSizeScale, glowSizeScale, getWidth() / 2f, getHeight() / 2f);
            canvas.drawRect(glowRect, glowPaint);
            canvas.restore();
        }
    }
}