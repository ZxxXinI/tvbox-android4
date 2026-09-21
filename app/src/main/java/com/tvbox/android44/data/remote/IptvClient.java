package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import java.io.IOException;

import okhttp3.Request;

/** IPTV 文本源下载。 */
public class IptvClient {

    public String getText(String url, @Nullable CancelScope scope) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        String body = HttpExecutor.executeForString(HttpClients.withTimeout(15000L), request, scope);
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }
        return body;
    }
}
