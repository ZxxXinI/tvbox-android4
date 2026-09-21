package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.tvbox.android44.data.remote.dto.DoubanHotResponseDto;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;

/** 豆瓣最近热播客户端（外部非正式依赖，带验证过的请求头）。 */
public class DoubanClient {

    public static final String CATEGORY_TV = "tv";
    public static final String CATEGORY_SHOW = "show";
    public static final String CATEGORY_MOVIE = "movie";

    private static final Gson GSON = new Gson();
    private static final String API_BASE =
            "https://m.douban.com/rexxar/api/v2/subject/recent_hot/";

    public DoubanHotResponseDto recentHot(String category, int start, int limit,
                                          @Nullable CancelScope scope) throws IOException {
        String url = API_BASE + category + "?start=" + start + "&limit=" + limit;
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent",
                "Mozilla/5.0 (Linux; Android 4.4.4) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/33.0.0.0 Mobile Safari/537.36");
        headers.put("Referer", "https://m.douban.com/");
        headers.put("Origin", "https://m.douban.com");
        headers.put("Accept", "application/json");
        Request.Builder rb = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            rb.header(h.getKey(), h.getValue());
        }
        String body = HttpExecutor.executeForString(
                HttpClients.withTimeout(10000L), rb.build(), scope);
        String s = body.startsWith("\uFEFF") ? body.substring(1) : body;
        try {
            JsonReader reader = new JsonReader(new StringReader(s.trim()));
            reader.setLenient(true);
            DoubanHotResponseDto dto = GSON.fromJson(reader, DoubanHotResponseDto.class);
            if (dto == null || dto.items == null) {
                throw new IOException("EMPTY_BODY");
            }
            return dto;
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("PARSE");
        }
    }
}
