package com.tvbox.android44.common.ui;

/** 海报网格条目（首页/搜索/历史复用的 UI 模型）。 */
public class PosterEntry {
    /** 稳定 ID（焦点恢复用）。 */
    public final String key;
    public final String title;
    /** 备注：更新至 x 集 / 评分 / 进度。 */
    public final String subtitle;
    public final String posterUrl;
    /** 原始对象：Movie / DoubanHotItem / WatchHistoryItem。 */
    public final Object payload;

    public PosterEntry(String key, String title, String subtitle, String posterUrl, Object payload) {
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.posterUrl = posterUrl;
        this.payload = payload;
    }
}
