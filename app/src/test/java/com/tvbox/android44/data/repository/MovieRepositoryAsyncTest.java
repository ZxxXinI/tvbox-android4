package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.testutil.AsyncTest;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = TvBoxApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MovieRepositoryAsyncTest {
    private MockWebServer server;
    private ExecutorService parentPool, childPool;
    private MovieRepository repo;
    private ApiLine api;

    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        parentPool = Executors.newSingleThreadExecutor(); childPool = Executors.newFixedThreadPool(3);
        repo = new MovieRepository(parentPool, childPool);
        api = new ApiLine("test", "测试", server.url("/api/").toString(), false);
    }
    @After public void tearDown() throws Exception {
        parentPool.shutdownNow(); childPool.shutdownNow(); TvBoxApp.get().executors().shutdown(); server.shutdown();
    }
    private static String body(String id) {
        return "{\"page\":1,\"pagecount\":2,\"total\":2,\"list\":[{\"vod_id\":\"" + id + "\",\"type_id\":1,\"vod_name\":\"测试影片\"}]}";
    }

    @Test public void categoryAggregationCompletesWithSingleParentWorkerAndPartialFailure() throws Exception {
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                String type = request.getRequestUrl().queryParameter("t");
                if ("bad".equals(type)) return new MockResponse().setResponseCode(503);
                return new MockResponse().setBody(body(type));
            }
        });
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Result<PagedMovies>> result = new AtomicReference<>();
        repo.fetchByCategories(api, Arrays.asList("1", "2", "bad"), 1, r -> { result.set(r); done.countDown(); });
        AsyncTest.await(done);
        assertTrue(result.get().isSuccess());
        assertEquals(2, result.get().data().movies.size());
        assertEquals(3, server.getRequestCount());
        assertFalse(repo.isCoolingDown(api.id, System.currentTimeMillis()));
    }

    @Test public void cancellingOneSubscriberKeepsSharedRequestForOtherCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown(); release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setBody(body("1"));
            }
        });
        AtomicInteger cancelledCallbacks = new AtomicInteger();
        MovieRepository.Request first = repo.fetchHome(api, 1, r -> cancelledCallbacks.incrementAndGet());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Result<PagedMovies>> result = new AtomicReference<>();
        repo.fetchHome(api, 1, r -> { result.set(r); done.countDown(); });
        first.cancel(); release.countDown(); AsyncTest.await(done);
        assertTrue(result.get().isSuccess());
        assertEquals(1, server.getRequestCount());
        assertEquals(0, cancelledCallbacks.get());
    }

    @Test public void cancellationAbortsIoWithoutPuttingSourceIntoCooldown() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        CancelScope scope = new CancelScope();
        Future<Result<PagedMovies>> search = parentPool.submit(() -> repo.searchSync(api, "标题 & 空格", 1, scope, 3000));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("标题 & 空格", request.getRequestUrl().queryParameter("wd"));
        scope.cancel();
        assertTrue(search.get(5, TimeUnit.SECONDS) instanceof Result.Cancelled);
        assertFalse(repo.isCoolingDown(api.id, System.currentTimeMillis()));
    }

    @Test public void timeoutReturnsFailureAndCoolsOnlyFailedSource() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Result<PagedMovies> result = repo.searchSync(api, "慢源", 1, new CancelScope(), 150);
        assertTrue(result.isFailure());
        assertEquals(com.tvbox.android44.common.ErrorKind.TIMEOUT, result.asFailure().kind);
        assertTrue(repo.isCoolingDown(api.id, System.currentTimeMillis()));
        assertFalse(repo.isCoolingDown("other", System.currentTimeMillis()));
    }

    @Test public void concurrentClientsShareThreeRequestLimit() throws Exception {
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        CountDownLatch firstThree = new CountDownLatch(3), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                int count = active.incrementAndGet(); max.accumulateAndGet(count, Math::max);
                firstThree.countDown(); release.await(5, TimeUnit.SECONDS);
                active.decrementAndGet(); return new MockResponse().setBody(body("1"));
            }
        });
        ExecutorService callers = Executors.newFixedThreadPool(6);
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final String q = "q" + i;
                futures.add(callers.submit(() -> assertTrue(repo.searchSync(api, q, 1, new CancelScope(), 5000).isSuccess())));
            }
            assertTrue(firstThree.await(5, TimeUnit.SECONDS));
            assertEquals(3, server.getRequestCount());
            release.countDown();
            for (Future<?> future : futures) future.get(6, TimeUnit.SECONDS);
            assertEquals(3, max.get());
        } finally { release.countDown(); callers.shutdownNow(); }
    }
}
