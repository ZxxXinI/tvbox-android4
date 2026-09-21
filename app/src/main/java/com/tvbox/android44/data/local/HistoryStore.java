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
        file = new File(context.getFilesDir(), "history.json");
    }

    public synchronized List<WatchHistoryItem> load() {
        if (cache == null) {
            HistoryList wrapper = JsonIo.read(file, HistoryList.class);
            cache = wrapper != null && wrapper.items != null
                    ? wrapper.items : new ArrayList<WatchHistoryItem>();
        }
        return cache;
    }

    public synchronized void addOrUpdate(WatchHistoryItem item) {
        if (item == null) {
            return;
        }
        List<WatchHistoryItem> list = load();
        List<WatchHistoryItem> next = new ArrayList<WatchHistoryItem>();
        next.add(item);
        for (WatchHistoryItem h : list) {
            if (!h.key().equals(item.key())) {
                next.add(h);
            }
        }
        while (next.size() > AppConstants.HISTORY_MAX) {
            next.remove(next.size() - 1);
        }
        save(next);
    }

    public synchronized WatchHistoryItem find(String apiLineId, String movieId) {
        for (WatchHistoryItem h : load()) {
            if (h.apiLineId.equals(apiLineId) && h.movieId.equals(movieId)) {
                return h;
            }
        }
        return null;
    }

    public synchronized void clear() {
        save(new ArrayList<WatchHistoryItem>());
    }

    private void save(List<WatchHistoryItem> items) {
        cache = items;
        HistoryList wrapper = new HistoryList();
        wrapper.items = items;
        try {
            JsonIo.write(file, wrapper);
        } catch (Exception e) {
            // 写失败保留内存值；下次成功写入覆盖
        }
        notifyListeners();
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
