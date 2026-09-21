package com.tvbox.android44.common;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集中管理的线程池：网络 / 磁盘 / 主线程调度。
 * 所有页面任务必须通过这里执行，禁止在页面内自建线程池。
 */
public class AppExecutors {

    private final ExecutorService network;
    private final ExecutorService disk;
    private final ScheduledExecutorService scheduler;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AppExecutors() {
        network = Executors.newFixedThreadPool(4, namedFactory("tvbox-net"));
        disk = Executors.newFixedThreadPool(2, namedFactory("tvbox-disk"));
        scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("tvbox-sched"));
    }

    public ExecutorService network() {
        return network;
    }

    public ExecutorService disk() {
        return disk;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public void main(Runnable r) {
        main.post(r);
    }

    public void mainDelayed(Runnable r, long delayMillis) {
        main.postDelayed(r, delayMillis);
    }

    public void removeMainCallbacks(Runnable r) {
        main.removeCallbacks(r);
    }

    public boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public void shutdown() {
        network.shutdownNow();
        disk.shutdownNow();
        scheduler.shutdownNow();
    }

    private static ThreadFactory namedFactory(final String base) {
        final AtomicInteger idx = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, base + "-" + idx.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };
    }
}
