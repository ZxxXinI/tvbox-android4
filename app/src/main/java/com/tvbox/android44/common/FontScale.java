package com.tvbox.android44.common;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;

/**
 * 三档字体：正常 1.00 / 大 1.18 / 超大 1.36。
 * API 17+ 使用 createConfigurationContext，API 16 使用独立资源上下文。
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
        if (scale == SCALE_NORMAL) {
            return base;
        }
        return withScale(base, scale);
    }

    /** API 16 lacks createConfigurationContext; use isolated resources there. */
    public static Context withScale(final Context base, float scale) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = scale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return base.createConfigurationContext(cfg);
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.setTo(base.getResources().getDisplayMetrics());
        metrics.scaledDensity = metrics.density * scale;
        final android.content.res.Resources resources = new android.content.res.Resources(
                base.getAssets(), metrics, cfg);
        return new android.content.ContextWrapper(base) {
            private android.content.res.Resources.Theme theme;
            private android.view.LayoutInflater inflater;
            @Override public android.content.res.Resources getResources() { return resources; }
            @Override public android.content.res.AssetManager getAssets() { return resources.getAssets(); }
            @Override public android.content.res.Resources.Theme getTheme() {
                if (theme == null) {
                    theme = resources.newTheme();
                    theme.setTo(base.getTheme());
                }
                return theme;
            }
            @Override public void setTheme(int resourceId) { getTheme().applyStyle(resourceId, true); }
            @Override public Object getSystemService(String name) {
                if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                    if (inflater == null) inflater = android.view.LayoutInflater.from(base).cloneInContext(this);
                    return inflater;
                }
                return super.getSystemService(name);
            }
        };
    }
}
