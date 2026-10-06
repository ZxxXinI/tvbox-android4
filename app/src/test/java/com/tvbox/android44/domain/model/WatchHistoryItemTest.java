package com.tvbox.android44.domain.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 历史条目纯函数：key 格式与进度百分比边界。 */
public class WatchHistoryItemTest {
    @org.junit.Test
    public void veryLargePositionCannotOverflowPercentage() {
        WatchHistoryItem item = new WatchHistoryItem();
        item.position = Long.MAX_VALUE; item.duration = Long.MAX_VALUE;
        org.junit.Assert.assertEquals(100, item.progressPercent());
    }

    private static WatchHistoryItem item(long position, long duration) {
        WatchHistoryItem h = new WatchHistoryItem();
        h.apiLineId = "yaya";
        h.movieId = "123";
        h.position = position;
        h.duration = duration;
        return h;
    }

    @Test
    public void key_format() {
        assertEquals("yaya|123", item(0, 0).key());
    }

    @Test
    public void progressPercent_bounds() {
        assertEquals(50, item(500, 1000).progressPercent());
        assertEquals(0, item(0, 0).progressPercent()); // 时长无效
        assertEquals(0, item(-1, 1000).progressPercent()); // 位置无效
        assertEquals(0, item(500, -1).progressPercent());
        assertEquals(100, item(1500, 1000).progressPercent()); // 超出截断为 100
        assertEquals(29, item(29, 100).progressPercent()); // 避免先除法的浮点截断误差
    }
}
