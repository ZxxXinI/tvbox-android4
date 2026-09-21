package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.PlatformLiveClient;
import com.tvbox.android44.domain.model.PlatformLive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 平台直播仓库：平台/分类短期缓存，房间按页缓存；
 * 同一房间 resolve 进行中合并。
 */
public class PlatformLiveRepository {

    public interface Callback<T> {
        void onResult(Result<T> result);
    }

    private static final long CATALOG_TTL_MS = 3 * 60 * 1000L;

    private final ExecutorService executor;
    private final SettingsRepository settings;
    private final Map<String, Object> catalogCache = new HashMap<String, Object>();
    private final Map<String, Long> catalogCacheAt = new HashMap<String, Long>();
    /** 进行中的 resolve 合并。 */
    private final ConcurrentHashMap<String, Object> resolving = new ConcurrentHashMap<String, Object>();

    public PlatformLiveRepository(ExecutorService executor, SettingsRepository settings) {
        this.executor = executor;
        this.settings = settings;
    }

    private PlatformLiveClient client() {
        return new PlatformLiveClient(settings.platformLiveUrl());
    }

    public void sites(final Callback<List<PlatformLive.Site>> cb) {
        runCached("sites", new Worker<List<PlatformLive.Site>>() {
            @Override
            public Result<List<PlatformLive.Site>> work(PlatformLiveClient client, CancelScope scope) {
                try {
                    return new Result.Success<List<PlatformLive.Site>>(client.sites(scope));
                } catch (Exception e) {
                    return failure(e, scope);
                }
            }
        }, cb);
    }

    public void categories(final String site, final String parentId,
                           final Callback<List<PlatformLive.Category>> cb) {
        runCached("cats|" + site + "|" + (parentId == null ? "" : parentId),
                new Worker<List<PlatformLive.Category>>() {
                    @Override
                    public Result<List<PlatformLive.Category>> work(PlatformLiveClient client, CancelScope scope) {
                        try {
                            return new Result.Success<List<PlatformLive.Category>>(
                                    client.categories(site, parentId, scope));
                        } catch (Exception e) {
                            return failure(e, scope);
                        }
                    }
                }, cb);
    }

    public void rooms(final String site, final String categoryId, final int page,
                      final Callback<PlatformLiveClient.RoomsPage> cb) {
        runCached("rooms|" + site + "|" + categoryId + "|" + page,
                new Worker<PlatformLiveClient.RoomsPage>() {
                    @Override
                    public Result<PlatformLiveClient.RoomsPage> work(PlatformLiveClient client, CancelScope scope) {
                        try {
                            return new Result.Success<PlatformLiveClient.RoomsPage>(
                                    client.rooms(site, categoryId, page, scope));
                        } catch (Exception e) {
                            return failure(e, scope);
                        }
                    }
                }, cb);
    }

    /** resolve 不缓存；进行中请求合并。 */
    public void resolve(final String site, final String roomId, final boolean refresh,
                        final Callback<PlatformLive.Stream> cb) {
        final String key = site + "|" + roomId + "|" + refresh;
        final CancelScope scope = new CancelScope();
        Object existing = resolving.putIfAbsent(key, new Object());
        if (existing != null) {
            deliver(cb, new Result.Failure<PlatformLive.Stream>(
                    ErrorKind.OTHER, "正在解析中，请稍候", null));
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                Result<PlatformLive.Stream> r;
                try {
                    r = new Result.Success<PlatformLive.Stream>(
                            client().resolve(site, roomId, refresh, scope));
                } catch (Exception e) {
                    r = failure(e, scope);
                }
                resolving.remove(key);
                deliver(cb, r);
            }
        });
    }

    // ===== 内部 =====

    private interface Worker<T> {
        Result<T> work(PlatformLiveClient client, CancelScope scope);
    }

    @SuppressWarnings("unchecked")
    private <T> void runCached(final String key, final Worker<T> worker, final Callback<T> cb) {
        synchronized (catalogCache) {
            Object cached = catalogCache.get(key);
            Long at = catalogCacheAt.get(key);
            if (cached != null && at != null
                    && System.currentTimeMillis() - at < CATALOG_TTL_MS) {
                deliver(cb, new Result.Success<T>((T) cached, "platform", true));
                return;
            }
        }
        final CancelScope scope = new CancelScope();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                Result<T> r = worker.work(client(), scope);
                if (r.isSuccess()) {
                    synchronized (catalogCache) {
                        catalogCache.put(key, r.data());
                        catalogCacheAt.put(key, System.currentTimeMillis());
                    }
                }
                deliver(cb, r);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> failure(Exception e, CancelScope scope) {
        if (scope.isCancelled()) {
            return (Result<T>) Result.Cancelled.INSTANCE;
        }
        ErrorKind kind = ErrorKind.fromException(e);
        if (com.tvbox.android44.BuildConfig.DEBUG) {
            android.util.Log.d("TVBOX_PLATFORM", "request failure kind=" + kind
                    + " exception=" + e.getClass().getName() + " message=" + e.getMessage());
        }
        return new Result.Failure<T>(kind, kind.userMessage(), e);
    }

    private static <T> void deliver(final Callback<T> cb, final Result<T> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }
}
