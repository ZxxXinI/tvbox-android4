package com.tvbox.android44.domain.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 清理：去除标签并替换常见实体（vod_content/演员/导演等字段）。
 * 纯 Java 实现，可在 JVM 单测运行。
 */
public final class HtmlCleaner {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern NAMED_ENTITY = Pattern.compile("&[a-zA-Z][a-zA-Z0-9]{1,10};");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private HtmlCleaner() {
    }

    public static String strip(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        // <p> / <br> 转空白，避免词粘连
        s = s.replaceAll("(?i)<br\\s*/?>", " ");
        s = s.replaceAll("(?i)</p>", " ");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = WS.matcher(s).replaceAll(" ").trim();
        return s;
    }

    private static String decodeEntities(String s) {
        StringBuffer sb = new StringBuffer();
        Matcher m = NAMED_ENTITY.matcher(s);
        while (m.find()) {
            String rep = named(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();

        sb = new StringBuffer();
        m = NUM_ENTITY.matcher(s);
        while (m.find()) {
            String rep = numeric(m.group(1).length() > 0, m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String named(String entity) {
        if (entity.equalsIgnoreCase("&amp;")) return "&";
        if (entity.equalsIgnoreCase("&lt;")) return "<";
        if (entity.equalsIgnoreCase("&gt;")) return ">";
        if (entity.equalsIgnoreCase("&quot;")) return "\"";
        if (entity.equalsIgnoreCase("&apos;")) return "'";
        if (entity.equalsIgnoreCase("&nbsp;")) return " ";
        if (entity.equalsIgnoreCase("&mdash;")) return "—";
        if (entity.equalsIgnoreCase("&hellip;")) return "…";
        if (entity.equalsIgnoreCase("&ldquo;")) return "“";
        if (entity.equalsIgnoreCase("&rdquo;")) return "”";
        return entity;
    }

    private static String numeric(boolean hex, String digits) {
        try {
            int code = hex
                    ? Integer.parseInt(digits, 16)
                    : Integer.parseInt(digits);
            if (code <= 0 || code > 0x10FFFF) {
                return "";
            }
            return new String(Character.toChars(code));
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
