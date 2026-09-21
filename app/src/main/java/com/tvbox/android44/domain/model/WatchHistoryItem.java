package com.tvbox.android44.domain.model;

/** 观看历史条目（apiLineId + movieId 唯一）。 */
public class WatchHistoryItem {
    public String movieId;
    public String apiLineId;
    public String apiLineName;
    public String movieName;
    public String posterUrl;
    public String typeName;
    public String remarks;
    public int lineIndex;
    public String lineId;
    public String lineName;
    public int episodeIndex;
    public String episodeTitle;
    public String episodeUrl;
    public long position;
    public long duration;
    public long updatedAt;

    public WatchHistoryItem() {
    }

    public String key() {
        return apiLineId + "|" + movieId;
    }

    /** 进度百分比 0~100；时长或位置无效时返回 0。 */
    public int progressPercent() {
        if (duration <= 0 || position < 0) {
            return 0;
        }
        long p = Math.min(position, duration);
        return (int) (p * 100 / duration);
    }
}
