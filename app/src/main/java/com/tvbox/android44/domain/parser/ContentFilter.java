package com.tvbox.android44.domain.parser;

import com.tvbox.android44.domain.model.Movie;

/**
 * 内容过滤：过滤伦理、电影解说、演员资料、新闻资讯等当前产品不需要内容。
 * 过滤基于标准化后的片名/类型/备注字段，作用于首页、搜索、历史和 AI 搜索结果。
 */
public final class ContentFilter {

    /** 类型字段关键词（typeName 命中即过滤；也用于首页分类名过滤）。 */
    private static final String[] TYPE_KEYWORDS = {
            "伦理", "解说", "资讯", "新闻", "福利", "写真", "广告", "宣传", "预告花絮", "演员"
    };

    /** 片名/备注关键词（需整词级命中，避免误伤正常片名）。 */
    private static final String[] NAME_KEYWORDS = {
            "伦理", "解说版", "电影解说", "新闻资讯", "演员资料", "福利"
    };

    private ContentFilter() {
    }

    public static boolean isBlocked(String name, String typeName, String remarks) {
        String type = typeName == null ? "" : typeName;
        for (String k : TYPE_KEYWORDS) {
            if (type.contains(k)) {
                return true;
            }
        }
        String n = NameNormalizer.normalize(name);
        String r = NameNormalizer.normalize(remarks);
        for (String k : NAME_KEYWORDS) {
            String kn = NameNormalizer.normalize(k);
            if (n.contains(kn) || r.contains(kn)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlocked(Movie movie) {
        if (movie == null) {
            return true;
        }
        return isBlocked(movie.name, movie.typeName, movie.remarks);
    }
}
