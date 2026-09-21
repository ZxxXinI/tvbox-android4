package com.tvbox.android44.data.repository;

import android.content.Context;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.DoubanCache;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.DoubanClient;
import com.tvbox.android44.data.remote.DoubanMapper;
import com.tvbox.android44.domain.model.DoubanHotItem;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 豆瓣热播仓库：成功缓存 20 分钟、失败状态缓存 6 分钟；
 * 失败优先返回仍可用的旧缓存。
 */
public class DoubanRepository {

    public interface Callback {
        void onResult(Result<List<DoubanHotItem>> result);
    }

    private final ExecutorService executor;
    private final DoubanClient client = new DoubanClient();
    private final DoubanCache cache;

    public DoubanRepository(ExecutorService executor, Context context) {
        this.executor = executor;
        this.cache = new DoubanCache(context);
    }

    public RequestHandle hot(String category, int page, final Callback cb) {
        int start = (page - 1) * AppConstants.DOUBAN_PAGE_SIZE;
        final String key = DoubanCache.keyOf(category, start);
        long now = System.currentTimeMillis();
        DoubanCache.Entry fresh = cache.getFresh(key, now);
        if (fresh != null && fresh.ok) {
            deliver(cb, new Result.Success<List<DoubanHotItem>>(fresh.items, "douban", true));
            return RequestHandle.NOOP;
        }
        final CancelScope scope = new CancelScope();
        final FutureTask<Result<List<DoubanHotItem>>> task =
                new FutureTask<Result<List<DoubanHotItem>>>(
                        new java.util.concurrent.Callable<Result<List<DoubanHotItem>>>() {
                            @Override
                            public Result<List<DoubanHotItem>> call() {
                                try {
                                    DoubanClient c = client;
                                    List<DoubanHotItem> items = DoubanMapper.toItems(
                                            c.recentHot(category, start, AppConstants.DOUBAN_PAGE_SIZE, scope));
                                    if (scope.isCancelled()) {
                                        return cancelled();
                                    }
                                    if (items.isEmpty()) {
                                        cache.put(key, items, false, 0, System.currentTimeMillis());
                                        return staleOrError(key, "EMPTY_BODY");
                                    }
                                    cache.put(key, items, true, items.size(), System.currentTimeMillis());
                                    return new Result.Success<List<DoubanHotItem>>(items, "douban", false);
                                } catch (Exception e) {
                                    if (scope.isCancelled()) {
                                        return cancelled();
                                    }
                                    cache.put(key, java.util.Collections.<DoubanHotItem>emptyList(),
                                            false, 0, System.currentTimeMillis());
                                    return staleOrError(key, classify(e));
                                }
                            }

                            private Result<List<DoubanHotItem>> staleOrError(String key, String kind) {
                                DoubanCache.Entry stale = cache.getStale(key);
                                if (stale != null && stale.items != null && !stale.items.isEmpty()) {
                                    return new Result.Success<List<DoubanHotItem>>(
                                            stale.items, "douban", true);
                                }
                                com.tvbox.android44.common.ErrorKind ek =
                                        com.tvbox.android44.common.ErrorKind.OTHER;
                                try {
                                    ek = com.tvbox.android44.common.ErrorKind.valueOf(kind);
                                } catch (IllegalArgumentException ignored) {
                                }
                                return new Result.Failure<List<DoubanHotItem>>(ek, ek.userMessage(), null);
                            }

                            private String classify(Exception e) {
                                return com.tvbox.android44.common.ErrorKind.fromException(e).name();
                            }
                        });
        executor.submit(task);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final Result<List<DoubanHotItem>> r = task.get();
                    TvBoxApp.get().executors().main(new Runnable() {
                        @Override
                        public void run() {
                            cb.onResult(r);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
        return new RequestHandle(scope, task);
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> cancelled() {
        return (Result<T>) Result.Cancelled.INSTANCE;
    }

    private static void deliver(final Callback cb, final Result<List<DoubanHotItem>> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }

    public static final class RequestHandle {
        public static final RequestHandle NOOP = new RequestHandle(null, null);
        private final CancelScope scope;
        private final FutureTask<?> task;

        RequestHandle(CancelScope scope, FutureTask<?> task) {
            this.scope = scope;
            this.task = task;
        }

        public void cancel() {
            if (scope != null) {
                scope.cancel();
            }
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}
