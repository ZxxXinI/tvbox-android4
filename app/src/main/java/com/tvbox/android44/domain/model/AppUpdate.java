package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 应用更新清单。 */
public class AppUpdate {
    public int versionCode;
    public String versionName;
    public String apkUrl;
    public String apkSha256;
    public long apkSize;
    public boolean force;
    public final List<String> changelog = new ArrayList<String>();
}
