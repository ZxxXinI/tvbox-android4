package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.parser.NameNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 详情渐进补线（文档 06 §5）：
 * - 主详情先行渲染；其他来源按片名搜索→最匹配候选→详情。
 * - 替代来源最多 3 条并发，单来源完整流程超时 4 秒。
 * - 每发现有效线路立即追加（不重置当前线路/选集/焦点）；按稳定 lineId 去重。
 * - 失败源进入 2 分钟冷却；取消不进入冷却。
 */
public class DetailSupplement {

    public interface Listener {
        void onLineAppended(Movie detail, int appendedLineCount);

        void onProgress(int completedSources, int totalSources);

        void onDone();
    }

    private final ExecutorService executor;
    private final MovieRepository repo;
    private final SettingsRepository settings;

    public DetailSupplement(ExecutorService executor, MovieRepository repo,
                            SettingsRepository settings) {
        this.executor = executor;
        this.repo = repo;
        this.settings = settings;
    }

    public Handle start(final Movie mainDetail, final Listener listener) {
        final CancelScope scope = new CancelScope();
        final List<Future<?>> futures = new ArrayList<Future<?>>();
        final AtomicInteger completed = new AtomicInteger();
        long now = System.currentTimeMillis();

        ApiLine main = null;
        for (ApiLine l : settings.allApis()) {
            if (l.id.equals(mainDetail.apiLineId)) {
                main = l;
                break;
            }
        }
        List<ApiLine> candidates = new ArrayList<ApiLine>();
        if (main != null) {
            for (ApiLine l : settings.allApis()) {
                if (l.id.equals(main.id) || repo.isCoolingDown(l.id, now)) {
                    continue;
                }
                candidates.add(l);
            }
        }
        final int total = candidates.size();
        if (total == 0) {
            TvBoxApp.get().executors().main(new Runnable() {
                @Override
                public void run() {
                    if (!scope.isCancelled()) listener.onDone();
                }
            });
            return new Handle(scope, futures);
        }

        final Semaphore slots = new Semaphore(AppConstants.SEARCH_MAX_CONCURRENT);
        for (final ApiLine line : candidates) {
            futures.add(executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        slots.acquire();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (!scope.isCancelled()) {
                            supplementOne(line, mainDetail, scope, listener);
                        }
                    } finally {
                        slots.release();
                        final int done = completed.incrementAndGet();
                        TvBoxApp.get().executors().main(new Runnable() {
                            @Override
                            public void run() {
                                if (scope.isCancelled()) {
                                    return;
                                }
                                listener.onProgress(done, total);
                                if (done >= total) {
                                    listener.onDone();
                                }
                            }
                        });
                    }
                }
            }));
        }
        return new Handle(scope, futures);
    }

    private void supplementOne(ApiLine line, final Movie mainDetail, CancelScope scope,
                               final Listener listener) {
        long deadline = System.currentTimeMillis() + AppConstants.DETAIL_SUPPLEMENT_TIMEOUT_MS;
        // 1) 按片名搜索
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            repo.markCooldown(line.id, System.currentTimeMillis());
            return;
        }
        Result<PagedMovies> sr = repo.searchSync(line, mainDetail.name, 1, scope, remain);
        if (scope.isCancelled()) {
            return;
        }
        if (!sr.isSuccess() || sr.data() == null || sr.data().movies.isEmpty()) {
            repo.markCooldown(line.id, System.currentTimeMillis());
            return;
        }
        // 2) 最匹配候选（规范化片名相等优先，其次包含 + 年份辅助）
        Movie best = bestMatch(mainDetail, sr.data().movies);
        if (best == null) {
            repo.markCooldown(line.id, System.currentTimeMillis());
            return;
        }
        // 3) 取详情
        remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            repo.markCooldown(line.id, System.currentTimeMillis());
            return;
        }
        try {
            com.tvbox.android44.data.remote.dto.VodResponseDto dto =
                    new com.tvbox.android44.data.remote.MacCmsClient().query(
                            line.baseUrl, null, null, best.id, 0, remain, scope);
            if (dto.list != null && !dto.list.isEmpty()) {
                Movie rich = com.tvbox.android44.data.remote.MacCmsMapper.toMovie(
                        line, dto.list.get(0), true);
                if (rich != null && !rich.playSources.isEmpty()) {
                    appendLines(mainDetail, rich, scope, listener);
                    MultiSourceSearch.stats(line.id).lastSuccessAt = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            if (scope.isCancelled()) {
                return;
            }
            repo.markCooldown(line.id, System.currentTimeMillis());
        }
    }

    /** 把替代来源的线路按稳定 lineId 去重后追加；追加后主线程通知。 */
    private void appendLines(final Movie mainDetail, final Movie other, final CancelScope scope,
                             final Listener listener) {
        TvBoxApp.get().executors().main(new Runnable() {
            public void run() {
                if (scope.isCancelled()) return;
                mergeLines(mainDetail, other, listener);
            }
        });
    }

    static void mergeLines(final Movie mainDetail, Movie other, final Listener listener) {
        int appended = 0;
        synchronized (mainDetail) {
            for (PlaySource ps : other.playSources) {
                boolean exists = false;
                for (PlaySource exist : mainDetail.playSources) {
                    if (exist.lineId.equals(ps.lineId)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists && !ps.episodes.isEmpty()) {
                    mainDetail.playSources.add(ps);
                    appended++;
                }
            }
            if (!mainDetail.availableSourceIds.contains(other.apiLineId)) {
                mainDetail.availableSourceIds.add(other.apiLineId);
            }
        }
        if (appended > 0) {
            final int count = appended;
            listener.onLineAppended(mainDetail, count);
        }
    }

    /** 匹配规则：规范化名相等（含年份相等加分）> 包含。 */
    static Movie bestMatch(Movie target, List<Movie> candidates) {
        String tName = NameNormalizer.normalize(target.name);
        if (tName.isEmpty()) return null;
        String tYear = NameNormalizer.normalizeYear(target.year);
        Movie equal = null;
        Movie contains = null;
        for (Movie c : candidates) {
            if (c == null) continue;
            String cName = NameNormalizer.normalize(c.name);
            if (cName.isEmpty()) continue;
            if (cName.equals(tName)) {
                String cYear = NameNormalizer.normalizeYear(c.year);
                if (!tYear.isEmpty() && tYear.equals(cYear)) return c;
                if (tYear.isEmpty() || cYear.isEmpty()) {
                    if (equal == null) {
                        equal = c;
                    }
                    continue;
                }
                continue;
            }
            if (contains == null && (cName.contains(tName) || tName.contains(cName))) {
                contains = c;
            }
        }
        return equal != null ? equal : contains;
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
