package com.tvbox.android44.common;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;

/**
 * 三档字体：正常 1.00 / 大 1.18 / 超大 1.36。
 * 通过 Configuration.fontScale + createConfigurationContext 实现（API 17+）。
 */
public final class FontScale {

    public static final float SCALE_NORMAL = 1.00f;
    public static final float SCALE_LARGE = 1.18f;
    public static final float SCALE_XLARGE = 1.36f;

    private FontScale() {
    }

    public static float current() {
        String f = TvBoxApp.get().settings().fontScale();
        if (SettingsRepository.FONT_LARGE.equals(f)) {
            return SCALE_LARGE;
        }
        if (SettingsRepository.FONT_XLARGE.equals(f)) {
            return SCALE_XLARGE;
        }
        return SCALE_NORMAL;
    }

    /** BaseActivity.attachBaseContext 中调用。 */
    public static Context wrap(Context base) {
        float scale = current();
        if (scale == SCALE_NORMAL || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = scale;
        return base.createConfigurationContext(cfg);
    }
}
