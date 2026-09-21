package com.tvbox.android44.domain.model;

/** 视频来源线路（MacCMS 接口）。 */
public class ApiLine {
    public final String id;
    public final String name;
    public final String baseUrl;
    /** true 表示内置来源（不可删除）；false 表示用户自定义。 */
    public final boolean builtIn;

    public ApiLine(String id, String name, String baseUrl, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.builtIn = builtIn;
    }
}
