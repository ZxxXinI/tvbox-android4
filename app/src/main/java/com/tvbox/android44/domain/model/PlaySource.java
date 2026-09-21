package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 一条播放线路（vod_play_from 中的一项）。 */
public class PlaySource {
    /** 稳定 ID：apiLineId + sourceName。 */
    public final String lineId;
    /** 线路名（如 ffm3u8）。 */
    public final String lineName;
    /** 来源名（如 非凡/量子）。 */
    public final String sourceName;
    public final List<PlayEpisode> episodes = new ArrayList<PlayEpisode>();

    public PlaySource(String lineId, String lineName, String sourceName) {
        this.lineId = lineId;
        this.lineName = lineName;
        this.sourceName = sourceName;
    }
}
