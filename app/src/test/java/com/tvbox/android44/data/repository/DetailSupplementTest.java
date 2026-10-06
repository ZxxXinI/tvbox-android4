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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = TvBoxApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DetailSupplementTest {
    private MockWebServer server;
    private ExecutorService executor;
    private DetailSupplement supplement;
    private Movie detail;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); executor = Executors.newSingleThreadExecutor();
        ApiLine main = new ApiLine("main", "主源", server.url("/main/").toString(), false);
        ApiLine other = new ApiLine("other", "备源", server.url("/other/").toString(), false);
        supplement = new DetailSupplement(executor,
                new MovieRepository(executor, TvBoxApp.get().executors().sourceRequests()), new TestSettings(main, other));
        detail = new Movie("111", "main", "主源", "同一作品"); detail.year = "2025";
        PlaySource original = new PlaySource("main|m3u8", "m3u8", "主源");
        original.episodes.add(new PlayEpisode(0, "第1集", "https://example.com/original.m3u8"));
        detail.playSources.add(original);
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                boolean rich = request.getRequestUrl().queryParameter("ids") != null;
                return new MockResponse().setBody("{\"list\":[{\"vod_id\":\"222\",\"type_id\":1,\"vod_name\":\"同一作品\",\"vod_year\":\"2025\""
                        + (rich ? ",\"vod_play_from\":\"m3u8\",\"vod_play_url\":\"第1集$https://example.com/other.m3u8\"" : "") + "}]}");
            }
        });
    }
    @After public void tearDown() throws Exception {
        executor.shutdownNow(); TvBoxApp.get().executors().shutdown(); server.shutdown();
    }
    private DetailSupplement.Listener listener(CountDownLatch done, AtomicInteger appends) {
        return new DetailSupplement.Listener() {
            public void onLineAppended(Movie movie, int count) { appends.addAndGet(count); }
            public void onProgress(int completed, int total) { }
            public void onDone() { done.countDown(); }
        };
    }
    @Test public void appendsAlternativeWithoutChangingExistingLineAndDeduplicates() throws Exception {
        PlaySource original = detail.playSources.get(0);
        CountDownLatch done = new CountDownLatch(1); AtomicInteger appends = new AtomicInteger();
        supplement.start(detail, listener(done, appends)); AsyncTest.await(done);
        assertEquals(2, detail.playSources.size()); assertSame(original, detail.playSources.get(0));
        assertEquals(1, appends.get());
        CountDownLatch repeated = new CountDownLatch(1);
        supplement.start(detail, listener(repeated, appends)); AsyncTest.await(repeated);
        assertEquals(2, detail.playSources.size()); assertEquals(1, appends.get());
    }
    @Test public void cancelSuppressesAlreadyQueuedAppendAndDoneCallbacks() throws Exception {
        AtomicInteger appends = new AtomicInteger(); CountDownLatch done = new CountDownLatch(1);
        DetailSupplement.Handle handle = supplement.start(detail, listener(done, appends));
        executor.submit(() -> {}).get(8, TimeUnit.SECONDS); handle.cancel();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, detail.playSources.size()); assertEquals(0, appends.get()); assertEquals(1, done.getCount());
    }
    @Test public void matchingPrefersExactYearAndRejectsWrongYearOrEmptyNames() {
        Movie unknown = new Movie("unknown", "other", "", "同一作品");
        Movie exact = new Movie("exact", "other", "", "同一作品"); exact.year = "2025";
        Movie wrong = new Movie("wrong", "other", "", "同一作品"); wrong.year = "1999";
        assertSame(exact, DetailSupplement.bestMatch(detail, Arrays.asList(unknown, wrong, exact)));
        assertNull(DetailSupplement.bestMatch(detail, Arrays.asList(wrong, new Movie())));
        assertNull(DetailSupplement.bestMatch(new Movie(), Arrays.asList(exact)));
    }
}
