package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.parser.NameNormalizer;
import com.tvbox.android44.domain.parser.SearchResultMerger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多来源搜索编排（文档 06 §3）：
 * - 主来源固定第一；其余按近期成功/延迟排序，排除冷却项。
 * - 同时在途最多 3 条；单来源 3 秒超时。
 * - 任一来源返回立即增量展示；单源失败只计完成数，不置整页错误。
 * - 所有来源完成且结果为空才空态；取消不计失败。
 */
public class MultiSourceSearch {

    public interface Listener {
        /** 任一来源完成后的增量快照（主线程）。 */
        void onIncremental(List<Movie> merged, int completed, int totalSources, int foundCount);

        /** 全部完成（主线程）。anySuccess 表示至少一个来源成功。 */
        void onFinished(List<Movie> merged, boolean anySuccess);
    }

    /** 来源近期表现统计（内存，用于排序）。 */
    static final class SourceStats {
        long lastSuccessAt;
        long lastLatencyMs = Long.MAX_VALUE;
    }

    static final Map<String, SourceStats> STATS =
            new HashMap<String, SourceStats>();

    private final ExecutorService executor;
    private final MovieRepository repo;
    private final SettingsRepository settings;

    public MultiSourceSearch(ExecutorService executor, MovieRepository repo,
                             SettingsRepository settings) {
        this.executor = executor;
        this.repo = repo;
        this.settings = settings;
    }

    public Handle search(String query, final Listener listener) {
        final String q = query.trim();
        final CancelScope scope = new CancelScope();
        final SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        final List<Future<?>> futures = new ArrayList<Future<?>>();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicBoolean anySuccess = new AtomicBoolean(false);
        final long now = System.currentTimeMillis();

        // 来源顺序：主来源第一；其余按近期成功/延迟排序，排除冷却
        ApiLine main = settings.currentApi();
        List<ApiLine> ordered = new ArrayList<ApiLine>();
        ordered.add(main);
        List<ApiLine> others = new ArrayList<ApiLine>();
        for (ApiLine l : settings.allApis()) {
            if (l.id.equals(main.id) || repo.isCoolingDown(l.id, now)) {
                continue;
            }
            others.add(l);
        }
        java.util.Collections.sort(others, new Comparator<ApiLine>() {
            @Override
            public int compare(ApiLine a, ApiLine b) {
                SourceStats sa = stats(a.id);
                SourceStats sb = stats(b.id);
                int bySuccess = Long.compare(sb.lastSuccessAt, sa.lastSuccessAt);
                if (bySuccess != 0) {
                    return bySuccess;
                }
                return Long.compare(sa.lastLatencyMs, sb.lastLatencyMs);
            }
        });
        ordered.addAll(others);
        final int total = ordered.size();

        final Semaphore slots = new Semaphore(AppConstants.SEARCH_MAX_CONCURRENT);
        for (final ApiLine line : ordered) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        slots.acquire();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (scope.isCancelled()) {
                            return;
                        }
                        long start = System.currentTimeMillis();
                        Result<PagedMovies> r = repo.searchSync(line, q, 1, scope,
                                AppConstants.SEARCH_TIMEOUT_MS);
                        long latency = System.currentTimeMillis() - start;
                        if (!scope.isCancelled()) {
                            postResult(line, r, latency);
                        }
                    } finally {
                        slots.release();
                    }
                }

                private void postResult(final ApiLine line, final Result<PagedMovies> r,
                                        long latency) {
                    boolean ok = false;
                    int found = 0;
                    if (r.isSuccess() && r.data() != null) {
                        ok = true;
                        SourceStats st = stats(line.id);
                        st.lastSuccessAt = System.currentTimeMillis();
                        st.lastLatencyMs = latency;
                        synchronized (merged) {
                            for (Movie m : r.data().movies) {
                                merged.add(m);
                            }
                            found = merged.size();
                        }
                    }
                    if (ok) {
                        anySuccess.set(true);
                    }
                    final boolean fok = ok;
                    final int foundCount = found;
                    final int done = completed.incrementAndGet();
                    TvBoxApp.get().executors().main(new Runnable() {
                        @Override
                        public void run() {
                            if (scope.isCancelled()) {
                                return;
                            }
                            if (fok) {
                                listener.onIncremental(snapshot(merged), done, total, foundCount);
                            } else {
                                listener.onIncremental(snapshot(merged), done, total, merged.size());
                            }
                            if (done >= total) {
                                listener.onFinished(snapshot(merged), anySuccess.get());
                            }
                        }
                    });
                }
            }));
        }
        return new Handle(scope, futures);
    }

    static SourceStats stats(String id) {
        synchronized (STATS) {
            SourceStats s = STATS.get(id);
            if (s == null) {
                s = new SourceStats();
                STATS.put(id, s);
            }
            return s;
        }
    }

    private static List<Movie> snapshot(SearchResultMerger.Merged merged) {
        synchronized (merged) {
            return new ArrayList<Movie>(merged.movies);
        }
    }

    public static final class Handle {
        private final CancelScope scope;
        private final List<Future<?>> futures;

        Handle(CancelScope scope, List<Future<?>> futures) {
            this.scope = scope;
            this.futures = futures;
        }

        public void cancel() {
            scope.cancel();
            synchronized (futures) {
                for (Future<?> f : futures) {
                    f.cancel(true);
                }
            }
        }
    }
}
