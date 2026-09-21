package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 一部影片（来自单个来源）。 */
public class Movie {
    public String id;
    public String apiLineId;
    public String apiLineName;
    public String name;
    public String typeId;
    public String typeName;
    public String posterUrl;
    /** 备注，如“更新至40集”或“HD”。 */
    public String remarks;
    public String year;
    public String area;
    public String language;
    public String actor;
    public String director;
    public String duration;
    public String description;
    /** 该影片全部可播放线路（含其他来源补齐的线路）。 */
    public final List<PlaySource> playSources = new ArrayList<PlaySource>();
    /** 提供过该作品的其他来源 ID（供详情补线参考）。 */
    public final List<String> availableSourceIds = new ArrayList<String>();

    public Movie() {
    }

    public Movie(String id, String apiLineId, String apiLineName, String name) {
        this.id = id;
        this.apiLineId = apiLineId;
        this.apiLineName = apiLineName;
        this.name = name;
    }

    public int totalEpisodes() {
        int max = 0;
        for (PlaySource s : playSources) {
            max = Math.max(max, s.episodes.size());
        }
        return max;
    }

    /** 稳定线路 ID：apiLineId + sourceName。 */
    public static String stableLineId(String apiLineId, String sourceName) {
        return apiLineId + "|" + sourceName;
    }
}
