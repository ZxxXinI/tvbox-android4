package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.LiveChannelGroup;

import org.junit.Test;

import java.util.List;

/** IPTV 文本解析：BOM/CRLF/#genre#/$截断/同组同名合并/编号/无分组。 */
public class IptvTextParserTest {

    @Test
    public void basicParse_groupsAndChannels() {
        String text = "央视频道,#genre#\r\n"
                + "CCTV1,http://a.com/1.m3u8\r\n"
                + "CCTV2,http://a.com/2.m3u8\r\n"
                + "卫视频道,#genre#\r\n"
                + "湖南卫视,http://b.com/hn.m3u8\r\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(2, groups.size());
        assertEquals("央视频道", groups.get(0).name);
        assertEquals(2, groups.get(0).channels.size());
        assertEquals("CCTV1", groups.get(0).channels.get(0).name);
        assertEquals(1, groups.get(0).channels.get(0).number);
        assertEquals("CCTV2", groups.get(0).channels.get(1).name);
        assertEquals(2, groups.get(0).channels.get(1).number);
        assertEquals("湖南卫视", groups.get(1).channels.get(0).name);
        assertEquals(3, groups.get(1).channels.get(0).number);
    }

    @Test
    public void bomAndCrlf_tolerated() {
        String text = "\uFEFF央视频道,#genre#\r\nCCTV1,http://a.com/1.m3u8\r\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(1, groups.size());
        assertEquals("央视频道", groups.get(0).name);
        assertEquals("CCTV1", groups.get(0).channels.get(0).name);
    }

    @Test
    public void docFormat_urlThenMeta_metaAsLineName() {
        // 文档 08 §5/§6 主格式：URL 在前，$ 后为线路名元数据
        String text = "g,#genre#\n"
                + "CCTV1,http://a.com/1.m3u8$LR•IPV4•29『高清线路』\n"
                + "CCTV2,http://a.com/2.m3u8\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(2, groups.get(0).channels.size());
        assertEquals("LR•IPV4•29『高清线路』", groups.get(0).channels.get(0).lines.get(0).name);
        assertEquals("http://a.com/1.m3u8", groups.get(0).channels.get(0).lines.get(0).url);
        // 无元数据时默认线路名
        assertEquals("线路1", groups.get(0).channels.get(1).lines.get(0).name);
    }

    @Test
    public void variantFormat_metaThenUrl_urlTruncatedAtDollar() {
        // 兼容变体：$ 前为线路名、$ 后为 URL；URL 内再遇 $ 截断
        String text = "g,#genre#\n"
                + "CCTV1,50fps高清$http://a.com/1.m3u8$额外垃圾\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(1, groups.get(0).channels.size());
        assertEquals("50fps高清", groups.get(0).channels.get(0).lines.get(0).name);
        assertEquals("http://a.com/1.m3u8", groups.get(0).channels.get(0).lines.get(0).url);
    }

    @Test
    public void sameGroupSameName_mergedLinesUrlDeduped() {
        String text = "g,#genre#\n"
                + "CCTV1,线路1$http://a.com/1.m3u8\n"
                + "CCTV1,线路2$http://a.com/1.m3u8\n" // 同 URL：去重
                + "CCTV1,线路2$http://a.com/2.m3u8\n"; // 不同 URL：追加
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).channels.size());
        assertEquals(2, groups.get(0).channels.get(0).lines.size());
        assertEquals(1, groups.get(0).channels.get(0).number);
    }

    @Test
    public void channelWithoutGroup_ignored() {
        String text = "CCTV1,http://a.com/1.m3u8\n";
        assertTrue(IptvTextParser.parse(text).isEmpty());
    }

    @Test
    public void malformedLines_skipped() {
        String text = "g,#genre#\n"
                + "\n"
                + "无逗号行\n"
                + ",http://a.com/x\n"
                + "CCTV1,rtmp://not-http\n"
                + "CCTV1,http://a.com/1.m3u8\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).channels.size());
    }

    @Test
    public void leadingNumberAndHtml_cleaned() {
        String text = "g,#genre#\n"
                + "1.CCTV1,http://a.com/1.m3u8\n"
                + "<b>CCTV2</b>,http://a.com/2.m3u8\n";
        List<LiveChannelGroup> groups = IptvTextParser.parse(text);
        assertEquals("CCTV1", groups.get(0).channels.get(0).name);
        assertEquals("CCTV2", groups.get(0).channels.get(1).name);
    }

    @Test
    public void nullAndEmpty_safe() {
        assertTrue(IptvTextParser.parse(null).isEmpty());
        assertTrue(IptvTextParser.parse("").isEmpty());
    }
}
