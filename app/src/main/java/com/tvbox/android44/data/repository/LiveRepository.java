package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.IptvClient;
import com.tvbox.android44.domain.model.LiveChannelGroup;
import com.tvbox.android44.domain.parser.IptvTextParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** 电视直播仓库：下载 + 解析 + 会话级内存缓存。 */
public class LiveRepository {

    public interface Callback {
        void onResult(Result<List<LiveChannelGroup>> result);
    }

    private static final long SESSION_CACHE_TTL_MS = 10 * 60 * 1000L;

    private final ExecutorService executor;
    private final IptvClient client = new IptvClient();
    private final SettingsRepository settings;
    private final Map<String, Cached> sessionCache = new HashMap<String, Cached>();

    private static final class Cached {
        final List<LiveChannelGroup> groups;
        final long at;

        Cached(List<LiveChannelGroup> groups, long at) {
            this.groups = groups;
            this.at = at;
        }
    }

    public LiveRepository(ExecutorService executor, SettingsRepository settings) {
        this.executor = executor;
        this.settings = settings;
    }

    public void load(final boolean forceRefresh, final Callback cb) {
        final String url = settings.iptvUrl();
        final CancelScope scope = new CancelScope();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (!forceRefresh) {
                    Cached c;
                    synchronized (sessionCache) {
                        c = sessionCache.get(url);
                    }
                    if (c != null && now - c.at < SESSION_CACHE_TTL_MS) {
                        deliver(cb, new Result.Success<List<LiveChannelGroup>>(c.groups, "iptv", true));
                        return;
                    }
                }
                try {
                    String text = client.getText(url, scope);
                    final List<LiveChannelGroup> groups = IptvTextParser.parse(text);
                    if (scope.isCancelled()) {
                        return;
                    }
                    if (groups.isEmpty()) {
                        deliver(cb, new Result.Failure<List<LiveChannelGroup>>(
                                ErrorKind.PARSE, "直播源解析结果为空", null));
                        return;
                    }
                    synchronized (sessionCache) {
                        sessionCache.put(url, new Cached(groups, System.currentTimeMillis()));
                    }
                    deliver(cb, new Result.Success<List<LiveChannelGroup>>(groups, "iptv", false));
                } catch (Exception e) {
                    if (scope.isCancelled()) {
                        return;
                    }
                    ErrorKind kind = ErrorKind.fromException(e);
                    deliver(cb, new Result.Failure<List<LiveChannelGroup>>(kind, kind.userMessage(), e));
                }
            }
        });
    }

    private static void deliver(final Callback cb, final Result<List<LiveChannelGroup>> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }
}
