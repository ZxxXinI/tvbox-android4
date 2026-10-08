package com.tvbox.android44.data.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tvbox.android44.domain.model.AiProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Read-only, bounded model discovery. Never persists credentials or exposes upstream bodies. */
public class AiModelsClient {
    public static final String PRESET_DATE = "2026-10-08";
    private final OkHttpClient http;
    private final String qwenNativeUrl;
    private final long timeoutMs;

    public AiModelsClient() {
        this(HttpClients.client(), "https://dashscope.aliyuncs.com/api/v1/models", 20000);
    }

    public AiModelsClient(OkHttpClient http, String qwenNativeUrl, long timeoutMs) {
        this.http = http; this.qwenNativeUrl = qwenNativeUrl; this.timeoutMs = timeoutMs;
    }

    public static final class Model {
        public final String id;
        public final String name;
        public Model(String id, String name) { this.id = id; this.name = name; }
    }

    public static final class Catalog {
        public final String source;
        public final String message;
        public final String updatedAt;
        public final List<Model> models;
        public Catalog(String source, String message, String updatedAt, List<Model> models) {
            this.source = source; this.message = message; this.updatedAt = updatedAt; this.models = models;
        }
        public static Catalog error(String message) {
            return new Catalog("error", message, "", new ArrayList<Model>());
        }
    }

    public Catalog fetch(AiProvider provider, String apiKey, CancelScope scope) throws IOException {
        if (provider == null || !AiProvider.validApiKey(apiKey)) {
            return Catalog.error("请检查提供方和 API Key");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            checkCancelled(scope);
            String url = modelsUrl(provider.apiBase);
            JsonObject first;
            boolean nativeQwen = false;
            try {
                first = page(url, provider, apiKey.trim(), scope, deadline);
            } catch (HttpFailure failure) {
                if (!"qwen".equals(provider.id) || !unsupported(failure.code)) throw failure;
                nativeQwen = true;
                url = qwenPage(1);
                first = page(url, provider, apiKey.trim(), scope, deadline);
            }
            LinkedHashMap<String, Model> result = new LinkedHashMap<String, Model>();
            JsonObject body = first;
            int nativeRowsSeen = 0;
            for (int number = 1; number <= 20; number++) {
                JsonArray rows = rows(body);
                String lastId = "";
                for (JsonElement row : rows) {
                    if (!row.isJsonObject()) continue;
                    JsonObject model = row.getAsJsonObject();
                    String id = string(model, "id");
                    if (id.isEmpty()) id = string(model, "model");
                    if (id.isEmpty()) continue;
                    lastId = id;
                    if (id.contains(apiKey.trim())) continue;
                    if (!textChatModel(model, id)) continue;
                    String name = string(model, "name");
                    if (name.contains(apiKey.trim())) name = "";
                    result.put(id, new Model(id, name.isEmpty() ? id : name));
                }
                String next = null;
                if (nativeQwen) {
                    nativeRowsSeen += rows.size();
                    JsonObject output = body.getAsJsonObject("output");
                    int total = output != null && output.has("total") ? output.get("total").getAsInt() : rows.size();
                    if (nativeRowsSeen < total && rows.size() > 0) next = qwenPage(number + 1);
                } else if (body.has("has_more") && body.get("has_more").getAsBoolean()) {
                    String after = string(body, "last_id");
                    if (after.isEmpty()) after = lastId;
                    if (after.isEmpty()) throw new IOException("MODEL_PAGE");
                    next = HttpUrl.parse(url).newBuilder().setQueryParameter("after", after).build().toString();
                }
                if (next == null) break;
                if (number == 20) throw new IOException("MODEL_PAGE_LIMIT");
                body = page(next, provider, apiKey.trim(), scope, deadline);
            }
            checkCancelled(scope);
            if (result.isEmpty()) return presets(provider.id, "接口未返回可用的文本对话型号，可重试或使用候选/手动输入");
            return new Catalog("remote", "接口返回的型号；实际调用权限和额度以提供方为准", "",
                    new ArrayList<Model>(result.values()));
        } catch (HttpFailure error) {
            checkCancelled(scope);
            if (error.code == 401 || error.code == 403) {
                return Catalog.error("API Key 无效或无权限，请检查普通开放平台 Key");
            }
            return presets(provider.id, error.code == 429 ? "请求过于频繁，请稍后重试"
                    : "模型列表接口暂不可用（HTTP " + error.code + "）");
        } catch (IOException | RuntimeException error) {
            checkCancelled(scope);
            return presets(provider.id, "模型列表获取失败或超时，可重试或使用候选/手动输入");
        }
    }

