package com.tvbox.android44.data.remote.dto;

import com.google.gson.annotations.SerializedName;

/** 分类 DTO（列表响应的 class 字段）。 */
public class VodClassDto {
    @SerializedName("type_id")
    public Object typeId;
    @SerializedName("type_pid")
    public Object typePid;
    @SerializedName("type_name")
    public String typeName;
}
