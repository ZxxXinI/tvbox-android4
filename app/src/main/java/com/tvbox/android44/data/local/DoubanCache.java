package com.tvbox.android44.data.local;

import android.content.Context;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.DoubanHotItem;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 豆瓣热播缓存：成功缓存 20 分钟；失败状态缓存 6 分钟；
 * 失败时优先返回仍可用的旧缓存。
 */
public class DoubanCache {

    public static final class Entry {
        public List<DoubanHotItem> items;
        public long cachedAt;
        public boolean ok;
        public long total;
    }

    private final File file;
    private Map<String, Entry> cache;

    public DoubanCache(Context context) {
        file = new File(context.getFilesDir(), "douban_cache.json");
    }

    public static String keyOf(String category, int start) {
        return category + "|" + start;
    }

    private synchronized Map<String, Entry> load() {
        if (cache == null) {
            CacheMap wrapper = JsonIo.read(file, CacheMap.class);
            cache = wrapper != null && wrapper.entries != null
                    ? wrapper.entries : new HashMap<String, Entry>();
        }
        return cache;
    }

    /** 仍在有效期内的缓存（含失败标记缓存）。 */
    public synchronized Entry getFresh(String key, long now) {
        Entry e = load().get(key);
        if (e == null) {
            return null;
        }
        long ttl = e.ok ? AppConstants.DOUBAN_SUCCESS_TTL_MS : AppConstants.DOUBAN_FAILURE_TTL_MS;
        if (now - e.cachedAt > ttl) {
            return null;
        }
        return e;
    }

    /** 已过期但仍存在的旧缓存（失败时回退用）。 */
    public synchronized Entry getStale(String key) {
        return load().get(key);
    }

    public synchronized void put(String key, List<DoubanHotItem> items, boolean ok, long total, long now) {
        Map<String, Entry> all = load();
        Entry e = new Entry();
        e.items = items;
        e.ok = ok;
        e.total = total;
        e.cachedAt = now;
        all.put(key, e);
        persist(all);
    }

    private void persist(Map<String, Entry> entries) {
        cache = entries;
        CacheMap wrapper = new CacheMap();
        wrapper.entries = entries;
        try {
            JsonIo.write(file, wrapper);
        } catch (Exception ignored) {
        }
    }

    public static final class CacheMap {
        public int version = 1;
        public Map<String, Entry> entries = new HashMap<String, Entry>();
    }
}
