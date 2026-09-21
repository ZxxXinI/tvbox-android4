package com.tvbox.android44.common;

import android.view.View;
import android.view.animation.ScaleAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;

/**
 * 焦点缩放（焦点 = 描边 + 轻度放大，双信号）。
 * 720p 缩放 1.05，动画 120ms；无动画模式可直接跳变。
 */
public final class FocusScaler {

    private static final float SCALE = 1.05f;
    private static final long DURATION = 120;

    private FocusScaler() {
    }

    public static final View.OnFocusChangeListener INSTANCE = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            apply(v, hasFocus);
        }
    };

    public static void apply(View v, boolean hasFocus) {
        v.animate().cancel();
        if (hasFocus) {
            v.animate().scaleX(SCALE).scaleY(SCALE).setDuration(DURATION).start();
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(DURATION).start();
        }
    }

    /** 给整个 item 根视图挂上缩放监听（保留旧监听链不覆盖）。 */
    public static void attach(View view) {
        final View.OnFocusChangeListener old = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                apply(v, hasFocus);
                if (old != null) {
                    old.onFocusChange(v, hasFocus);
                }
            }
        });
    }
}
