package com.tvbox.android44.data.remote;

import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.domain.model.AiProvider;
import com.google.gson.JsonParser;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AiClientTest {
    private MockWebServer server;
    @Before public void setUp() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void tearDown() throws Exception { server.shutdown(); }
    private AiClient client() { return new AiClient(new OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()); }
    private AiClient.ChatResult ask(AiClient client, CancelScope scope) throws Exception {
        return client.chat(server.url("/v1").toString(), "unit-test-key", "fixture-model", "system", "推荐电影", scope);
    }
    @Test public void fourProvidersNormalizePathsAndSendModelAuthorizationAndMessages() throws Exception {
        String[] urls = {"https://apihub.agnes-ai.com/v1/chat/completions", "https://api.deepseek.com/chat/completions",
                "https://api.siliconflow.cn/v1/chat/completions", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"};
        for (int i = 0; i < SettingsRepository.AI_PROVIDERS.size(); i++) {
            AiProvider provider = SettingsRepository.AI_PROVIDERS.get(i);
            assertEquals(urls[i], AiClient.normalizeApiBase(provider.apiBase));
            String path = HttpUrl.parse(provider.apiBase).encodedPath();
            server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"推荐结果\"}}]}"));
            AiClient.ChatResult result = client().chat(server.url(path).toString(), "unit-test-key", provider.defaultModel, "system", "query", null);
            assertEquals("推荐结果", result.content);
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals(HttpUrl.parse(urls[i]).encodedPath(), request.getPath());
            assertEquals("Bearer unit-test-key", request.getHeader("Authorization"));
            com.google.gson.JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
            assertEquals(provider.defaultModel, body.get("model").getAsString()); assertEquals(2, body.getAsJsonArray("messages").size());
        }
    }
    @Test public void unauthorizedAndRateLimitResponsesNeverReturnServerBody() throws Exception {
        for (int status : new int[]{401, 403, 429}) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("private-server-body-marker"));
            AiClient.ChatResult result = ask(client(), null); assertEquals(status, result.httpCode); assertNull(result.content);
        }
        server.enqueue(new MockResponse().setResponseCode(500).setBody("private-server-body-marker"));
        try { ask(client(), null); fail(); } catch (IOException error) { assertEquals("HTTP 500", error.getMessage()); }
    }
    @Test public void emptyAndMalformedShapesProduceTypedErrorsWithoutRawContent() throws Exception {
        String[] bodies = {"", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}"};
        for (String body : bodies) {
            try { AiClient.parseContent(body); fail(); } catch (AiClient.ResponseException error) { assertEquals(ErrorKind.EMPTY_BODY, error.kind); }
        }
        for (String body : new String[]{"not-json-private-marker", "null", "[]", "{\"choices\":[null]}", "{\"choices\":[{\"message\":null}]}"}) {
            try { AiClient.parseContent(body); fail(); } catch (AiClient.ResponseException error) {
                assertEquals(ErrorKind.PARSE, error.kind); assertNull(error.getCause()); assertFalse(error.getMessage().contains("private-marker"));
            }
        }
    }
    @Test public void malformedBaseUrlsAreRejectedAndTrailingSlashDoesNotDuplicateEndpoint() {
        assertEquals("", AiClient.normalizeApiBase("file:///key")); assertEquals("", AiClient.normalizeApiBase("http://bad host"));
        assertEquals("https://example.com/v1/chat/completions", AiClient.normalizeApiBase(" https://example.com/v1/chat/completions/// "));
    }
    @Test public void timeoutAndCancellationInterruptRequests() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS));
        AiClient fast = new AiClient(new OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build());
        try { ask(fast, null); fail(); } catch (IOException error) { assertEquals(ErrorKind.TIMEOUT, ErrorKind.fromException(error)); }
        server.takeRequest(1, TimeUnit.SECONDS);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ExecutorService worker = Executors.newSingleThreadExecutor(); CancelScope scope = new CancelScope();
        try {
            Future<?> task = worker.submit(() -> { try { ask(client(), scope); fail(); } catch (Exception expected) { assertTrue(scope.isCancelled()); } });
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)); scope.cancel(); task.get(2, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
