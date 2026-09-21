package com.tvbox.android44.data.local;

import android.content.Context;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.LineHealth;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放管家线路健康存储：保留 30 天、最多 300 条；读写时顺便清理。
 * 所有时间均为 System.currentTimeMillis()。
 */
public class HealthStore {

    private final File file;
    private Map<String, LineHealth> cache;

    public HealthStore(Context context) {
        file = new File(context.getFilesDir(), "playback_health.json");
    }

    public synchronized Map<String, LineHealth> load() {
        if (cache == null) {
            HealthMap wrapper = JsonIo.read(file, HealthMap.class);
            cache = wrapper != null && wrapper.entries != null
                    ? wrapper.entries : new HashMap<String, LineHealth>();
            cleanupIfNeeded(cache);
        }
        return cache;
    }

    public synchronized LineHealth get(String key) {
        return load().get(key);
    }

    public synchronized void recordSuccess(String key, long now) {
        Map<String, LineHealth> all = load();
        LineHealth h = ensure(all, key);
        h.lastSuccessAt = now;
        h.successCount++;
        h.cooldownUntil = 0;
        save(all);
    }

    public synchronized void recordFail(String key, long now) {
        Map<String, LineHealth> all = load();
        LineHealth h = ensure(all, key);
        h.lastFailAt = now;
        h.failCount++;
        h.cooldownUntil = now + AppConstants.LINE_RETRY_COOLDOWN_MS;
        save(all);
    }

    public synchronized void recordSlow(String key, long now) {
        Map<String, LineHealth> all = load();
        LineHealth h = ensure(all, key);
        h.lastSlowAt = now;
        h.slowCount++;
        save(all);
    }

    public synchronized void clearAll() {
        save(new HashMap<String, LineHealth>());
    }

    /** 统计摘要（设置页展示）。 */
    public synchronized List<LineHealth> statsSnapshot() {
        List<LineHealth> list = new ArrayList<LineHealth>(load().values());
        java.util.Collections.sort(list, new Comparator<LineHealth>() {
            @Override
            public int compare(LineHealth a, LineHealth b) {
                return Long.compare(Math.max(b.lastSuccessAt, b.lastFailAt),
                        Math.max(a.lastSuccessAt, a.lastFailAt));
            }
        });
        return list;
    }

    private static LineHealth ensure(Map<String, LineHealth> all, String key) {
        LineHealth h = all.get(key);
        if (h == null) {
            h = new LineHealth(key);
            all.put(key, h);
        }
        return h;
    }

    private void save(Map<String, LineHealth> entries) {
        cleanupIfNeeded(entries);
        cache = entries;
        HealthMap wrapper = new HealthMap();
        wrapper.entries = entries;
        try {
            JsonIo.write(file, wrapper);
        } catch (Exception ignored) {
            // 写失败保留内存值
        }
    }

    private static void cleanupIfNeeded(Map<String, LineHealth> entries) {
        long cutoff = System.currentTimeMillis()
                - AppConstants.HEALTH_KEEP_DAYS * 24L * 3600L * 1000L;
        boolean changed = false;
        for (Map.Entry<String, LineHealth> e : new ArrayList<Map.Entry<String, LineHealth>>(entries.entrySet())) {
            LineHealth h = e.getValue();
            long last = Math.max(Math.max(h.lastSuccessAt, h.lastFailAt), h.lastSlowAt);
            if (last < cutoff) {
                entries.remove(e.getKey());
                changed = true;
            }
        }
        if (entries.size() > AppConstants.HEALTH_MAX_ENTRIES) {
            List<LineHealth> list = new ArrayList<LineHealth>(entries.values());
            java.util.Collections.sort(list, new Comparator<LineHealth>() {
                @Override
                public int compare(LineHealth a, LineHealth b) {
                    return Long.compare(Math.max(b.lastSuccessAt, b.lastFailAt),
                            Math.max(a.lastSuccessAt, a.lastFailAt));
                }
            });
            for (int i = AppConstants.HEALTH_MAX_ENTRIES; i < list.size(); i++) {
                entries.remove(list.get(i).key);
            }
            changed = true;
        }
        if (changed) {
            // 只影响内存与下次写盘
        }
    }

    /** Gson 包装。 */
    public static final class HealthMap {
        public int version = 1;
        public Map<String, LineHealth> entries = new HashMap<String, LineHealth>();
    }
}
