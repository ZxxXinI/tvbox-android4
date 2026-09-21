package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** HTML 清理：去标签、实体解码、空白折叠。 */
public class HtmlCleanerTest {

    @Test
    public void nullAndEmpty_safe() {
        assertEquals("", HtmlCleaner.strip(null));
        assertEquals("", HtmlCleaner.strip(""));
    }

    @Test
    public void tagsRemoved_brAndPBecomeSpace() {
        assertEquals("第一段 第二段", HtmlCleaner.strip("<p>第一段</p><br/>第二段"));
        // 行内标签不产生空格，避免“词粘连”方向反了也保持原文
        assertEquals("加粗文本", HtmlCleaner.strip("<b>加粗</b>文本"));
    }

    @Test
    public void namedEntities_decoded() {
        assertEquals("A&B <x> \"q\"", HtmlCleaner.strip("A&amp;B &lt;x&gt; &quot;q&quot;"));
        // 尾部空白会被 trim，用夹在中间的 nbsp 验证
        assertEquals("a b", HtmlCleaner.strip("a&nbsp;b"));
        assertEquals("—", HtmlCleaner.strip("&mdash;"));
    }

    @Test
    public void numericEntities_decoded() {
        assertEquals("中", HtmlCleaner.strip("&#20013;"));
        assertEquals("中", HtmlCleaner.strip("&#x4E2D;"));
        // 非法码点安全清空
        assertEquals("", HtmlCleaner.strip("&#0;"));
    }

    @Test
    public void whitespaceCollapsed() {
        assertEquals("a b c", HtmlCleaner.strip("a\t\n b   c"));
    }

    @Test
    public void unknownEntityKeptVerbatim() {
        assertEquals("&weird;", HtmlCleaner.strip("&weird;"));
    }
}
