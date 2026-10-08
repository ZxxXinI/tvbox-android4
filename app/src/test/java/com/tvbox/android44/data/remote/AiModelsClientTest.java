package com.tvbox.android44.data.remote;

import com.google.gson.Gson;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.domain.model.AiProvider;
import java.io.InterruptedIOException;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;

public class AiModelsClientTest {
    private MockWebServer server;
    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }
    private AiModelsClient client(long timeout) {
        return new AiModelsClient(new OkHttpClient(), server.url("/native/models").toString(), timeout);
    }
    private AiProvider provider(String id, String path) {
        return new AiProvider(id, id, server.url(path).toString(), "");
    }
    private MockResponse models(String rows) { return new MockResponse().setBody("{\"data\":[" + rows + "]}"); }

    @Test public void allFiveRoutesAuthenticateWithoutKeysInUrl() throws Exception {
        String[] paths = {"/", "/compatible-mode/v1", "/api/paas/v4", "/v1", "/v1"};
        for (int i = 0; i < 5; i++) {
            AiProvider known = SettingsRepository.AI_PROVIDERS.get(i);
            server.enqueue(models("{\"id\":\"text-model\",\"name\":\"Displayed model\"}"));
            AiModelsClient.Catalog result = client(2000).fetch(provider(known.id, paths[i]),
                    "test-discovery-key", new CancelScope());
            assertEquals("remote", result.source);
            assertEquals("Displayed model", result.models.get(0).name);
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals(("mimo".equals(known.id) ? "test-discovery-key" : "Bearer test-discovery-key"),
                    request.getHeader("mimo".equals(known.id) ? "api-key" : "Authorization"));
            assertEquals(paths[i].equals("/") ? "/models" : paths[i] + "/models", request.getPath());
            assertFalse(request.getPath().contains("test-discovery-key"));
            assertFalse(new Gson().toJson(result).contains("test-discovery-key"));
        }
    }

    @Test public void paginationDeduplicatesAndFiltersNonChatModels() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"has_more\":true,\"last_id\":\"cursor\","
                + "\"data\":[{\"id\":\"one\"},{\"id\":\"one\"},{\"id\":\"embedding-v3\"},"
                + "{\"id\":\"mimo-v2.5-tts\"},{\"id\":\"speech-only\",\"output_modalities\":[\"audio\"]}]}"));
        server.enqueue(models("{\"id\":\"two\"},{\"id\":\"one\"},{\"id\":\"qwen-vl-plus\"},{\"id\":\"glm-image\"}"));
        AiModelsClient.Catalog result = client(2000).fetch(provider("deepseek", "/v1"),
                "test-key", new CancelScope());
        assertEquals("remote", result.source); assertEquals(3, result.models.size());
        assertEquals("one", result.models.get(0).id); assertEquals("two", result.models.get(1).id);
        server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().contains("after=cursor"));
    }

    @Test public void qwenUnsupportedCompatFallsBackToNativeAndReadsAllPages() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setBody("{\"output\":{\"total\":2,\"models\":[{\"model\":\"qwen-one\"}]}}"));
        server.enqueue(new MockResponse().setBody("{\"output\":{\"total\":2,\"models\":[{\"model\":\"qwen-two\"}]}}"));
        AiModelsClient.Catalog result = client(2000).fetch(provider("qwen", "/compatible-mode/v1"),
                "test-key", new CancelScope());
        assertEquals("remote", result.source); assertEquals(2, result.models.size());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().contains("page_no=1"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().contains("page_no=2"));
    }

    @Test public void authFailureDoesNotPretendToBeSuccessfulOrUsePresets() throws Exception {
        for (int code : new int[]{401, 403}) {
            server.enqueue(new MockResponse().setResponseCode(code).setBody("private-key-body-marker"));
            AiModelsClient.Catalog result = client(1000).fetch(provider("qwen", "/v1"), "test-key", new CancelScope());
            assertEquals("error", result.source); assertTrue(result.models.isEmpty());
            assertFalse(new Gson().toJson(result).contains("private-key-body-marker"));
        }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void unsupportedEmptyMalformedAndRateLimitedResponsesHaveLabelledPresets() throws Exception {
        for (MockResponse response : new MockResponse[]{new MockResponse().setResponseCode(404),
                models(""), new MockResponse().setBody("private-response-marker"),
                new MockResponse().setResponseCode(429)}) {
            server.enqueue(response);
            AiModelsClient.Catalog result = client(1000).fetch(provider("glm", "/v4"), "test-key", new CancelScope());
            assertEquals("preset", result.source); assertFalse(result.models.isEmpty());
            assertEquals(AiModelsClient.PRESET_DATE, result.updatedAt);
            assertTrue(result.message.contains("未验证"));
            assertFalse(new Gson().toJson(result).contains("private-response-marker"));
        }
    }

    @Test public void timeoutBudgetIsSharedByQwenAttempts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setHeadersDelay(120, TimeUnit.MILLISECONDS));
        server.enqueue(models("{\"id\":\"one\"}").setHeadersDelay(200, TimeUnit.MILLISECONDS));
        long start = System.nanoTime();
        AiModelsClient.Catalog result = client(200).fetch(provider("qwen", "/v1"), "test-key", new CancelScope());
        assertEquals("preset", result.source);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 800);
    }

    @Test public void cancellingInFlightDiscoveryDoesNotReturnCandidates() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        CancelScope scope = new CancelScope();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                try { client(2000).fetch(provider("kimi", "/v1"), "test-key", scope); fail(); }
                catch (InterruptedIOException expected) { assertTrue(scope.isCancelled()); }
                catch (Exception unexpected) { throw new AssertionError(unexpected); }
            });
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)); scope.cancel(); task.get(1, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    @Test public void invalidKeyIsNotSentAndEchoedCredentialsAreRemovedFromModels() throws Exception {
        assertEquals("error", client(1000).fetch(provider("glm", "/v1"),
                "bad\nprivate-key-marker", new CancelScope()).source);
        assertEquals(0, server.getRequestCount());
        server.enqueue(models("{\"id\":\"test-secret-marker\"},{\"id\":\"normal-model\",\"name\":\"test-secret-marker\"}"));
        AiModelsClient.Catalog result = client(1000).fetch(provider("glm", "/v1"),
                "test-secret-marker", new CancelScope());
        assertEquals("remote", result.source); assertEquals(1, result.models.size());
        assertFalse(new Gson().toJson(result).contains("test-secret-marker"));
    }

    @Test public void redirectNeverForwardsApiKeyToAnotherTarget() throws Exception {
        MockWebServer target = new MockWebServer(); target.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target.url("/steal")));
            AiModelsClient.Catalog result = client(1000).fetch(provider("mimo", "/v1"), "test-key", new CancelScope());
            assertEquals("preset", result.source);
            assertEquals(0, target.getRequestCount());
        } finally { target.shutdown(); }
    }
}
