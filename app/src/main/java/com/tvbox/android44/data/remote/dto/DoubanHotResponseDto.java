package com.tvbox.android44.data.remote.dto;

import java.util.List;

/** 豆瓣最近热播响应 DTO。 */
public class DoubanHotResponseDto {
    public String category;
    public String type;
    public Long total;
    public List<ItemDto> items;

    public long totalLong() {
        return total == null ? 0 : total;
    }

    public static final class ItemDto {
        public String id;
        public String title;
        public PicDto pic;
        public RatingDto rating;
        public String uri;
        public String card_subtitle;
        public String episodes_info;
        public boolean is_new;
    }

    public static final class PicDto {
        public String large;
        public String normal;
    }

    public static final class RatingDto {
        public Float value;
        public Integer count;
    }
}
