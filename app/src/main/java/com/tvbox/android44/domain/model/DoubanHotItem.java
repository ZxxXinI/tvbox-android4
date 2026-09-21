package com.tvbox.android44.domain.model;

/** 豆瓣热播条目（展示用，不是播放资源）。 */
public class DoubanHotItem {
    public final String id;
    public final String title;
    public final String posterUrl;
    public final float rating;
    /** 年份（从 card_subtitle 解析，可能为空）。 */
    public final String year;
    /** 副标题（如 “2026 / 中国大陆 / 剧情 古装”）。 */
    public final String subtitle;
    /** 备注（如“更新至 40 集”）。 */
    public final String remarks;

    public DoubanHotItem(String id, String title, String posterUrl, float rating,
                         String year, String subtitle, String remarks) {
        this.id = id;
        this.title = title;
        this.posterUrl = posterUrl;
        this.rating = rating;
        this.year = year;
        this.subtitle = subtitle;
        this.remarks = remarks;
    }
}
