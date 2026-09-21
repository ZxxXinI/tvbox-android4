package com.tvbox.android44.domain.model;

/** AI 推荐条目：点击后按 searchKeyword 走普通多来源搜索。 */
public class AiRecommendItem {
    public final String title;
    public final String searchKeyword;
    public final String reason;

    public AiRecommendItem(String title, String searchKeyword, String reason) {
        this.title = title;
        this.searchKeyword = searchKeyword;
        this.reason = reason;
    }
}
