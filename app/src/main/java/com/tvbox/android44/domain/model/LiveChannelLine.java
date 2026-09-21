package com.tvbox.android44.domain.model;

/** 直播频道的一条线路。 */
public class LiveChannelLine {
    /** 线路名（元数据部分，不含 URL）。 */
    public final String name;
    public final String url;

    public LiveChannelLine(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
