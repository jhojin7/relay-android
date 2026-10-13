package com.hush.voice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;

final class Ui {
  static final int BG = 0xff101412,
      CARD = 0xff1c221e,
      SOFT = 0xff273029,
      LIME = 0xffd6f58b,
      WHITE = 0xfff1f3ec,
      MUTED = 0xffa2aca2,
      LINE = 0xff333e35;

  static int dp(Context c, float v) {
    return (int) (v * c.getResources().getDisplayMetrics().density + .5f);
  }

  static GradientDrawable shape(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static TextView text(Context c, String value, int size, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setLineSpacing(dp(c, 3), 1);
    return t;
  }

  static TextView bold(Context c, String value, int size, int color) {
    TextView t = text(c, value, size, color);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  static LinearLayout row(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setOrientation(LinearLayout.HORIZONTAL);
    return v;
  }

  static void pad(View v, int h, int top, int bottom) {
    v.setPadding(
        dp(v.getContext(), h),
        dp(v.getContext(), top),
        dp(v.getContext(), h),
        dp(v.getContext(), bottom));
  }

  static void gap(LinearLayout v, int h) {
    View x = new View(v.getContext());
    v.addView(x, new LinearLayout.LayoutParams(1, dp(v.getContext(), h)));
  }

  static void weighted(LinearLayout row, View v) {
    row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
  }

  static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView t = bold(c, label, 15, primary ? BG : WHITE);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(c, 52));
    pad(t, 20, 12, 12);
    t.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x33ffffff), shape(c, primary ? LIME : SOFT, 18), null));
    t.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
          action.run();
        });
    return t;
  }

  static void enter(View v, int delay) {
    if (!ValueAnimator.areAnimatorsEnabled()) return;
    v.setAlpha(0);
    v.setTranslationY(dp(v.getContext(), 16));
    v.animate()
        .alpha(1)
        .translationY(0)
        .setDuration(380)
        .setStartDelay(delay)
        .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1))
        .start();
  }

  static void feedback(View v) {
    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  }

  static final class Icon extends View {
    private final String name;
    private final int color;
    private final Paint p = new Paint(3);

    Icon(Context c, String n, int col) {
      super(c);
      name = n;
      color = col;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.translate(
          (getWidth() - dp(getContext(), 24)) / 2f, (getHeight() - dp(getContext(), 24)) / 2f);
      canvas.scale(dp(getContext(), 24) / 24f, dp(getContext(), 24) / 24f);
      p.setColor(color);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStyle(Paint.Style.STROKE);
      switch (name) {
        case "mic" -> {
          canvas.drawRoundRect(9, 3, 15, 15, 3, 3, p);
          canvas.drawArc(5, 7, 19, 19, 0, 180, false, p);
          canvas.drawLine(12, 19, 12, 22, p);
          canvas.drawLine(8, 22, 16, 22, p);
        }
        case "notes" -> {
          canvas.drawRoundRect(5, 3, 19, 21, 3, 3, p);
          canvas.drawLine(9, 8, 15, 8, p);
          canvas.drawLine(9, 12, 15, 12, p);
          canvas.drawLine(9, 16, 13, 16, p);
        }
        case "power" -> {
          canvas.drawArc(4, 4, 20, 20, -55, 290, false, p);
          canvas.drawLine(12, 2, 12, 11, p);
        }
        case "settings" -> {
          canvas.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            canvas.drawLine(
                (float) (12 + 8 * Math.cos(a)),
                (float) (12 + 8 * Math.sin(a)),
                (float) (12 + 10 * Math.cos(a)),
                (float) (12 + 10 * Math.sin(a)),
                p);
          }
          canvas.drawCircle(12, 12, 8, p);
        }
        case "arrow" -> {
          canvas.drawLine(5, 12, 19, 12, p);
          canvas.drawLine(14, 7, 19, 12, p);
          canvas.drawLine(14, 17, 19, 12, p);
        }
        case "close" -> {
          canvas.drawLine(6, 6, 18, 18, p);
          canvas.drawLine(18, 6, 6, 18, p);
        }
        case "lock" -> {
          canvas.drawRoundRect(6, 10, 18, 21, 3, 3, p);
          canvas.drawArc(8, 3, 16, 15, 180, 180, false, p);
        }
        case "check" -> {
          canvas.drawLine(5, 12, 10, 17, p);
          canvas.drawLine(10, 17, 19, 7, p);
        }
        case "search" -> {
          canvas.drawCircle(10, 10, 6, p);
          canvas.drawLine(15, 15, 21, 21, p);
        }
        case "logo" -> {
          p.setStrokeWidth(3.5f);
          canvas.drawLine(5, 8, 5, 16, p);
          canvas.drawLine(12, 3, 12, 21, p);
          canvas.drawLine(19, 8, 19, 16, p);
        }
      }
      canvas.restore();
    }
  }

  static final class Wave extends View {
    private final Paint paint = new Paint(3);
    private ValueAnimator animator;
    private float phase, level, target;
    private boolean listening;

    Wave(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void active(boolean a) {
      listening = a;
      if (a && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow()) {
        if (animator == null) {
          animator = ValueAnimator.ofFloat(0, 6.283185f);
          animator.setDuration(1600);
          animator.setRepeatCount(ValueAnimator.INFINITE);
          animator.setInterpolator(new android.view.animation.LinearInterpolator());
          animator.addUpdateListener(
              v -> {
                phase = (float) v.getAnimatedValue();
                level += (target - level) * .14f;
                invalidate();
              });
          animator.start();
        }
      } else if (animator != null) {
        animator.cancel();
        animator = null;
      }
      invalidate();
    }

    void level(float l) {
      target = l;
      if (!ValueAnimator.areAnimatorsEnabled()) {
        level = l;
        invalidate();
      }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      active(listening);
    }

    @Override
    protected void onDetachedFromWindow() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
      super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
      float density = getResources().getDisplayMetrics().density;
      float step = Math.min(10 * density, getWidth() / 29f), width = 3 * density;
      paint.setColor(LIME);
      for (int i = 0; i < 27; i++) {
        float envelope = (float) Math.sin((i + 1) * Math.PI / 28);
        float h =
            listening
                ? (8
                        + envelope
                            * (14 + level * 58)
                            * (.55f + .45f * (float) Math.sin(phase + i * .65f)))
                    * density
                : (5 + envelope * 38 * (.55f + .45f * (float) Math.cos(i * .8))) * density;
        h = Math.min(h, getHeight() * .85f);
        float x = getWidth() / 2f + (i - 13) * step;
        c.drawRoundRect(
            x - width / 2,
            getHeight() / 2f - h / 2,
            x + width / 2,
            getHeight() / 2f + h / 2,
            width,
            width,
            paint);
      }
    }
  }
}
