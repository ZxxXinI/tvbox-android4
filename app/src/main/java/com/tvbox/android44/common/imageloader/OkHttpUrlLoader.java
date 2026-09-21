package com.tvbox.android44.common.imageloader;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;

import java.io.InputStream;

import okhttp3.OkHttpClient;

/**
 * Glide 的 OkHttp 3.12 加载器（API 19 TLS 兼容；单一网络栈）。
 */
public class OkHttpUrlLoader implements ModelLoader<GlideUrl, InputStream> {

    private final okhttp3.Call.Factory client;

    public OkHttpUrlLoader(okhttp3.Call.Factory client) {
        this.client = client;
    }

    @Override
    public LoadData<InputStream> buildLoadData(@NonNull GlideUrl model, int width, int height,
                                               @NonNull Options options) {
        return new LoadData<>(model, new OkHttpStreamFetcher(client, model));
    }

    @Override
    public boolean handles(@NonNull GlideUrl model) {
        return true;
    }

    public static class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {

        private final okhttp3.Call.Factory client;

        public Factory(OkHttpClient client) {
            this.client = client;
        }

        @Override
        public ModelLoader<GlideUrl, InputStream> build(MultiModelLoaderFactory multiFactory) {
            return new OkHttpUrlLoader(client);
        }

        @Override
        public void teardown() {
        }
    }
}
