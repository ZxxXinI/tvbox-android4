package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.tvbox.android44.common.ErrorKind;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI Chat Completions 兼容客户端（Agnes/DeepSeek/SiliconFlow/Qwen）。
 * Authorization 头绝不写入日志。
 */
public class AiClient {

    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient http;

    public AiClient() {
        this(HttpClients.client().newBuilder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS).build());
    }

    public AiClient(OkHttpClient http) { this.http = http; }

    public static final class ResponseException extends IOException {
        public final ErrorKind kind;
        ResponseException(ErrorKind kind) { super(kind.name()); this.kind = kind; }
    }

    /** Base URL 已以 /chat/completions 结尾直接使用，否则追加该路径。 */
    public static String normalizeApiBase(String raw) {
        if (raw == null) {
            return "";
        }
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(raw.trim());
        if (url == null) return "";
        String path = url.encodedPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        return url.newBuilder().encodedPath(path).fragment(null).build().toString();
    }

    public static final class ChatResult {
        public final String content;
        public final int httpCode;

        ChatResult(String content, int httpCode) {
            this.content = content;
            this.httpCode = httpCode;
        }
    }

    public ChatResult chat(String apiBase, String apiKey, String model,
                           String systemPrompt, String userQuery,
                           @Nullable CancelScope scope) throws IOException {
        String url = normalizeApiBase(apiBase);
        if (url.isEmpty()) {
            throw new IOException("api base 非法");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.4);
        body.addProperty("max_tokens", 1200);
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userQuery);
        messages.add(user);
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, GSON.toJson(body)))
                .build();

        okhttp3.Call call = http.newCall(request);
        if (scope != null) {
            scope.register(call);
        }
        try {
            Response response = call.execute();
            try {
                int code = response.code();
                if (code == 401 || code == 403) {
                    return new ChatResult(null, code);
                }
                if (code == 429) {
                    return new ChatResult(null, code);
                }
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                String text = response.body() == null ? "" : response.body().string();
                return new ChatResult(parseContent(text), code);
            } finally {
                response.close();
            }
        } finally {
            if (scope != null) {
                scope.unregister(call);
            }
        }
    }

    static String parseContent(String body) throws IOException {
        if (body == null || body.trim().isEmpty()) throw new ResponseException(ErrorKind.EMPTY_BODY);
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw new ResponseException(ErrorKind.PARSE);
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()) {
                throw new ResponseException(ErrorKind.PARSE);
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.size() == 0) {
                throw new ResponseException(ErrorKind.EMPTY_BODY);
            }
            if (!choices.get(0).isJsonObject()) throw new ResponseException(ErrorKind.PARSE);
            JsonObject first = choices.get(0).getAsJsonObject();
            if (first.has("message") && first.get("message").isJsonObject()
                    && first.getAsJsonObject("message").has("content")) {
                JsonElement value = first.getAsJsonObject("message").get("content");
                if (value.isJsonNull()) throw new ResponseException(ErrorKind.EMPTY_BODY);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new ResponseException(ErrorKind.PARSE);
                String content = value.getAsString();
                if (content == null || content.trim().isEmpty()) {
                    throw new ResponseException(ErrorKind.EMPTY_BODY);
                }
                return content;
            }
            throw new ResponseException(ErrorKind.PARSE);
        } catch (RuntimeException e) {
            // Do not retain raw server content in messages or exception causes.
            throw new ResponseException(ErrorKind.PARSE);
        }
    }
}
