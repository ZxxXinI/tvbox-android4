package com.tvbox.android44.domain.model;

/** 影片分类。type_id 无父级时 parentId 为空字符串。 */
public class Category {
    public final String id;
    public final String parentId;
    public final String name;

    public Category(String id, String parentId, String name) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
    }
}
