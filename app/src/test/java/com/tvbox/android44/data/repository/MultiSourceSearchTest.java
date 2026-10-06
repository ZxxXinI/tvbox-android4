package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.domain.model.*;
import com.tvbox.android44.testutil.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = TvBoxApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MultiSourceSearchTest {
    private MockWebServer server;
    private ExecutorService executor;
    private MovieRepository repo;
    private MultiSourceSearch search;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); executor = Executors.newFixedThreadPool(4);
        repo = new MovieRepository(executor, TvBoxApp.get().executors().sourceRequests());
        ApiLine main = new ApiLine("main", "主来源", server.url("/main/").toString(), false);
        ApiLine other = new ApiLine("other", "其他来源", server.url("/other/").toString(), false);
        search = new MultiSourceSearch(executor, repo, new TestSettings(main, other));
    }
    @After public void tearDown() throws Exception {
        executor.shutdownNow(); TvBoxApp.get().executors().shutdown(); server.shutdown();
    }
    private static String body(String id) {
        return "{\"list\":[{\"vod_id\":\"" + id + "\",\"type_id\":1,\"vod_name\":\"同一作品\",\"vod_year\":\"2025\"}]}";
    }
    @Test public void fastAlternativeRendersBeforeMainAndLateMainWins() throws Exception {
        CountDownLatch release = new CountDownLatch(1), incremental = new CountDownLatch(1), finished = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().startsWith("/main/")) {
                    release.await(6, TimeUnit.SECONDS); return new MockResponse().setBody(body("111"));
                }
                return new MockResponse().setBody(body("222"));
            }
        });
        AtomicReference<List<Movie>> result = new AtomicReference<>();
        search.search("同一作品", new MultiSourceSearch.Listener() {
            public void onIncremental(List<Movie> movies, int completed, int total, int found) {
                if (release.getCount() > 0) {
                    assertEquals("other", movies.get(0).apiLineId); assertEquals(1, completed);
                    incremental.countDown();
                }
            }
            public void onFinished(List<Movie> movies, boolean success) {
                assertTrue(success); result.set(movies); finished.countDown();
            }
        });
        AsyncTest.await(incremental); assertEquals(1, finished.getCount());
        release.countDown(); AsyncTest.await(finished);
        assertEquals(1, result.get().size());
        assertEquals("main", result.get().get(0).apiLineId);
        assertEquals(2, result.get().get(0).availableSourceIds.size());
    }
    @Test public void oneFailedSourceDoesNotDiscardSuccessfulResults() throws Exception {
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                return request.getPath().startsWith("/main/") ? new MockResponse().setResponseCode(500)
                        : new MockResponse().setBody(body("222"));
            }
        });
        CountDownLatch done = new CountDownLatch(1);
        search.search("影片", new MultiSourceSearch.Listener() {
            public void onIncremental(List<Movie> m, int c, int t, int f) { }
            public void onFinished(List<Movie> movies, boolean success) {
                assertTrue(success); assertEquals(1, movies.size()); done.countDown();
            }
        });
        AsyncTest.await(done);
    }
    @Test public void cancelledSearchHasNoLateUiCallbacksOrCooldown() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        AtomicInteger callbacks = new AtomicInteger();
        MultiSourceSearch.Handle handle = search.search("取消", new MultiSourceSearch.Listener() {
            public void onIncremental(List<Movie> m, int c, int t, int f) { callbacks.incrementAndGet(); }
            public void onFinished(List<Movie> m, boolean s) { callbacks.incrementAndGet(); }
        });
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); handle.cancel();
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
        assertFalse(repo.isCoolingDown("main", System.currentTimeMillis()));
        assertFalse(repo.isCoolingDown("other", System.currentTimeMillis()));
    }
}
