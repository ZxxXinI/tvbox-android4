package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.PlayEpisode;
import com.tvbox.android44.domain.model.PlaySource;

import org.junit.Test;

import java.util.List;

/** 播放串解析：$$$ 对齐 / # 分集 / $ 分隔 / Markdown URL / 空值容错。 */
public class PlayStringParserTest {

    @Test
    public void multiLine_episodesSplit_byDollarAndHash() {
        List<PlaySource> sources = PlayStringParser.parse("qq", "企鹅",
                "qqdhd$$$m3u8",
                "第01集$http://a.com/1.mp4#第02集$http://a.com/2.mp4"
                        + "$$$第01集$http://b.com/1.m3u8");
        assertEquals(2, sources.size());
        assertEquals("qqdhd", sources.get(0).lineName);
        assertEquals(2, sources.get(0).episodes.size());
        assertEquals("第01集", sources.get(0).episodes.get(0).title);
        assertEquals("http://a.com/1.mp4", sources.get(0).episodes.get(0).url);
        assertEquals(1, sources.get(0).episodes.get(1).index);
        assertEquals("m3u8", sources.get(1).lineName);
        assertEquals(1, sources.get(1).episodes.size());
        assertEquals("qq|m3u8", sources.get(1).lineId);
    }

    @Test
    public void missingDollar_titleGenerated() {
        List<PlayEpisode> episodes = PlayStringParser.parseEpisodes("http://c.com/v.mp4");
        assertEquals(1, episodes.size());
        assertEquals("第1集", episodes.get(0).title);
        assertEquals("http://c.com/v.mp4", episodes.get(0).url);
    }

    @Test
    public void markdownWrappedUrl_extracted() {
        List<PlayEpisode> a = PlayStringParser.parseEpisodes(
                "第1集$(http://a.com/1.m3u8)");
        assertEquals("http://a.com/1.m3u8", a.get(0).url);
        List<PlayEpisode> b = PlayStringParser.parseEpisodes(
                "第1集$[http://a.com/1.m3u8]");
        assertEquals("http://a.com/1.m3u8", b.get(0).url);
    }

    @Test
    public void nonHttpUrl_dropped() {
        List<PlayEpisode> episodes = PlayStringParser.parseEpisodes(
                "第1集$ftp://a.com/1.mp4#第2集$rtp://x/y");
        assertTrue(episodes.isEmpty());
    }

    @Test
    public void emptySegments_skipped() {
        // 空线路、空集、空 URL 不进入模型
        List<PlaySource> sources = PlayStringParser.parse("qq", "企鹅", "a$$$",
                "第1集$http://a.com/1.mp4##第2集$http://a.com/2.mp4");
        assertEquals(1, sources.size());
        assertEquals(2, sources.get(0).episodes.size());
    }

    @Test
    public void nullInputs_emptyResult() {
        assertTrue(PlayStringParser.parse("qq", "企鹅", null, "x").isEmpty());
        assertTrue(PlayStringParser.parse("qq", "企鹅", "x", null).isEmpty());
    }

    @Test
    public void fromMoreThanUrl_extraFromIgnored() {
        List<PlaySource> sources = PlayStringParser.parse("qq", "企鹅",
                "a$$$b$$$c", "第1集$http://a.com/1.mp4");
        assertEquals(1, sources.size());
    }
}
