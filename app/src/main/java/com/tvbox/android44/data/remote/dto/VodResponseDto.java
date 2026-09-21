package com.tvbox.android44.data.remote.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** MacCMS V10 JSON VOD 响应 DTO（容忍 null、数字字符串与字段缺失）。 */
public class VodResponseDto {
    public Double code;
    public String msg;
    public String page;
    public Double page1;
    public String pagecount;
    public Double pagecount1;
    public String limit;
    public String total;
    public Double total1;
    public List<VodDto> list;
    @SerializedName("class")
    public List<VodClassDto> classList;

    /** page 字段兼容数字或字符串。 */
    public int pageInt() {
        if (page1 != null) return page1.intValue();
        return parseIntSafe(page);
    }

    public int pageCountInt() {
        if (pagecount1 != null) return pagecount1.intValue();
        return parseIntSafe(pagecount);
    }

    public long totalLong() {
        if (total1 != null) return total1.longValue();
        try {
            return total == null ? 0 : Long.parseLong(total.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int parseIntSafe(String v) {
        try {
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
