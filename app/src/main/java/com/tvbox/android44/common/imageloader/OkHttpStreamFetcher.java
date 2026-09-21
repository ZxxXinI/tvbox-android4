package com.tvbox.android44.common.imageloader;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.util.ContentLengthInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** OkHttp 3.12 流获取器。 */
public class OkHttpStreamFetcher implements DataFetcher<InputStream> {

    private final okhttp3.Call.Factory client;
    private final GlideUrl url;
    private volatile okhttp3.Call call;
    private InputStream stream;
    private ResponseBody responseBody;

    public OkHttpStreamFetcher(okhttp3.Call.Factory client, GlideUrl url) {
        this.client = client;
        this.url = url;
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull final DataCallback<? super InputStream> callback) {
        Request.Builder requestBuilder = new Request.Builder().get().url(url.toStringUrl());
        for (Map.Entry<String, String> headerEntry : url.getHeaders().entrySet()) {
            String key = headerEntry.getKey();
            requestBuilder.addHeader(key, headerEntry.getValue());
        }
        if (url.getHeaders().isEmpty() && requestBuilder.build().header("User-Agent") == null) {
            requestBuilder.header("User-Agent", "okhttp/3.12.13");
        }
        Request request = requestBuilder.build();
        call = client.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call c, IOException e) {
                callback.onLoadFailed(e);
            }

            @Override
            public void onResponse(okhttp3.Call c, Response response) {
                // 不能在此关闭 response：Glide 异步读取流，响应体由 cleanup() 释放
                ResponseBody body = response.body();
                if (!response.isSuccessful()) {
                    if (body != null) {
                        body.close();
                    }
                    callback.onLoadFailed(new HttpException(response.message(), response.code()));
                    return;
                }
                if (body == null) {
                    callback.onLoadFailed(new IOException("EMPTY_BODY"));
                    return;
                }
                responseBody = body;
                long length = body.contentLength();
                stream = ContentLengthInputStream.obtain(body.byteStream(), length);
                callback.onDataReady(stream);
            }
        });
    }

    @Override
    public void cleanup() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        if (responseBody != null) {
            responseBody.close();
            responseBody = null;
        }
    }

    @Override
    public void cancel() {
        okhttp3.Call c = call;
        if (c != null) {
            c.cancel();
        }
    }

    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @Override
    public DataSource getDataSource() {
        return DataSource.REMOTE;
    }
}
