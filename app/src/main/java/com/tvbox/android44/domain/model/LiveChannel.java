package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 电视直播频道（同组同名合并多条线路）。 */
public class LiveChannel {
    /** 连续编号，从 1 开始（按解析顺序）。 */
    public int number;
    public final String groupName;
    public final String name;
    public final List<LiveChannelLine> lines = new ArrayList<LiveChannelLine>();

    public LiveChannel(String groupName, String name) {
        this.groupName = groupName;
        this.name = name;
    }
}
