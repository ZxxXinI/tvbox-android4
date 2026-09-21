package com.tvbox.android44.feature.player;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ExoPlayer 2 OkHttp 数据源（基于 OkHttp 3.12，API 19 TLS 兼容）。
 * 只注入白名单请求头（User-Agent/Referer/Origin 等），不全局发送。
 */
public final class OkHttpDataSource extends BaseDataSource implements HttpDataSource {

    public static final class Factory implements DataSource.Factory {

        private final okhttp3.Call.Factory callFactory;
        private final String defaultUserAgent;
        private final Map<String, String> defaultRequestProperties =
                new HashMap<String, String>();
        private TransferListener transferListener;

        public Factory(okhttp3.Call.Factory callFactory, String defaultUserAgent) {
            this.callFactory = callFactory;
            this.defaultUserAgent = defaultUserAgent;
        }

        public Factory setDefaultRequestProperty(String name, String value) {
            defaultRequestProperties.put(name, value);
            return this;
        }

        public Factory setTransferListener(TransferListener listener) {
            this.transferListener = listener;
            return this;
        }

        @Override
        public OkHttpDataSource createDataSource() {
            OkHttpDataSource dataSource = new OkHttpDataSource(callFactory, defaultUserAgent,
                    Collections.unmodifiableMap(new HashMap<String, String>(defaultRequestProperties)));
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            return dataSource;
        }
    }

    private final okhttp3.Call.Factory callFactory;
    private final String userAgent;
    private final Map<String, String> defaultRequestProperties;

    private final AtomicReference<Map<String, String>> requestPropertiesRef =
            new AtomicReference<Map<String, String>>(
                    Collections.unmodifiableMap(new HashMap<String, String>()));

    private DataSpec dataSpec;
    private Response response;
    private InputStream responseStream;

    private boolean opened;

    private long bytesToRead;
    private long bytesRead;

    private static final byte[] SKIP_BUFFER = new byte[4096];

    OkHttpDataSource(okhttp3.Call.Factory callFactory, String userAgent,
                     Map<String, String> defaultRequestProperties) {
        super(/* isNetwork= */ true);
        this.callFactory = callFactory;
        this.userAgent = userAgent;
        this.defaultRequestProperties = defaultRequestProperties;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return response == null ? null : Uri.parse(response.request().url().toString());
    }

    @Override
    public int getResponseCode() {
        return response == null ? -1 : response.code();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        if (response != null) {
            for (String field : response.headers().names()) {
                headers.put(field, Collections.singletonList(response.headers().get(field)));
            }
        }
        return headers;
    }

    @Override
    public void setRequestProperty(String name, String value) {
        Assertions.checkNotNull(name);
        Assertions.checkNotNull(value);
        Map<String, String> properties = new HashMap<String, String>(requestPropertiesRef.get());
        properties.put(name, value);
        requestPropertiesRef.set(Collections.unmodifiableMap(properties));
    }

    @Override
    public void clearRequestProperty(String name) {
        Map<String, String> properties = new HashMap<String, String>(requestPropertiesRef.get());
        properties.remove(name);
        requestPropertiesRef.set(Collections.unmodifiableMap(properties));
    }

    @Override
    public void clearAllRequestProperties() {
        requestPropertiesRef.set(Collections.unmodifiableMap(
                new HashMap<String, String>()));
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        bytesToRead = 0;
        Request.Builder builder = new Request.Builder();
        builder.url(dataSpec.uri.toString());
        for (Map.Entry<String, String> entry : defaultRequestProperties.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : requestPropertiesRef.get().entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        String method = httpMethodName(dataSpec.httpMethod);
        if (dataSpec.httpBody != null && dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET
                && dataSpec.httpMethod != DataSpec.HTTP_METHOD_HEAD) {
            builder.method(method, RequestBody.create(null, dataSpec.httpBody));
        } else {
            builder.method(method, null);
        }
        if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET) {
            String range = "bytes=" + dataSpec.position + "-";
            if (dataSpec.length != C.LENGTH_UNSET) {
                range += (dataSpec.position + dataSpec.length - 1);
            }
            builder.header("Range", range);
        }
        builder.header("User-Agent", userAgent);

        okhttp3.Call call = callFactory.newCall(builder.build());
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e, dataSpec,
                    HttpDataSourceException.TYPE_OPEN);
        }
        responseStream = response.body() == null ? null : response.body().byteStream();

        int code = response.code();
        if (code < 200 || code > 299) {
            Map<String, List<String>> headers = toMapLower(response.headers().toMultimap());
            closeConnection();
            throw new HttpDataSource.InvalidResponseCodeException(code, response.message(),
                    null, headers, dataSpec, com.google.android.exoplayer2.util.Util.EMPTY_BYTE_ARRAY);
        }

        MediaType mediaType = response.body() == null ? null : response.body().contentType();
        String contentType = mediaType == null ? "" : mediaType.toString();
        if (!contentTypeMatches(contentType)) {
            // 放行，由 ExoPlayer 按扩展名/内容嗅探处理
        }
        long contentLength = response.body() == null ? 0 : response.body().contentLength();
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesToRead = dataSpec.length;
        } else {
            long contentLengthFromHeader =
                    (code == 200 || code == 206) ? contentLength : C.LENGTH_UNSET;
            bytesToRead = contentLengthFromHeader == C.LENGTH_UNSET
                    ? C.LENGTH_UNSET : Math.max(contentLengthFromHeader - dataSpec.position, 0);
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesToRead;
    }

    private static String httpMethodName(int method) {
        switch (method) {
            case DataSpec.HTTP_METHOD_POST:
                return "POST";
            case DataSpec.HTTP_METHOD_HEAD:
                return "HEAD";
            case DataSpec.HTTP_METHOD_GET:
            default:
                return "GET";
        }
    }

    private boolean contentTypeMatches(String contentType) {
        return true;
    }

    private static Map<String, List<String>> toMapLower(Map<String, List<String>> map) {
        Map<String, List<String>> lower = new HashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            lower.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
        }
        return lower;
    }

    @Override
    public int read(byte[] buffer, int offset, int length)
            throws HttpDataSource.HttpDataSourceException {
        try {
            return readInternal(buffer, offset, length);
        } catch (IOException e) {
            if (opened) {
                throw new HttpDataSource.HttpDataSourceException(e, dataSpec,
                        HttpDataSourceException.TYPE_READ);
            }
            return C.RESULT_END_OF_INPUT;
        }
    }

    private int readInternal(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            length = (int) Math.min(length, bytesRemaining);
        }
        int read = responseStream.read(buffer, offset, length);
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET && bytesToRead - bytesRead > 0) {
                throw new IOException("unexpected end of stream");
            }
            return C.RESULT_END_OF_INPUT;
        }
        bytesRead += read;
        bytesTransferred(read);
        return read;
    }

    @Override
    public void close() throws HttpDataSource.HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
        }
        closeConnection();
    }

    private void closeConnection() {
        if (responseStream != null) {
            try {
                responseStream.close();
            } catch (IOException ignored) {
            }
            responseStream = null;
        }
        if (response != null) {
            response.body().close();
            response = null;
        }
    }
}
