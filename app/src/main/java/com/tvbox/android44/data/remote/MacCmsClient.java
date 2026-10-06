package com.tvbox.android44.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.data.remote.dto.VodResponseDto;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MacCMS V10 JSON VOD 客户端（统一 GET；参数必须 URL encode）。
 * Base URL 必须以 '/' 结尾后再使用。
 */
public class MacCmsClient {

    private static final Gson GSON = new Gson();

    public static class MacCmsException extends IOException {
        public final int kind;

        public MacCmsException(int kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    /**
     * 执行一次 MacCMS 查询。
     *
     * @param baseUrl   以 / 结尾
     * @param wd        搜索词（可空）
     * @param typeId    分类（可空）
     * @param ids       详情 id（可空，优先）
     * @param hours     最近更新小时（<=0 忽略）
     * @param timeoutMs 整体超时
     */
    public VodResponseDto query(String baseUrl, @Nullable String wd, @Nullable String typeId,
                                @Nullable String ids, int hours, long timeoutMs,
                                @Nullable CancelScope scope) throws IOException {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append("?ac=detail");
        if (ids != null && !ids.isEmpty()) {
            url.append("&ids=").append(encode(ids));
        }
        if (wd != null && !wd.trim().isEmpty()) {
            url.append("&wd=").append(encode(wd.trim()));
        }
        if (typeId != null && !typeId.isEmpty()) {
            url.append("&t=").append(encode(typeId));
        }
        if (hours > 0) {
            url.append("&h=").append(hours);
        }
        url.append("&pg=1");

        Request request = new Request.Builder().url(url.toString()).get().build();
        long startedAt = android.os.SystemClock.elapsedRealtime();
        String body = SourceRequestGate.execute(request, timeoutMs, scope);
        if (com.tvbox.android44.BuildConfig.DEBUG) {
            // 诊断日志：仅路径形态、响应长度与耗时，不含查询参数与完整响应
            android.util.Log.d("TVBOX_MACCMS", "query ids=" + (ids != null && !ids.isEmpty())
                    + " wd=" + (wd != null && !wd.trim().isEmpty())
                    + " len=" + (body == null ? -1 : body.length())
                    + " cost=" + (android.os.SystemClock.elapsedRealtime() - startedAt) + "ms");
        }
        return parseBody(body);
    }

    public VodResponseDto queryPage(String baseUrl, int page, @Nullable String typeId,
                                    long timeoutMs, @Nullable CancelScope scope) throws IOException {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append("?ac=detail&pg=").append(page);
        if (typeId != null && !typeId.isEmpty()) {
            url.append("&t=").append(encode(typeId));
        }
        Request request = new Request.Builder().url(url.toString()).get().build();
        String body = SourceRequestGate.execute(request, timeoutMs, scope);
        return parseBody(body);
    }

    /** 分类表（MacCMS V10 规范：class 字段只在 ac=list 响应中保证存在）。 */
    public VodResponseDto listCategories(String baseUrl, long timeoutMs,
                                         @Nullable CancelScope scope) throws IOException {
        Request request = new Request.Builder().url(baseUrl + "?ac=list&pg=1").get().build();
        String body = SourceRequestGate.execute(request, timeoutMs, scope);
        return parseBody(body);
    }

    static VodResponseDto parseBody(String body) throws IOException {
        if (body == null) {
            throw new MacCmsException(0, "empty body");
        }
        String s = body;
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        s = s.trim();
        if (s.isEmpty()) {
            throw new MacCmsException(0, "empty body");
        }
        // 去掉 PHP 调试输出等前置噪声
        int brace = s.indexOf('{');
        if (brace > 0) {
            s = s.substring(brace);
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(s));
            reader.setLenient(true);
            VodResponseDto dto = GSON.fromJson(reader, VodResponseDto.class);
            if (dto == null) {
                throw new MacCmsException(0, "null dto");
            }
            return dto;
        } catch (JsonSyntaxException e) {
            throw new MacCmsException(0, "parse error");
        } catch (JsonIOException e) {
            throw new MacCmsException(0, "io error");
        }
    }

    static String encode(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return v;
        }
    }
}
