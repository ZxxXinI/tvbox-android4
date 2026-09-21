package com.tvbox.android44.data.remote.dto;

import com.google.gson.annotations.SerializedName;

/** 单部影片 DTO。 */
public class VodDto {
    @SerializedName("vod_id")
    public Object vodId;
    @SerializedName("type_id")
    public Object typeId;
    @SerializedName("type_id_1")
    public Object typeId1;
    @SerializedName("type_name")
    public String typeName;
    @SerializedName("vod_name")
    public String vodName;
    @SerializedName("vod_pic")
    public String vodPic;
    @SerializedName("vod_remarks")
    public String vodRemarks;
    @SerializedName("vod_year")
    public String vodYear;
    @SerializedName("vod_area")
    public String vodArea;
    @SerializedName("vod_lang")
    public String vodLang;
    @SerializedName("vod_actor")
    public String vodActor;
    @SerializedName("vod_director")
    public String vodDirector;
    @SerializedName("vod_duration")
    public String vodDuration;
    @SerializedName("vod_content")
    public String vodContent;
    @SerializedName("vod_play_from")
    public String vodPlayFrom;
    @SerializedName("vod_play_url")
    public String vodPlayUrl;
    @SerializedName("vod_time")
    public String vodTime;
    @SerializedName("vod_score")
    public String vodScore;
    @SerializedName("vod_en_from")
    public String vodEnFrom;

    /** 宽松数字转换：JSON 值可能是数字或字符串；无法转换返回 null（调用方丢弃该条）。 */
    public static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
