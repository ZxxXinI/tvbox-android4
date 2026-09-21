package com.tvbox.android44.domain.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tvbox.android44.domain.model.AiRecommendItem;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 推荐结果解析：容忍 JSON 外包裹代码块或少量说明；
 * 最终必须解析为标题和条目列表；最多保留 10 条合法结果；
 * 缺片名或搜索关键词的条目丢弃。
 */
public final class AiRecommendParser {

    private static final int MAX_ITEMS = 10;
    private static final Gson GSON = new Gson();

    private AiRecommendParser() {
    }

    public static List<AiRecommendItem> parse(String content) {
        if (content == null) {
            return new ArrayList<AiRecommendItem>();
        }
        String json = extractJson(content);
        List<AiRecommendItem> items = new ArrayList<AiRecommendItem>();
        if (json == null) {
            return items;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray array = null;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                array = firstArray(obj, "recommendations", "items", "list", "results", "data", "movies", "films");
                if (array == null) {
                    return items;
                }
            }
            if (array == null) {
                return items;
            }
            for (JsonElement el : array) {
                if (!el.isJsonObject() || items.size() >= MAX_ITEMS) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String title = pickString(o, "title", "name", "片名", "名称");
                String keyword = pickString(o, "searchKeyword", "search_keyword", "keyword", "搜索词", "关键词");
                String reason = pickString(o, "reason", "description", "推荐理由", "理由", "简介");
                if (title == null || title.trim().isEmpty()) {
                    continue;
                }
                title = title.trim();
                if (keyword == null || keyword.trim().isEmpty()) {
                    keyword = title;
                }
                keyword = keyword.trim();
                items.add(new AiRecommendItem(title, keyword, reason == null ? "" : reason.trim()));
            }
        } catch (Exception ignored) {
            // 解析失败交给上层显示“AI 返回格式无法识别”
        }
        return items;
    }

    /** 提取 ```json 代码块或首个 { / [ 到末个 } / ] 的 JSON 文本。 */
    static String extractJson(String content) {
        String s = content.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 去代码块围栏
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        int start;
        if (objStart < 0) start = arrStart;
        else if (arrStart < 0) start = objStart;
        else start = Math.min(objStart, arrStart);
        if (start < 0) {
            return null;
        }
        char open = s.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = s.lastIndexOf(close);
        if (end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    private static JsonArray firstArray(JsonObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                return obj.getAsJsonArray(k);
            }
        }
        return null;
    }

    private static String pickString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return null;
    }
}
