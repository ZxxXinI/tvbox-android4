package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 片名规范化与去重键：全角/分隔符归一；不同季不得合并。 */
public class NameNormalizerTest {

    @Test
    public void normalize_stripsSeparatorsAndCase() {
        assertEquals("复仇者联盟4终局之战",
                NameNormalizer.normalize("复仇者联盟 4：终局之战"));
        assertEquals("abc123", NameNormalizer.normalize("ＡＢＣ－１２３"));
        assertEquals("庆余年", NameNormalizer.normalize("庆　余年·"));
    }

    @Test
    public void normalizeYear_extractsFourDigits() {
        assertEquals("2023", NameNormalizer.normalizeYear("2023"));
        assertEquals("2019", NameNormalizer.normalizeYear("内地/2019"));
        assertEquals("", NameNormalizer.normalizeYear("未知"));
        assertEquals("", NameNormalizer.normalizeYear(null));
    }

    @Test
    public void differentSeasons_notMerged() {
        assertNotEquals(NameNormalizer.dedupeKey("庆余年 第1季", "2022"),
                NameNormalizer.dedupeKey("庆余年 第2季", "2023"));
    }

    @Test
    public void sameTitleDifferentFormatting_sameKey() {
        // 空格/间隔号/全角标点差异统一后应同键（括号内文字不剥离，属现行为）
        assertEquals(NameNormalizer.dedupeKey("流浪地球2", "2023"),
                NameNormalizer.dedupeKey("流浪 地球·2", "2023"));
        assertEquals(NameNormalizer.dedupeKey("庆余年-第二季", "2023"),
                NameNormalizer.dedupeKey("庆余年_第二季", "2023"));
    }

    @Test
    public void digitsPreserved_notConfused() {
        // 数字是区分作品的信息，不得被规范化吃掉
        assertFalse(NameNormalizer.normalize("叶问1").equals(NameNormalizer.normalize("叶问2")));
        assertTrue(NameNormalizer.normalize("1024").contains("1024"));
    }
}