    public static String modelsUrl(String base) {
        HttpUrl url = HttpUrl.parse(base);
        if (url == null) throw new IllegalArgumentException("MODEL_URL");
        String path = url.encodedPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - 17);
        if (!path.endsWith("/models")) path += "/models";
        return url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString();
    }

    private String qwenPage(int page) {
        return HttpUrl.parse(qwenNativeUrl).newBuilder().setQueryParameter("providers", "qwen")
                .setQueryParameter("capabilities", "TG").setQueryParameter("page_no", String.valueOf(page))
                .setQueryParameter("page_size", "100").build().toString();
    }

    private JsonObject page(String url, AiProvider provider, String key, CancelScope scope, long deadline)
            throws IOException {
        checkCancelled(scope);
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new InterruptedIOException("MODEL_TIMEOUT");
        Request request = new Request.Builder().url(url)
                .header("mimo".equals(provider.id) ? "api-key" : "Authorization",
                        "mimo".equals(provider.id) ? key : "Bearer " + key).get().build();
        Call call = http.newBuilder().followRedirects(false).followSslRedirects(false)
                .callTimeout(remaining, TimeUnit.MILLISECONDS).build().newCall(request);
        scope.register(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new HttpFailure(response.code());
            if (response.body() == null) throw new IOException("MODEL_EMPTY");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = response.body().byteStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(scope);
                if (bytes.size() + count > 1024 * 1024) throw new IOException("MODEL_TOO_LARGE");
                bytes.write(buffer, 0, count);
            }
            JsonElement json = JsonParser.parseString(bytes.toString("UTF-8"));
            if (!json.isJsonObject()) throw new IOException("MODEL_PARSE");
            return json.getAsJsonObject();
        } finally { scope.unregister(call); }
    }

    private static JsonArray rows(JsonObject body) throws IOException {
        if (body.has("data") && body.get("data").isJsonArray()) return body.getAsJsonArray("data");
        if (body.has("output") && body.get("output").isJsonObject()) {
            JsonObject output = body.getAsJsonObject("output");
            if (output.has("models") && output.get("models").isJsonArray()) return output.getAsJsonArray("models");
        }
        throw new IOException("MODEL_PARSE");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : "";
    }

    static boolean textChatModel(JsonObject model, String id) {
        JsonElement inputs = model.get("input_modalities");
        JsonElement modalities = model.get("output_modalities");
        if (model.has("inference_metadata") && model.get("inference_metadata").isJsonObject()) {
            modalities = model.getAsJsonObject("inference_metadata").get("response_modality");
            inputs = model.getAsJsonObject("inference_metadata").get("request_modality");
        }
        if (inputs != null && inputs.isJsonArray() && inputs.getAsJsonArray().size() > 0
                && !hasText(inputs.getAsJsonArray())) return false;
        if (modalities != null && modalities.isJsonArray() && modalities.getAsJsonArray().size() > 0) {
            boolean text = false;
            for (JsonElement value : modalities.getAsJsonArray()) {
                if (value.isJsonPrimitive() && "text".equalsIgnoreCase(value.getAsString())) text = true;
            }
            if (!text) return false;
        }
        String name = id.toLowerCase(Locale.US);
        return !name.contains("embedding") && !name.contains("rerank") && !name.contains("-tts")
                && !name.contains("-asr") && !name.contains("whisper") && !name.contains("realtime")
                && !name.startsWith("tts-") && !name.startsWith("asr-")
                && !name.startsWith("cosyvoice") && !name.startsWith("sambert")
                && !name.startsWith("gpt-image") && !name.startsWith("dall-e") && !name.startsWith("glm-ocr")
                && !name.startsWith("qwen-image") && !name.startsWith("glm-image")
                && !name.startsWith("cogview") && !name.startsWith("cogvideo") && !name.startsWith("wan");
    }

    private static boolean hasText(JsonArray values) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && "text".equalsIgnoreCase(value.getAsString())) return true;
        }
        return false;
    }

    public static Catalog presets(String id, String message) {
        String[] candidates;
        if ("deepseek".equals(id)) candidates = new String[]{"deepseek-flash", "deepseek-pro"};
        else if ("qwen".equals(id)) candidates = new String[]{"qwen-plus", "qwen-max", "qwen-turbo"};
        else if ("glm".equals(id)) candidates = new String[]{"glm-5.3", "glm-5.2", "glm-5.3-flash"};
        else if ("kimi".equals(id)) candidates = new String[]{"kimi-k2.5", "moonshot-v1-8k", "moonshot-v1-32k"};
        else if ("mimo".equals(id)) candidates = new String[]{"mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.6-flash"};
        else return Catalog.error("请选择默认提供方");
        List<Model> models = new ArrayList<Model>();
        for (String model : candidates) models.add(new Model(model, model));
        return new Catalog("preset", message + "；官方候选未验证本 Key 权限，请自行确认型号是否仍受支持",
                PRESET_DATE, models);
    }

    private static boolean unsupported(int code) { return code == 404 || code == 405 || code == 501; }
    private static void checkCancelled(CancelScope scope) throws InterruptedIOException {
        if (scope.isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("MODEL_CANCELLED");
    }
    private static final class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code) { super("MODEL_HTTP_" + code); this.code = code; }
    }
}
