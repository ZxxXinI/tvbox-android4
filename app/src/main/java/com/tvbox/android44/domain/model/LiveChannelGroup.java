package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 电视直播频道分组。 */
public class LiveChannelGroup {
    public final String name;
    public final List<LiveChannel> channels = new ArrayList<LiveChannel>();

    public LiveChannelGroup(String name) {
        this.name = name;
    }
}
