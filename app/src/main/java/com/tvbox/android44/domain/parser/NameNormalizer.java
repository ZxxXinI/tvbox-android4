package com.tvbox.android44.domain.parser;

/**
 * 片名规范化与多来源去重键。
 * 去重键 = normalizedName + "|" + normalizedYear；
 * 必须保留能区分作品的数字与季信息（“第1季”与“第2季”不得合并）。
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        // 全角转半角（ASCII 区间）
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            sb.append(c);
        }
        s = sb.toString();
        // 统一括号
        s = s.replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('｛', '{').replace('｝', '}');
        // 删除普通空格、全角空格和常见分隔符
        s = s.replace(" ", "").replace("　", "")
                .replace("\t", "").replace("·", "")
                .replace("-", "").replace("—", "").replace("_", "")
                .replace(".", "").replace("、", "").replace(",", "")
                .replace("，", "").replace(":", "").replace("：", "");
        return s;
    }

    public static String normalizeYear(String year) {
        if (year == null) {
            return "";
        }
        String y = year.trim();
        // 提取 4 位年份
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(y);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    /** 多来源稳定去重键。 */
    public static String dedupeKey(String name, String year) {
        return normalize(name) + "|" + normalizeYear(year);
    }
}
