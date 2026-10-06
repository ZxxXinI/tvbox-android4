package com.tvbox.android44.data.local;

import android.content.Context;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.LineHealth;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Ordered background persistence; playback only reads independent memory snapshots. */
public class HealthStore {
    public interface Callback { void onComplete(boolean success); }
    public interface StatsCallback { void onStats(List<LineHealth> stats); }

    private final File file;
    private final Executor disk;
    private final Executor callbacks;
    private volatile Map<String, LineHealth> snapshot = Collections.emptyMap();

    public HealthStore(Context context, Executor disk, Executor callbacks) {
        this(new File(context.getFilesDir(), "playback_health.json"), disk, callbacks);
    }

    HealthStore(File file, Executor disk, Executor callbacks) {
        this.file = file;
        this.disk = disk;
        this.callbacks = callbacks;
        // Initialization and subsequent writes use the same ordered executor.
        disk.execute(new Runnable() {
            @Override public void run() {
                HealthMap stored = JsonIo.read(HealthStore.this.file, HealthMap.class);
                Map<String, LineHealth> entries = copy(stored == null ? null : stored.entries);
                cleanup(entries, System.currentTimeMillis());
                snapshot = entries;
            }
        });
    }

    /** Never loads a file or waits for the disk worker. */
    public Map<String, LineHealth> load() { return copy(snapshot); }

    public LineHealth get(String key) {
        LineHealth value = snapshot.get(key);
        return value == null ? null : new LineHealth(value);
    }

    public void recordSuccess(String key, long now) { record(key, now, 0); }
    public void recordFail(String key, long now) { record(key, now, 1); }
    public void recordSlow(String key, long now) { record(key, now, 2); }

    private void record(final String key, final long now, final int event) {
        if (key == null || key.trim().isEmpty()) return;
        disk.execute(new Runnable() {
            @Override public void run() {
                Map<String, LineHealth> next = copy(snapshot);
                LineHealth value = next.get(key);
                if (value == null) { value = new LineHealth(key); next.put(key, value); }
                if (event == 0) {
                    value.lastSuccessAt = Math.max(0, now);
                    value.successCount = increment(value.successCount);
                    value.cooldownUntil = 0;
                } else if (event == 1) {
                    value.lastFailAt = Math.max(0, now);
                    value.failCount = increment(value.failCount);
                    value.cooldownUntil = now > Long.MAX_VALUE - AppConstants.LINE_RETRY_COOLDOWN_MS
                            ? Long.MAX_VALUE : Math.max(0, now) + AppConstants.LINE_RETRY_COOLDOWN_MS;
                } else {
                    value.lastSlowAt = Math.max(0, now);
                    value.slowCount = increment(value.slowCount);
                }
                if (!save(next)) System.err.println("TVBOX_HEALTH: 线路统计保存失败");
            }
        });
    }

    /** Reports durable success; a failed clear preserves the saved snapshot. */
    public void clearAll(final Callback callback) {
        disk.execute(new Runnable() {
            @Override public void run() {
                final boolean success = save(new HashMap<String, LineHealth>());
                if (callback != null) callbacks.execute(new Runnable() {
                    @Override public void run() { callback.onComplete(success); }
                });
            }
        });
    }

    public List<LineHealth> statsSnapshot() {
        List<LineHealth> values = new ArrayList<LineHealth>(copy(snapshot).values());
        Collections.sort(values, RECENT_FIRST);
        return values;
    }

    /** Queues behind initialization and mutations without blocking the UI. */
    public void readStats(final StatsCallback callback) {
        disk.execute(new Runnable() {
            @Override public void run() {
                final List<LineHealth> values = statsSnapshot();
                callbacks.execute(new Runnable() {
                    @Override public void run() { callback.onStats(values); }
                });
            }
        });
    }

    private boolean save(Map<String, LineHealth> entries) {
        cleanup(entries, System.currentTimeMillis());
        HealthMap wrapper = new HealthMap();
        wrapper.entries = entries;
        try {
            JsonIo.write(file, wrapper);
            snapshot = entries;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static Map<String, LineHealth> copy(Map<String, LineHealth> source) {
        Map<String, LineHealth> result = new HashMap<String, LineHealth>();
        if (source == null) return result;
        for (Map.Entry<String, LineHealth> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null) continue;
            LineHealth value = new LineHealth(entry.getValue());
            value.key = entry.getKey();
            value.successCount = Math.max(0, value.successCount);
            value.failCount = Math.max(0, value.failCount);
            value.slowCount = Math.max(0, value.slowCount);
            value.lastSuccessAt = Math.max(0, value.lastSuccessAt);
            value.lastFailAt = Math.max(0, value.lastFailAt);
            value.lastSlowAt = Math.max(0, value.lastSlowAt);
            value.cooldownUntil = Math.max(0, value.cooldownUntil);
            result.put(value.key, value);
        }
        return result;
    }

    private static final Comparator<LineHealth> RECENT_FIRST = new Comparator<LineHealth>() {
        @Override public int compare(LineHealth a, LineHealth b) {
            int order = Long.compare(lastEvent(b), lastEvent(a));
            return order != 0 ? order : a.key.compareTo(b.key);
        }
    };

    private static long lastEvent(LineHealth value) {
        return Math.max(Math.max(value.lastSuccessAt, value.lastFailAt), value.lastSlowAt);
    }

    private static void cleanup(Map<String, LineHealth> entries, long now) {
        long cutoff = now - AppConstants.HEALTH_KEEP_DAYS * 24L * 3600L * 1000L;
        for (LineHealth value : new ArrayList<LineHealth>(entries.values())) {
            if (lastEvent(value) < cutoff) entries.remove(value.key);
        }
        if (entries.size() > AppConstants.HEALTH_MAX_ENTRIES) {
            List<LineHealth> values = new ArrayList<LineHealth>(entries.values());
            Collections.sort(values, RECENT_FIRST);
            for (int i = AppConstants.HEALTH_MAX_ENTRIES; i < values.size(); i++) entries.remove(values.get(i).key);
        }
    }

    public static final class HealthMap {
        public int version = 1;
        public Map<String, LineHealth> entries = new HashMap<String, LineHealth>();
    }
}
