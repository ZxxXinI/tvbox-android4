package com.tvbox.android44.data.local;

import android.content.Context;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.WatchHistoryItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 观看历史：同一 apiLineId+movieId 覆盖并置顶；最多 100 条；按更新时间倒序；
 * 损坏 JSON 回退空列表。
 */
public class HistoryStore {

    public interface Listener {
        void onHistoryChanged();
    }

    private final File file;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private List<WatchHistoryItem> cache;

    public HistoryStore(Context context) {
        this(new File(context.getFilesDir(), "history.json"));
    }

    HistoryStore(File file) { this.file = file; }

    public synchronized List<WatchHistoryItem> load() {
        if (cache == null) {
            HistoryList wrapper = JsonIo.read(file, HistoryList.class);
            cache = normalize(wrapper != null ? wrapper.items : null);
        }
        return snapshot(cache);
    }

    public synchronized boolean addOrUpdate(WatchHistoryItem item) {
        if (!valid(item)) {
            return false;
        }
        List<WatchHistoryItem> list = load();
        List<WatchHistoryItem> next = new ArrayList<WatchHistoryItem>();
        next.add(new WatchHistoryItem(item));
        for (WatchHistoryItem h : list) {
            if (!h.key().equals(item.key())) {
                next.add(h);
            }
        }
        while (next.size() > AppConstants.HISTORY_MAX) {
            next.remove(next.size() - 1);
        }
        return save(normalize(next));
    }

    public synchronized WatchHistoryItem find(String apiLineId, String movieId) {
        for (WatchHistoryItem h : load()) {
            if (h.apiLineId.equals(apiLineId) && h.movieId.equals(movieId)) {
                return new WatchHistoryItem(h);
            }
        }
        return null;
    }

    public synchronized boolean clear() {
        return save(new ArrayList<WatchHistoryItem>());
    }

    private boolean save(List<WatchHistoryItem> items) {
        HistoryList wrapper = new HistoryList();
        wrapper.items = items;
        try {
            JsonIo.write(file, wrapper);
        } catch (Exception e) {
            return false;
        }
        cache = items;
        notifyListeners();
        return true;
    }

    private static boolean valid(WatchHistoryItem item) {
        return item != null && item.apiLineId != null && !item.apiLineId.isEmpty()
                && item.movieId != null && !item.movieId.isEmpty();
    }

    private static List<WatchHistoryItem> snapshot(List<WatchHistoryItem> items) {
        List<WatchHistoryItem> result = new ArrayList<WatchHistoryItem>();
        for (WatchHistoryItem item : items) result.add(new WatchHistoryItem(item));
        return result;
    }

    private static List<WatchHistoryItem> normalize(List<WatchHistoryItem> items) {
        List<WatchHistoryItem> result = new ArrayList<WatchHistoryItem>();
        if (items != null) for (WatchHistoryItem item : items) {
            if (valid(item)) result.add(new WatchHistoryItem(item));
        }
        java.util.Collections.sort(result, new java.util.Comparator<WatchHistoryItem>() {
            public int compare(WatchHistoryItem a, WatchHistoryItem b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        java.util.Set<String> seen = new java.util.HashSet<String>();
        List<WatchHistoryItem> unique = new ArrayList<WatchHistoryItem>();
        for (WatchHistoryItem item : result) {
            if (seen.add(item.key())) unique.add(item);
            if (unique.size() >= AppConstants.HISTORY_MAX) break;
        }
        return unique;
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) {
                listeners.add(l);
            }
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyListeners() {
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<Listener>(listeners);
        }
        for (Listener l : copy) {
            l.onHistoryChanged();
        }
    }

    /** Gson 包装（保留扩展字段）。 */
    public static final class HistoryList {
        public int version = 1;
        public List<WatchHistoryItem> items = new ArrayList<WatchHistoryItem>();
    }
}
