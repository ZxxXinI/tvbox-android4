package com.tvbox.android44.data.remote;

import com.tvbox.android44.domain.model.Category;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.parser.ContentFilter;
import com.tvbox.android44.domain.parser.HtmlCleaner;
import com.tvbox.android44.domain.parser.PlayStringParser;
import com.tvbox.android44.data.remote.dto.VodClassDto;
import com.tvbox.android44.data.remote.dto.VodDto;
import com.tvbox.android44.data.remote.dto.VodResponseDto;

import java.util.ArrayList;
import java.util.List;

/**
 * MacCMS DTO → 领域模型转换：
 * trim、默认值、HTML 清理、播放串解析、内容过滤、坏条目丢弃。
 */
public final class MacCmsMapper {

    private MacCmsMapper() {
    }

    /** 分类表（ac=list 的 class 字段）；非法 id/空名条目丢弃。 */
    public static List<Category> toCategories(VodResponseDto dto) {
        List<Category> result = new ArrayList<Category>();
        if (dto.classList != null) {
            for (VodClassDto c : dto.classList) {
                Long id = VodDto.asLong(c.typeId);
                if (id == null || c.typeName == null || c.typeName.trim().isEmpty()) {
                    continue;
                }
                Long pid = VodDto.asLong(c.typePid);
                result.add(new Category(
                        String.valueOf(id),
                        pid == null || pid <= 0 ? "" : String.valueOf(pid),
                        c.typeName.trim()));
            }
        }
        return result;
    }

    public static PagedMovies toPagedMovies(ApiLine apiLine, VodResponseDto dto) {
        return toPagedMovies(apiLine, dto, false);
    }

    /** includePlays=true 用于详情响应（播放串只在详情语义下解析进模型）。 */
    public static PagedMovies toPagedMovies(ApiLine apiLine, VodResponseDto dto,
                                            boolean includePlays) {
        PagedMovies result = new PagedMovies(apiLine,
                dto.pageInt(), dto.pageCountInt(), dto.totalLong());
        if (dto.classList != null) {
            for (VodClassDto c : dto.classList) {
                Long id = VodDto.asLong(c.typeId);
                if (id == null || c.typeName == null || c.typeName.trim().isEmpty()) {
                    continue;
                }
                Long pid = VodDto.asLong(c.typePid);
                result.categories.add(new Category(
                        String.valueOf(id),
                        pid == null || pid <= 0 ? "" : String.valueOf(pid),
                        c.typeName.trim()));
            }
        }
        if (dto.list != null) {
            for (VodDto v : dto.list) {
                Movie m = toMovie(apiLine, v, includePlays);
                if (m != null) {
                    result.movies.add(m);
                }
            }
        }
        return result;
    }

    /**
     * 单条影片转换。vod_id/type_id 无法转为整数时返回 null（丢弃，不让整页失败）。
     * includePlays 控制是否解析播放串（列表页也可带播放数据）。
     */
    public static Movie toMovie(ApiLine apiLine, VodDto v, boolean includePlays) {
        Long id = VodDto.asLong(v.vodId);
        Long typeId = VodDto.asLong(v.typeId);
        if (id == null || typeId == null) {
            return null;
        }
        String name = v.vodName == null ? "" : v.vodName.trim();
        if (name.isEmpty()) {
            return null;
        }
        Movie m = new Movie(String.valueOf(id), apiLine.id, apiLine.name, name);
        m.typeId = String.valueOf(typeId);
        m.typeName = safeTrim(v.typeName);
        m.posterUrl = safeUrl(v.vodPic);
        m.remarks = safeTrim(v.vodRemarks);
        m.year = safeTrim(v.vodYear);
        m.area = HtmlCleaner.strip(v.vodArea);
        m.language = HtmlCleaner.strip(v.vodLang);
        m.actor = HtmlCleaner.strip(v.vodActor);
        m.director = HtmlCleaner.strip(v.vodDirector);
        m.duration = safeTrim(v.vodDuration);
        m.description = HtmlCleaner.strip(v.vodContent);
        if (ContentFilter.isBlocked(m)) {
            return null;
        }
        if (includePlays) {
            List<PlaySource> sources = PlayStringParser.parse(
                    apiLine.id, apiLine.name, v.vodPlayFrom, v.vodPlayUrl);
            m.playSources.addAll(sources);
        }
        return m;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 只保留合法 http/https URL。 */
    static String safeUrl(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return t;
        }
        return "";
    }
}
