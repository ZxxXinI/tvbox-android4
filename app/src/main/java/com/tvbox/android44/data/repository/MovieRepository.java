package com.tvbox.android44.data.repository;

import android.util.LruCache;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.MacCmsClient;
import com.tvbox.android44.data.remote.MacCmsMapper;
import com.tvbox.android44.data.remote.dto.VodResponseDto;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Category;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.model.PlaySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * MacCMS 单来源仓库：内存 LRU（列表 5min/80、详情 30min/60）、
 * 来源失败冷却 2 分钟、相同在途请求合并。
 * 回调统一在主线程分发；网络全部离开主线程。
 */
public class MovieRepository {

    private static final class CacheEntry<T> {
        final T value;
        final long at;

        CacheEntry(T value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private static final class Waiter {
        final Request request;
        final Callback<?> callback;
        Waiter(Request request, Callback<?> callback) {
            this.request = request;
            this.callback = callback;
        }
    }

    private static final class InFlight {
        final CancelScope scope = new CancelScope();
        FutureTask<Result<?>> task;
        final List<Waiter> waiters = new ArrayList<Waiter>();
    }

    public interface Callback<T> {
        void onResult(Result<T> result);
    }

    /** 可取消请求句柄。 */
    public static final class Request {
        private final CancelScope scope = new CancelScope();
        private volatile Runnable cancelSubscriber;

        public void cancel() {
            scope.cancel();
            Runnable cancel = cancelSubscriber;
            if (cancel != null) cancel.run();
        }
    }

    private final ExecutorService executor;
    private final ExecutorService categoryExecutor;
    private final MacCmsClient client = new MacCmsClient();

    private final LruCache<String, CacheEntry<PagedMovies>> listCache =
            new LruCache<String, CacheEntry<PagedMovies>>(AppConstants.LIST_CACHE_MAX_ENTRIES);
    private final LruCache<String, CacheEntry<Movie>> detailCache =
            new LruCache<String, CacheEntry<Movie>>(AppConstants.DETAIL_CACHE_MAX_ENTRIES);
    private final LruCache<String, CacheEntry<List<Category>>> categoryCache =
            new LruCache<String, CacheEntry<List<Category>>>(8);
    private final ConcurrentHashMap<String, InFlight> inFlight =
            new ConcurrentHashMap<String, InFlight>();
    private final ConcurrentHashMap<String, Long> cooldownUntil =
            new ConcurrentHashMap<String, Long>();

    public MovieRepository(ExecutorService executor) {
        this(executor, TvBoxApp.get().executors().sourceRequests());
    }

    public MovieRepository(ExecutorService executor, ExecutorService categoryExecutor) {
        this.executor = executor;
        this.categoryExecutor = categoryExecutor;
    }

    // ===== 冷却 =====

    public boolean isCoolingDown(String apiLineId, long now) {
        Long until = cooldownUntil.get(apiLineId);
        return until != null && until > now;
    }

    void markCooldown(String apiLineId, long now) {
        cooldownUntil.put(apiLineId, now + AppConstants.SOURCE_COOLDOWN_MS);
    }

    void clearCooldown(String apiLineId) {
        cooldownUntil.remove(apiLineId);
    }

    public void onTrimMemory() {
        listCache.evictAll();
        detailCache.evictAll();
    }

    /** 切换视频接口 / 内存压力时清理。 */
    public void invalidateAll() {
        listCache.evictAll();
        detailCache.evictAll();
        categoryCache.evictAll();
        cooldownUntil.clear();
    }

    // ===== 异步操作 =====

    public Request fetchHome(ApiLine apiLine, int page, Callback<PagedMovies> cb) {
        return submitList("home|" + apiLine.id + "|" + page, apiLine, page, null, cb);
    }

    public Request fetchByCategory(ApiLine apiLine, String typeId, int page,
                                    Callback<PagedMovies> cb) {
        List<String> typeIds = typeId == null || typeId.isEmpty()
                ? null : Collections.singletonList(typeId);
        return fetchByCategories(apiLine, typeIds, page, cb);
    }

    /**
     * 查询一个分类集合。父分类的“全部”不能只请求父 ID：部分 MacCMS 源把影片
     * 只挂在子分类下，父 ID 本身可能只有 1 条直挂数据，因此这里合并所有子分类页。
     */
    public Request fetchByCategories(ApiLine apiLine, List<String> typeIds, int page,
                                     Callback<PagedMovies> cb) {
        String key = "cat|" + apiLine.id + "|" + typeKey(typeIds) + "|" + page;
        return submitList(key, apiLine, page, typeIds, cb);
    }

    /** 分类表（ac=list）：30 分钟缓存；失败不进入来源冷却（不阻塞列表请求）。 */
    public Request fetchCategories(ApiLine apiLine, Callback<List<Category>> cb) {
        String key = "cats|" + apiLine.id;
        CacheEntry<List<Category>> cached = categoryCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at <= AppConstants.CATEGORY_CACHE_TTL_MS) {
            Request noop = new Request();
            deliverMain(noop, cb, new Result.Success<List<Category>>(cached.value, apiLine.id, true));
            return noop;
        }
        final Request request = new Request();
        submitMerged(key, request, new Worker<Result<List<Category>>>() {
            @Override
            public Result<List<Category>> work(CancelScope scope) {
                try {
                    VodResponseDto dto = client.listCategories(apiLine.baseUrl,
                            AppConstants.LIST_CALL_TIMEOUT_MS, scope);
                    List<Category> categories = MacCmsMapper.toCategories(dto);
                    if (categories.isEmpty()) {
                        return new Result.Failure<List<Category>>(
                                ErrorKind.EMPTY_BODY, "来源未返回分类", null);
                    }
                    categoryCache.put(key, new CacheEntry<List<Category>>(
                            categories, System.currentTimeMillis()));
                    return new Result.Success<List<Category>>(categories, apiLine.id, false);
                } catch (IOException e) {
                    if (scope.isCancelled()) {
                        return cancelledResult();
                    }
                    ErrorKind kind = ErrorKind.fromException(e);
            if (com.tvbox.android44.BuildConfig.DEBUG) {
                android.util.Log.d("TVBOX_ERR", kind + " " + e.getClass().getSimpleName());
            }
                    return new Result.Failure<List<Category>>(kind, kind.userMessage(), e);
                }
            }
        }, cb);
        return request;
    }

