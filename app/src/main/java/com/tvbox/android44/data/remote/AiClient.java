package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

    /** Base URL 已以 /chat/completions 结尾直接使用，否则追加该路径。 */
    public static String normalizeApiBase(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            return "";
        }
        if (s.endsWith("/chat/completions")) {
            return s;
        }
        return s + "/chat/completions";
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

        OkHttpClient client = HttpClients.client().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
        okhttp3.Call call = client.newCall(request);
        if (scope != null) {
            scope.register(call);
        }
        try {
            Response response = call.execute();
            try {
                int code = response.code();
                String text = response.body() == null ? "" : response.body().string();
                if (code == 401 || code == 403) {
                    return new ChatResult(null, code);
                }
                if (code == 429) {
                    return new ChatResult(null, code);
                }
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
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
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()) {
                throw new IOException("PARSE");
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.size() == 0) {
                throw new IOException("EMPTY_BODY");
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            if (first.has("message") && first.getAsJsonObject("message").has("content")) {
                String content = first.getAsJsonObject("message").get("content").getAsString();
                if (content == null || content.trim().isEmpty()) {
                    throw new IOException("EMPTY_BODY");
                }
                return content;
            }
            throw new IOException("PARSE");
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("PARSE");
        }
    }
}
