package com.tvbox.android44.data.local;

import com.tvbox.android44.domain.model.ApiLine;

import java.util.ArrayList;
import java.util.List;

/** 内置视频来源（只读，来自当前主工程基准快照）。 */
public final class BuiltInSources {

    private BuiltInSources() {
    }

    public static List<ApiLine> all() {
        List<ApiLine> list = new ArrayList<ApiLine>();
        list.add(new ApiLine("liangzi", "量子", "https://cj.lziapi.com/api.php/provide/vod/", true));
        list.add(new ApiLine("ruyi", "如意", "https://cj.rycjapi.com/api.php/provide/vod/", true));
        list.add(new ApiLine("360", "360", "https://360zyzz.com/api.php/provide/vod/", true));
        list.add(new ApiLine("niuniu", "牛牛", "https://api.niuniuzy.me/api.php/provide/vod/", true));
        list.add(new ApiLine("yaya", "鸭鸭", "https://cj.yayazy.net/api.php/provide/vod/", true));
        list.add(new ApiLine("hongniu", "红牛", "https://www.hongniuzy2.com/api.php/provide/vod/", true));
        list.add(new ApiLine("suoni", "索尼", "https://suoniapi.com/api.php/provide/vod/", true));
        list.add(new ApiLine("ffzy", "非凡", "http://api.ffzyapi.com/api.php/provide/vod/", true));
        return list;
    }

    public static ApiLine byId(String id) {
        for (ApiLine l : all()) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }
}
