package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.tvbox.android44.domain.model.AppUpdate;

import org.junit.Test;

/** OTA 清单解析：BOM / 关键字段缺失报错 / 未知字段忽略。 */
public class OtaManifestParserTest {

    private static final String FULL = "{\"versionCode\":10306,\"versionName\":\"1.3.6\","
            + "\"apkUrl\":\"https://e.com/TVBox.apk\",\"apkSha256\":\"abc\","
            + "\"apkSize\":12345678,\"force\":false,"
            + "\"changelog\":[\"修复\",\"优化\"],\"unknownField\":123}";

    @Test
    public void fullManifest_parsed() throws Exception {
        AppUpdate u = OtaManifestParser.parse(FULL);
        assertEquals(10306, u.versionCode);
        assertEquals("1.3.6", u.versionName);
        assertEquals("https://e.com/TVBox.apk", u.apkUrl);
        assertEquals("abc", u.apkSha256);
        assertEquals(12345678L, u.apkSize);
        assertTrue(!u.force);
        assertEquals(2, u.changelog.size());
    }

    @Test
    public void bomStripped() throws Exception {
        AppUpdate u = OtaManifestParser.parse("\uFEFF" + FULL);
        assertEquals(10306, u.versionCode);
    }

    @Test
    public void missingRequiredFields_throw() {
        assertThrows("缺少 versionCode", "{\"versionName\":\"1.0\",\"apkUrl\":\"https://e/a.apk\"}");
        assertThrows("缺少 versionName", "{\"versionCode\":2,\"apkUrl\":\"https://e/a.apk\"}");
        assertThrows("缺少 apkUrl", "{\"versionCode\":2,\"versionName\":\"1.0\"}");
        assertThrows("apkUrl 非法", "{\"versionCode\":2,\"versionName\":\"1.0\",\"apkUrl\":\"ftp://x\"}");
    }

    @Test
    public void invalidJson_throw() {
        assertThrows("清单不是合法 JSON", "not json {");
        assertThrows("清单为空", null);
    }

    @Test
    public void optionalFields_defaulted() throws Exception {
        AppUpdate u = OtaManifestParser.parse(
                "{\"versionCode\":2,\"versionName\":\"1.0\",\"apkUrl\":\"https://e/a.apk\"}");
        assertEquals(0, u.apkSize);
        assertEquals("", u.apkSha256);
        assertTrue(u.changelog.isEmpty());
        assertTrue(!u.force);
    }

    private static void assertThrows(String expectedMessagePart, String json) {
        try {
            OtaManifestParser.parse(json);
            fail("应抛出 ManifestException: " + expectedMessagePart);
        } catch (OtaManifestParser.ManifestException e) {
            assertTrue("实际消息: " + e.getMessage(), e.getMessage().contains(expectedMessagePart));
        }
    }
}
