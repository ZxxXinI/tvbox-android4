package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.Movie;

import org.junit.Test;

/** 内容过滤：类型/片名关键词命中过滤；正常片名（如“演员请就位”）不得误伤。 */
public class ContentFilterTest {

    private static Movie movie(String name, String typeName, String remarks) {
        Movie m = new Movie("1", "api", "源", name);
        m.typeName = typeName;
        m.remarks = remarks;
        return m;
    }

    @Test
    public void typeKeywords_blocked() {
        assertTrue(ContentFilter.isBlocked(movie("任意", "伦理片", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "电影解说", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "新闻资讯", "")));
        assertTrue(ContentFilter.isBlocked(movie("任意", "预告花絮", "")));
    }

    @Test
    public void nameKeywords_blocked() {
        assertTrue(ContentFilter.isBlocked(movie("某某电影解说版", "动作片", "")));
        assertTrue(ContentFilter.isBlocked(movie("正常名", "剧情片", "福利合集")));
        assertTrue(ContentFilter.isBlocked(movie("某某演员资料大全", "剧情片", "")));
    }

    @Test
    public void normalTitle_notBlocked() {
        // “演员请就位”是综艺名，不是“演员资料”
        assertFalse(ContentFilter.isBlocked(movie("演员请就位", "综艺", "更新至20240815期")));
        assertFalse(ContentFilter.isBlocked(movie("流浪地球2", "科幻片", "HD")));
        assertFalse(ContentFilter.isBlocked(movie("新闻女王", "港剧", "更新至20集")));
        assertFalse(ContentFilter.isBlocked(movie("庆余年（第二季）", "古装", "")));
    }

    @Test
    public void nullMovie_blocked() {
        assertTrue(ContentFilter.isBlocked((Movie) null));
        assertFalse(ContentFilter.isBlocked(null, null, null));
    }
}
