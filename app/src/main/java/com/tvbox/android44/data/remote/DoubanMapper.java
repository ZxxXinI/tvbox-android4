package com.tvbox.android44.data.remote;

import com.tvbox.android44.data.remote.dto.DoubanHotResponseDto;
import com.tvbox.android44.domain.model.DoubanHotItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 豆瓣 DTO → 领域模型。 */
public final class DoubanMapper {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    private DoubanMapper() {
    }

    public static List<DoubanHotItem> toItems(DoubanHotResponseDto dto) {
        List<DoubanHotItem> items = new ArrayList<DoubanHotItem>();
        if (dto == null || dto.items == null) {
            return items;
        }
        for (DoubanHotResponseDto.ItemDto it : dto.items) {
            if (it == null || it.title == null || it.title.trim().isEmpty()) {
                continue;
            }
            String poster = "";
            if (it.pic != null) {
                poster = it.pic.normal != null ? it.pic.normal
                        : (it.pic.large != null ? it.pic.large : "");
            }
            float rating = it.rating != null && it.rating.value != null ? it.rating.value : 0f;
            String subtitle = it.card_subtitle == null ? "" : it.card_subtitle;
            String year = "";
            Matcher m = YEAR.matcher(subtitle);
            if (m.find()) {
                year = m.group();
            }
            String remarks = it.episodes_info == null ? "" : it.episodes_info.trim();
            items.add(new DoubanHotItem(
                    it.id == null ? "" : it.id,
                    it.title.trim(), poster, rating, year, subtitle, remarks));
        }
        return items;
    }
}