    public Request fetchDetail(ApiLine apiLine, String movieId, Callback<Movie> cb) {
        String key = "detail|" + apiLine.id + "|" + movieId;
        CacheEntry<Movie> cached = detailCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at <= AppConstants.DETAIL_CACHE_TTL_MS) {
            final Result<Movie> r = new Result.Success<Movie>(cached.value, apiLine.id, true);
            Request noop = new Request();
            deliverMain(noop, cb, r);
            return noop;
        }
        final Request request = new Request();
        submitMerged(key, request, new Worker<Result<Movie>>() {
            @Override
            public Result<Movie> work(CancelScope scope) {
                return doFetchDetail(apiLine, movieId, key, scope);
            }
        }, cb);
        return request;
    }

    /** 同步搜索（多源编排调用；带整体超时；取消返回 Cancelled 且不计失败）。 */
    public Result<PagedMovies> searchSync(ApiLine apiLine, String keyword, int page,
                                          CancelScope scope, long timeoutMs) {
        String key = "search|" + apiLine.id + "|" + keyword + "|" + page;
        CacheEntry<PagedMovies> cached = listCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at <= AppConstants.LIST_CACHE_TTL_MS) {
            return new Result.Success<PagedMovies>(cached.value, apiLine.id, true);
        }
        if (isCoolingDown(apiLine.id, now)) {
            return new Result.Failure<PagedMovies>(ErrorKind.TIMEOUT, "来源冷却中", null);
        }
        try {
            VodResponseDto dto = client.query(apiLine.baseUrl, keyword, null, null, 0, timeoutMs, scope);
            PagedMovies mapped = MacCmsMapper.toPagedMovies(apiLine, dto);
            listCache.put(key, new CacheEntry<PagedMovies>(mapped, now));
            clearCooldown(apiLine.id);
            return new Result.Success<PagedMovies>(mapped, apiLine.id, false);
        } catch (IOException e) {
            if (scope.isCancelled()) {
                return cancelledResult();
            }
            markCooldown(apiLine.id, now);
            ErrorKind kind = ErrorKind.fromException(e);
            if (com.tvbox.android44.BuildConfig.DEBUG) {
                android.util.Log.d("TVBOX_ERR", kind + " " + e.getClass().getSimpleName());
            }
            return new Result.Failure<PagedMovies>(kind, kind.userMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> cancelledResult() {
        return (Result<T>) Result.Cancelled.INSTANCE;
    }

    // ===== 内部 =====

    private Request submitList(final String key, final ApiLine apiLine, final int page,
                               final List<String> typeIds, final Callback<PagedMovies> cb) {
        CacheEntry<PagedMovies> cached = listCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at <= AppConstants.LIST_CACHE_TTL_MS) {
            Request noop = new Request();
            deliverMain(noop, cb, new Result.Success<PagedMovies>(cached.value, apiLine.id, true));
            return noop;
        }
        final Request request = new Request();
        submitMerged(key, request, new Worker<Result<PagedMovies>>() {
            @Override
            public Result<PagedMovies> work(CancelScope scope) {
                try {
                    PagedMovies mapped = fetchMappedPage(apiLine, page, typeIds, scope);
                    listCache.put(key, new CacheEntry<PagedMovies>(mapped, System.currentTimeMillis()));
                    clearCooldown(apiLine.id);
                    return new Result.Success<PagedMovies>(mapped, apiLine.id, false);
                } catch (IOException e) {
                    if (scope.isCancelled()) {
                        return cancelledResult();
                    }
                    markCooldown(apiLine.id, System.currentTimeMillis());
                    ErrorKind kind = ErrorKind.fromException(e);
            if (com.tvbox.android44.BuildConfig.DEBUG) {
                android.util.Log.d("TVBOX_ERR", kind + " " + e.getClass().getSimpleName());
            }
                    return new Result.Failure<PagedMovies>(kind, kind.userMessage(), e);
                }
            }
        }, cb);
        return request;
    }

    private PagedMovies fetchMappedPage(final ApiLine apiLine, final int page,
                                        final List<String> typeIds,
                                        final CancelScope scope) throws IOException {
        if (typeIds == null || typeIds.isEmpty()) {
            VodResponseDto dto = client.queryPage(apiLine.baseUrl, page, null,
                    AppConstants.LIST_CALL_TIMEOUT_MS, scope);
            return MacCmsMapper.toPagedMovies(apiLine, dto);
        }
        if (typeIds.size() == 1) {
            VodResponseDto dto = client.queryPage(apiLine.baseUrl, page, typeIds.get(0),
                    AppConstants.LIST_CALL_TIMEOUT_MS, scope);
            return MacCmsMapper.toPagedMovies(apiLine, dto);
        }

        List<Callable<PagedMovies>> jobs = new ArrayList<Callable<PagedMovies>>();
        for (final String typeId : typeIds) {
            jobs.add(new Callable<PagedMovies>() {
                @Override
                public PagedMovies call() throws Exception {
                    VodResponseDto dto = client.queryPage(apiLine.baseUrl, page, typeId,
                            AppConstants.LIST_CALL_TIMEOUT_MS, scope);
                    return MacCmsMapper.toPagedMovies(apiLine, dto);
                }
            });
        }

        List<Future<PagedMovies>> futures;
        try {
            // 聚合请求整体限时，避免某个子分类失联让首页无限等待。
            futures = categoryExecutor.invokeAll(jobs, AppConstants.LIST_CALL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("category aggregation interrupted", e);
        }

        List<PagedMovies> pages = new ArrayList<PagedMovies>();
        IOException lastFailure = null;
        for (Future<PagedMovies> future : futures) {
            if (future.isCancelled()) {
                continue;
            }
            try {
                pages.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("category aggregation interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    lastFailure = (IOException) cause;
                } else {
                    lastFailure = new IOException("category aggregation failed", cause);
                }
            }
        }
        if (pages.isEmpty()) {
            throw lastFailure == null
                    ? new IOException("category aggregation timeout") : lastFailure;
        }
        return mergePages(apiLine, page, pages);
    }

    static PagedMovies mergePages(ApiLine apiLine, int page,
                                  List<PagedMovies> pages) {
        int pageCount = 0;
        long total = 0L;
        PagedMovies merged = new PagedMovies(apiLine, page, 0, 0L);
        Set<String> movieKeys = new HashSet<String>();
        for (PagedMovies part : pages) {
            pageCount = Math.max(pageCount, part.pageCount);
            total += part.total;
            merged.categories.addAll(part.categories);
            for (Movie movie : part.movies) {
                String key = movie.apiLineId + "|" + movie.id;
                if (movieKeys.add(key)) {
                    merged.movies.add(movie);
                }
            }
        }
        PagedMovies result = new PagedMovies(apiLine, page, pageCount, total);
        result.categories.addAll(merged.categories);
        result.movies.addAll(merged.movies);
        return result;
    }

    private static String typeKey(List<String> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return "all";
        }
        StringBuilder key = new StringBuilder();
        for (String typeId : typeIds) {
            if (key.length() > 0) {
                key.append(',');
            }
            key.append(typeId == null ? "" : typeId);
        }
        return key.toString();
    }

    private Result<Movie> doFetchDetail(ApiLine apiLine, String movieId, String cacheKey,
                                        CancelScope scope) {
        try {
            VodResponseDto dto = client.query(apiLine.baseUrl, null, null, movieId, 0,
                    AppConstants.DETAIL_SUPPLEMENT_TIMEOUT_MS * 2, scope);
            // 详情响应必须解析播放串（includePlays=true）
            PagedMovies mapped = MacCmsMapper.toPagedMovies(apiLine, dto, true);
            if (mapped.movies.isEmpty()) {
                return new Result.Failure<Movie>(ErrorKind.EMPTY_BODY, "未找到影片详情", null);
            }
            Movie m = mapped.movies.get(0);
            if (m.playSources.isEmpty()) {
                // 极少数源列表形态详情缺播放串，重取一次完整详情
                VodResponseDto full = client.query(apiLine.baseUrl, null, null, movieId, 0,
                        AppConstants.DETAIL_SUPPLEMENT_TIMEOUT_MS * 2, scope);
                PagedMovies mappedFull = MacCmsMapper.toPagedMovies(apiLine, full, true);
                if (!mappedFull.movies.isEmpty()) {
                    Movie rich = mappedFull.movies.get(0);
                    for (PlaySource ps : rich.playSources) {
                        m.playSources.add(ps);
                    }
                    if (!rich.description.isEmpty()) {
                        m.description = rich.description;
                    }
                }
            }
            detailCache.put(cacheKey, new CacheEntry<Movie>(m, System.currentTimeMillis()));
            clearCooldown(apiLine.id);
            return new Result.Success<Movie>(m, apiLine.id, false);
        } catch (IOException e) {
            if (scope.isCancelled()) {
                return cancelledResult();
            }
            markCooldown(apiLine.id, System.currentTimeMillis());
            ErrorKind kind = ErrorKind.fromException(e);
            if (com.tvbox.android44.BuildConfig.DEBUG) {
                android.util.Log.d("TVBOX_ERR", kind + " " + e.getClass().getSimpleName());
            }
            return new Result.Failure<Movie>(kind, kind.userMessage(), e);
        }
    }

    private interface Worker<T> {
        T work(CancelScope scope);
    }

    /** Each caller owns a subscription; only the last cancellation aborts shared IO. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void submitMerged(final String key, final Request request,
                                  final Worker<Result<T>> worker, final Callback<T> cb) {
        synchronized (inFlight) {
            InFlight existing = inFlight.get(key);
            boolean created = existing == null;
            final InFlight current = created ? new InFlight() : existing;
            final Waiter waiter = new Waiter(request, cb);
            current.waiters.add(waiter);
            request.cancelSubscriber = new Runnable() {
                public void run() {
                    synchronized (inFlight) {
                        current.waiters.remove(waiter);
                        if (current.waiters.isEmpty() && inFlight.remove(key, current)) {
                            current.scope.cancel();
                            current.task.cancel(true);
                        }
                    }
                }
            };
            if (!created) return;
            current.task = new FutureTask<Result<?>>(new Callable<Result<?>>() {
                public Result<?> call() { return worker.work(current.scope); }
            }) {
                @Override protected void done() { dispatch(current, key); }
            };
            inFlight.put(key, current);
            executor.execute(current.task);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(InFlight entry, String key) {
        Result<?> result;
        try {
            result = entry.task.get();
        } catch (java.util.concurrent.CancellationException e) {
            result = cancelledResult();
        } catch (Exception e) {
            result = new Result.Failure<Object>(ErrorKind.OTHER, ErrorKind.OTHER.userMessage(), e);
        }
        List<Waiter> waiters;
        synchronized (inFlight) {
            inFlight.remove(key, entry);
            waiters = new ArrayList<Waiter>(entry.waiters);
            entry.waiters.clear();
        }
        for (Waiter waiter : waiters) {
            deliverMain(waiter.request, (Callback) waiter.callback, result);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void deliverMain(final Request request, final Callback<T> cb, final Result<?> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            public void run() {
                if (!request.scope.isCancelled()) cb.onResult((Result<T>) r);
            }
        });
    }
}
