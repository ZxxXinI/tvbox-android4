package com.tvbox.android44.common.imageloader;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.tvbox.android44.data.remote.HttpClients;

import java.io.InputStream;

/**
 * 应用 Glide 模块：OkHttp 3.12 网络栈 + RGB_565 低内存解码 + 适度内存缓存。
 */
@GlideModule
public final class TvGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1.5f)
                .setBitmapPoolScreens(1.5f)
                .build();
        builder.setMemorySizeCalculator(calculator);
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(HttpClients.client()));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
