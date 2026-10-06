package com.tvbox.android44.app;

import android.app.Application;
import android.content.ComponentCallbacks2;

import androidx.multidex.MultiDex;

import com.bumptech.glide.Glide;
import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.common.AppExecutors;
import com.tvbox.android44.data.local.HealthStore;
import com.tvbox.android44.data.local.HistoryStore;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.HttpClients;
import com.tvbox.android44.data.repository.DoubanRepository;
import com.tvbox.android44.data.repository.DetailSupplement;
import com.tvbox.android44.data.repository.LiveRepository;
import com.tvbox.android44.data.repository.MovieRepository;
import com.tvbox.android44.data.repository.MultiSourceSearch;
import com.tvbox.android44.data.repository.PlatformLiveRepository;
import com.tvbox.android44.data.repository.RecommendRepository;
import com.tvbox.android44.data.repository.UpdateRepository;
import com.tvbox.android44.domain.update.StartupUpdatePolicy;

import android.os.StrictMode;

/**
 * 应用入口：Multidex 安装、全局服务组装与内存压力回调。
 * 注意：不持有任何 Activity 引用；缓存清理由 Application 级回调触发。
 */
public class TvBoxApp extends Application {

    private static TvBoxApp instance;

    private AppExecutors executors;
    private SettingsRepository settings;
    private HistoryStore history;
    private HealthStore health;
    private MovieRepository movies;
    private MultiSourceSearch search;
    private DetailSupplement supplement;
    private DoubanRepository douban;
    private LiveRepository live;
    private PlatformLiveRepository platformLive;
    private RecommendRepository recommend;
    private UpdateRepository updates;
    private final StartupUpdatePolicy startupUpdates = new StartupUpdatePolicy();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        // 必须在 attachBaseContext 安装 Multidex：API<21 上 ContentProvider
        // （FileProvider）先于 onCreate 实例化，晚装会导致 ClassNotFoundException
        androidx.multidex.MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (BuildConfig.DEBUG) {
            StrictMode.ThreadPolicy tp = new StrictMode.ThreadPolicy.Builder()
                    .detectAll().penaltyLog().build();
            StrictMode.VmPolicy vp = new StrictMode.VmPolicy.Builder()
                    .detectAll().penaltyLog().build();
            StrictMode.setThreadPolicy(tp);
            StrictMode.setVmPolicy(vp);
        }
        executors = new AppExecutors();
        settings = new SettingsRepository(this);
        HttpClients.init(executors);

        history = new HistoryStore(this);
        executors.disk().execute(new Runnable() {
            public void run() { history.load(); }
        });
        health = new HealthStore(this, executors.disk(), new java.util.concurrent.Executor() {
            @Override public void execute(Runnable command) { executors.main(command); }
        });
        movies = new MovieRepository(executors.network(), executors.sourceRequests());
        search = new MultiSourceSearch(executors.network(), movies, settings);
        supplement = new DetailSupplement(executors.network(), movies, settings);
        douban = new DoubanRepository(executors.network(), this);
        live = new LiveRepository(executors.network(), settings);
        platformLive = new PlatformLiveRepository(executors.network(), settings);
        recommend = new RecommendRepository(executors.network(), settings);
        updates = new UpdateRepository(executors.network(), this);
    }

    public static TvBoxApp get() {
        return instance;
    }

    public AppExecutors executors() {
        return executors;
    }

    public SettingsRepository settings() {
        return settings;
    }

    public HistoryStore history() {
        return history;
    }

    public HealthStore health() {
        return health;
    }

    public MovieRepository movies() {
        return movies;
    }

    public MultiSourceSearch search() {
        return search;
    }

    public DetailSupplement supplement() {
        return supplement;
    }

    public DoubanRepository douban() {
        return douban;
    }

    public LiveRepository live() {
        return live;
    }

    public PlatformLiveRepository platformLive() {
        return platformLive;
    }

    public RecommendRepository recommend() {
        return recommend;
    }

    public UpdateRepository updates() {
        return updates;
    }

    public StartupUpdatePolicy startupUpdates() { return startupUpdates; }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            Glide.get(this).trimMemory(level);
            movies.onTrimMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Glide.get(this).trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        movies.onTrimMemory();
    }
}
