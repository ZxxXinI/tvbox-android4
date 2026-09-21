package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** HTTP 执行工具：统一注册 CancelScope、非 2xx 转 IOException。 */
public final class HttpExecutor {

    private HttpExecutor() {
    }

    public static String executeForString(OkHttpClient client, Request request,
                                          @Nullable CancelScope scope) throws IOException {
        okhttp3.Call call = client.newCall(request);
        if (scope != null) {
            scope.register(call);
        }
        try {
            Response response = call.execute();
            try {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("EMPTY_BODY");
                }
                return body.string();
            } finally {
                response.close();
            }
        } finally {
            if (scope != null) {
                scope.unregister(call);
            }
        }
    }

    public static byte[] executeForBytes(OkHttpClient client, Request request,
                                         @Nullable CancelScope scope) throws IOException {
        return executeForString(client, request, scope).getBytes("UTF-8");
    }

    public interface ProgressListener {
        void onProgress(long downloaded, long total, boolean done);
    }

    /** 流式下载到返回流；调用方负责关闭。 */
    public static Response executeForStream(OkHttpClient client, Request request,
                                            @Nullable CancelScope scope) throws IOException {
        okhttp3.Call call = client.newCall(request);
        if (scope != null) {
            scope.register(call);
        }
        Response response = call.execute();
        if (!response.isSuccessful()) {
            response.close();
            if (scope != null) {
                scope.unregister(call);
            }
            throw new IOException("HTTP " + response.code());
        }
        return response;
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
