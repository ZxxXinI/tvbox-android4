package com.tvbox.android44.data.repository;

import android.os.Looper;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.*;
import com.tvbox.android44.data.remote.AiClient;
import com.tvbox.android44.domain.model.*;
import com.tvbox.android44.testutil.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = TvBoxApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RecommendRepositoryTest {
    private MockWebServer server;
    private ExecutorService worker;
    private TestSettings settings;
    private RecommendRepository repository;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); worker = Executors.newSingleThreadExecutor();
        settings = new TestSettings() { @Override public AiProvider aiProvider() { return new AiProvider("fixture", "Fixture", server.url("/v1").toString(), "model"); } };
        settings.setAiProvider("deepseek"); settings.setAiApiKey("unit-test-key"); settings.setAiModel("model");
        repository = new RecommendRepository(worker, settings, new AiClient(new OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()));
    }
    @After public void tearDown() throws Exception { worker.shutdownNow(); TvBoxApp.get().executors().shutdown(); server.shutdown(); }
    private MockResponse response(String content) {
        com.google.gson.JsonObject message = new com.google.gson.JsonObject(); message.addProperty("content", content);
        com.google.gson.JsonObject choice = new com.google.gson.JsonObject(); choice.add("message", message);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray(); choices.add(choice);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject(); root.add("choices", choices);
        return new MockResponse().setBody(root.toString());
    }
    private Result<List<AiRecommendItem>> ask() throws Exception {
        AtomicReference<Result<List<AiRecommendItem>>> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        repository.ask("query", value -> { result.set(value); done.countDown(); }); AsyncTest.await(done); return result.get();
    }
    @Test public void singleWorkerHandlesSuccessAndResponseFailures() throws Exception {
        server.enqueue(response("{\"recommendations\":[{\"title\":\"影片\",\"searchKeyword\":\"影片\"}]}"));
        Result<List<AiRecommendItem>> success = ask(); assertTrue(success.isSuccess()); assertEquals("影片", success.data().get(0).title);
        for (int code : new int[]{401, 403, 429}) {
            server.enqueue(new MockResponse().setResponseCode(code).setBody("private-marker"));
            Result<List<AiRecommendItem>> result = ask(); assertEquals(code == 429 ? ErrorKind.HTTP : ErrorKind.PERMISSION, result.asFailure().kind);
            assertFalse(result.asFailure().userMessage.contains("private-marker"));
        }
    }
    @Test public void malformedAndEmptyResponsesHaveUsefulClassifications() throws Exception {
        server.enqueue(response("not-json-private-marker")); assertEquals(ErrorKind.PARSE, ask().asFailure().kind);
        server.enqueue(new MockResponse().setBody("{\"choices\":[]}")); assertEquals(ErrorKind.EMPTY_BODY, ask().asFailure().kind);
        server.enqueue(new MockResponse().setBody("{\"choices\":[null]}")); assertEquals(ErrorKind.PARSE, ask().asFailure().kind);
    }
    @Test public void cancelDiscardsAlreadyQueuedResultWithoutAffectingNextRequest() throws Exception {
        server.enqueue(response("[{\"title\":\"旧结果\"}]")); server.enqueue(response("[{\"title\":\"新结果\"}]"));
        AtomicInteger old = new AtomicInteger(); AtomicReference<String> next = new AtomicReference<>();
        RecommendRepository.Handle handle = repository.ask("old", result -> old.incrementAndGet());
        worker.submit(() -> {}).get(3, TimeUnit.SECONDS); handle.cancel();
        repository.ask("new", result -> next.set(result.data().get(0).title)); worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, old.get()); assertEquals("新结果", next.get());
    }
    @Test public void cancellingUnconfiguredRequestAlsoSuppressesQueuedError() {
        settings.setAiApiKey(""); AtomicInteger callbacks = new AtomicInteger();
        RecommendRepository.Handle handle = repository.ask("query", result -> callbacks.incrementAndGet()); handle.cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, callbacks.get()); assertEquals(0, server.getRequestCount());
    }
    @Test public void cancellingInFlightRequestFreesWorkerForNextQuery() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(response("[{\"title\":\"新结果\"}]"));
        AtomicInteger old = new AtomicInteger(); RecommendRepository.Handle handle = repository.ask("old", result -> old.incrementAndGet());
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)); handle.cancel();
        assertEquals("新结果", ask().data().get(0).title); assertEquals(0, old.get());
    }
}
