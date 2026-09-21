package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.AiRecommendItem;

import org.junit.Test;

import java.util.List;

/** AI 推荐解析：代码块容错 / 最多 10 条 / 缺字段丢弃 / 关键词回退片名。 */
public class AiRecommendParserTest {

    @Test
    public void plainJson_parsed() {
        List<AiRecommendItem> items = AiRecommendParser.parse(
                "{\"recommendations\":[{\"title\":\"流浪地球\",\"searchKeyword\":\"流浪地球2\","
                        + "\"reason\":\"硬核科幻\"},{\"title\":\"满江红\"}]}");
        assertEquals(2, items.size());
        assertEquals("流浪地球", items.get(0).title);
        assertEquals("流浪地球2", items.get(0).searchKeyword);
        assertEquals("硬核科幻", items.get(0).reason);
        // 缺 searchKeyword 时回退片名
        assertEquals("满江红", items.get(1).searchKeyword);
    }

    @Test
    public void fencedCodeBlock_extracted() {
        String content = "好的，以下是推荐：\n```json\n"
                + "{\"recommendations\":[{\"title\":\"三体\",\"searchKeyword\":\"三体\"}]}\n"
                + "```\n希望你喜欢。";
        List<AiRecommendItem> items = AiRecommendParser.parse(content);
        assertEquals(1, items.size());
        assertEquals("三体", items.get(0).title);
    }

    @Test
    public void surroundingProse_extracted() {
        List<AiRecommendItem> items = AiRecommendParser.parse(
                "前缀说明 {\"recommendations\":[{\"title\":\"庆余年\"}]} 后缀说明");
        assertEquals(1, items.size());
        assertEquals("庆余年", items.get(0).title);
    }

    @Test
    public void moreThanTen_capped() {
        StringBuilder json = new StringBuilder("{\"recommendations\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"title\":\"片名").append(i).append("\"}");
        }
        json.append("]}");
        assertEquals(10, AiRecommendParser.parse(json.toString()).size());
    }

    @Test
    public void missingTitle_dropped() {
        List<AiRecommendItem> items = AiRecommendParser.parse(
                "{\"recommendations\":[{\"reason\":\"无名\"},{\"title\":\"有名\"},"
                        + "{\"title\":\"  \"}]}");
        assertEquals(1, items.size());
        assertEquals("有名", items.get(0).title);
    }

    @Test
    public void invalidContent_emptyNotCrash() {
        assertTrue(AiRecommendParser.parse(null).isEmpty());
        assertTrue(AiRecommendParser.parse("").isEmpty());
        assertTrue(AiRecommendParser.parse("完全不是 JSON 的内容").isEmpty());
        assertTrue(AiRecommendParser.parse("{\"没有列表字段\":1}").isEmpty());
        assertTrue(AiRecommendParser.parse("{\"recommendations\": \"不是数组\"}").isEmpty());
    }

    @Test
    public void alternativeArrayKeys_supported() {
        assertEquals(1, AiRecommendParser.parse("{\"items\":[{\"title\":\"A\"}]}").size());
        assertEquals(1, AiRecommendParser.parse("[{\"title\":\"B\"}]").size());
    }
}
