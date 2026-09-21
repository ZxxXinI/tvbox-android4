package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 平台直播模型：站点/分类/房间/流候选（客户端只消费统一服务接口）。 */
public class PlatformLive {

    private PlatformLive() {
    }

    /** 直播平台站点。 */
    public static final class Site {
        public final String id;
        public final String name;
        public final String description;

        public Site(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    /** 平台分类（parentId 为空表示一级分类）。 */
    public static final class Category {
        public final String id;
        public final String parentId;
        public final String name;
        public final String iconUrl;

        public Category(String id, String parentId, String name, String iconUrl) {
            this.id = id;
            this.parentId = parentId;
            this.name = name;
            this.iconUrl = iconUrl;
        }
    }

    /** 直播房间。 */
    public static final class Room {
        public final String siteId;
        public final String roomId;
        public final String title;
        public final String coverUrl;
        public final String anchorName;
        public final String areaName;
        public final int viewers;
        public final boolean living;

        public Room(String siteId, String roomId, String title, String coverUrl,
                    String anchorName, String areaName, int viewers, boolean living) {
            this.siteId = siteId;
            this.roomId = roomId;
            this.title = title;
            this.coverUrl = coverUrl;
            this.anchorName = anchorName;
            this.areaName = areaName;
            this.viewers = viewers;
            this.living = living;
        }

        public String dedupeKey() {
            return siteId + "|" + roomId;
        }
    }

    /** 单个流候选（一个 CDN / 画质）。 */
    public static final class StreamCandidate {
        public final String name;
        public final String protocol;
        public final String url;
        public final String quality;

        public StreamCandidate(String name, String protocol, String url, String quality) {
            this.name = name;
            this.protocol = protocol;
            this.url = url;
            this.quality = quality;
        }
    }

    /** resolve 解析结果。 */
    public static final class Stream {
        public final boolean live;
        public final List<StreamCandidate> candidates = new ArrayList<StreamCandidate>();
        /** 白名单过滤后的请求头（User-Agent/Referer/Origin）。 */
        public final java.util.Map<String, String> headers = new java.util.HashMap<String, String>();

        public Stream(boolean live) {
            this.live = live;
        }
    }
}
