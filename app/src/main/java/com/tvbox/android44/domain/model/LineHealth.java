package com.tvbox.android44.domain.model;

/** 线路健康记录（播放管家）。key = apiLineId|movieId|lineId。 */
public class LineHealth {
    public String key;
    public long lastSuccessAt;
    public long lastFailAt;
    public long lastSlowAt;
    public int successCount;
    public int failCount;
    public int slowCount;
    public long cooldownUntil;

    public LineHealth() {
    }

    public LineHealth(String key) {
        this.key = key;
    }

    public LineHealth(LineHealth other) {
        key = other.key;
        lastSuccessAt = other.lastSuccessAt; lastFailAt = other.lastFailAt;
        lastSlowAt = other.lastSlowAt; cooldownUntil = other.cooldownUntil;
        successCount = other.successCount; failCount = other.failCount; slowCount = other.slowCount;
    }

    /** 长期成功率（0~1，无记录时 -1）。 */
    public double successRate() {
        long total = (long) successCount + failCount;
        if (total <= 0) {
            return -1;
        }
        return (double) successCount / (double) total;
    }
}
