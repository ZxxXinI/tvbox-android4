package com.tvbox.android44.domain.model;

/** 一集（标题 + 可播放 URL）。 */
public class PlayEpisode {
    public final int index;
    public final String title;
    public final String url;

    public PlayEpisode(int index, String title, String url) {
        this.index = index;
        this.title = title;
        this.url = url;
    }
}
