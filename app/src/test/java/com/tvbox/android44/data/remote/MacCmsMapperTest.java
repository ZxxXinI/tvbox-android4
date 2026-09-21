package com.tvbox.android44.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.data.remote.dto.VodClassDto;
import com.tvbox.android44.data.remote.dto.VodDto;
import com.tvbox.android44.data.remote.dto.VodResponseDto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * MacCMS DTO → 领域模型映射。
 * 样本结构取自 2026-08-29 对鸭鸭/非凡真实响应的探测：list[].vod_id 为数字、
 * class[] 为数组、page/pagecount/total 数字或字符串不定。
 */
public class MacCmsMapperTest {

    private static final ApiLine API = new ApiLine("yaya", "鸭鸭", "https://yaya.example/", true);

    private static VodDto vod(Object vodId, String name, String typeName, String remarks) {
        VodDto v = new VodDto();
        v.vodId = vodId;
        v.typeId = 1;
        v.typeName = typeName;
        v.vodName = name;
        v.vodRemarks = remarks;
        return v;
    }

    @Test
    public void realSampleShape_numericIds_mapped() {
        // 鸭鸭实测：vod_id 为 JSON 数字（映射后为 Integer/Long），class 为数组
        VodResponseDto dto = new VodResponseDto();
        dto.page = "1";
        dto.pagecount = "9";
        dto.total = "176";
        VodClassDto c = new VodClassDto();
        c.typeId = 6;
        c.typePid = 0;
        c.typeName = "电影";
        dto.classList = new ArrayList<VodClassDto>(Arrays.asList(c));
        dto.list = new ArrayList<VodDto>(Arrays.asList(
                vod(123456, "流浪地球2", "科幻片", "HD")));

        PagedMovies paged = MacCmsMapper.toPagedMovies(API, dto);
        assertEquals(1, paged.page);
        assertEquals(9, paged.pageCount);
        assertEquals(176L, paged.total);
        assertEquals(1, paged.categories.size());
        assertEquals("6", paged.categories.get(0).id);
        assertEquals("", paged.categories.get(0).parentId);
        assertEquals(1, paged.movies.size());
        assertEquals("123456", paged.movies.get(0).id);
    }

    @Test
    public void stringNumericIds_tolerated_badRowsDropped() {
        VodResponseDto dto = new VodResponseDto();
        // 非凡实测存在 vod_id 为字符串的形态；非法 id 行丢弃、不整页失败
        dto.list = new ArrayList<VodDto>(Arrays.asList(
                vod("999", "字符串ID片", "剧情片", ""),
                vod("abc", "非法ID片", "剧情片", ""),
                vod(null, "空ID片", "剧情片", ""),
                vod(1, "", "剧情片", ""),
                vod(2, "被内容过滤", "伦理片", "")));
        PagedMovies paged = MacCmsMapper.toPagedMovies(API, dto);
        assertEquals(1, paged.movies.size());
        assertEquals("字符串ID片", paged.movies.get(0).name);
    }

    @Test
    public void htmlFieldsCleaned_picSanitized() {
        VodDto v = vod(10, "清洁测试", "剧情片", "");
        v.vodContent = "<p>剧情<b>简介</b>&nbsp;第二段</p>";
        v.vodActor = "张三&nbsp;李四";
        v.vodPic = "javascript:alert(1)"; // 非法协议置空
        Movie m = MacCmsMapper.toMovie(API, v, false);
        // 开标签 <p> 直接去除不产生空格；</p>/<br> 转空白
        assertEquals("剧情简介 第二段", m.description);
        assertEquals("张三 李四", m.actor);
        assertEquals("", m.posterUrl);
    }

    @Test
    public void includePlays_parsesPlayString() {
        VodDto v = vod(10, "多线路片", "剧情片", "更新至10集");
        v.vodPlayFrom = "qqdhd$$$m3u8";
        v.vodPlayUrl = "第01集$http://a.com/1.mp4#第02集$http://a.com/2.mp4"
                + "$$$第01集$http://b.com/1.m3u8";
        Movie m = MacCmsMapper.toMovie(API, v, true);
        assertEquals(2, m.playSources.size());
        assertEquals(2, m.playSources.get(0).episodes.size());
        assertEquals("yaya|m3u8", m.playSources.get(1).lineId);
    }

    @Test
    public void toPagedMovies_playSourcesOnlyWithIncludePlays() {
        // 回归：详情路径曾因列表语义丢失播放串（toPagedMovies 硬编码 includePlays=false）
        VodDto v = vod(10, "多线路片", "剧情片", "HD");
        v.vodPlayFrom = "qqdhd";
        v.vodPlayUrl = "第01集$http://a.com/1.mp4";
        VodResponseDto dto = new VodResponseDto();
        dto.list = new ArrayList<VodDto>(Arrays.asList(v));

        assertEquals(0, MacCmsMapper.toPagedMovies(API, dto).movies.get(0).playSources.size());
        assertEquals(1, MacCmsMapper.toPagedMovies(API, dto, true)
                .movies.get(0).playSources.size());
    }

    @Test
    public void pageFields_numberOrString_bothWork() {
        VodResponseDto a = new VodResponseDto();
        a.page1 = 2.0;
        a.pagecount1 = 7.0;
        a.total1 = 65.0;
        PagedMovies pa = MacCmsMapper.toPagedMovies(API, a);
        assertEquals(2, pa.page);
        assertEquals(7, pa.pageCount);
        assertEquals(65L, pa.total);

        VodResponseDto b = new VodResponseDto();
        b.page = "3";
        b.pagecount = "abc"; // 非法字符串回退 0
        PagedMovies pb = MacCmsMapper.toPagedMovies(API, b);
        assertEquals(3, pb.page);
        assertEquals(0, pb.pageCount);
        assertEquals(0L, pb.total);
    }

    @Test
    public void nullLists_safe() {
        VodResponseDto dto = new VodResponseDto();
        PagedMovies paged = MacCmsMapper.toPagedMovies(API, dto);
        assertEquals(0, paged.movies.size());
        assertEquals(0, paged.categories.size());
    }

    @Test
    public void toCategories_parentChildAndBadRows() {
        // 样本取自 2026-08-30 鸭鸭 ac=list 实测：44 个分类，type_pid 表达父子关系
        VodClassDto movie = new VodClassDto();
        movie.typeId = 1;
        movie.typePid = 0;
        movie.typeName = "电影片";
        VodClassDto action = new VodClassDto();
        action.typeId = 6;
        action.typePid = 1;
        action.typeName = "动作片";
        VodClassDto bad = new VodClassDto();
        bad.typeId = "abc";
        bad.typePid = 0;
        bad.typeName = "坏数据";
        VodClassDto noName = new VodClassDto();
        noName.typeId = 9;
        noName.typePid = 0;
        noName.typeName = "  ";
        VodResponseDto dto = new VodResponseDto();
        dto.classList = new ArrayList<VodClassDto>(
                Arrays.asList(movie, action, bad, noName));

        java.util.List<com.tvbox.android44.domain.model.Category> categories =
                MacCmsMapper.toCategories(dto);
        assertEquals(2, categories.size());
        assertEquals("1", categories.get(0).id);
        assertEquals("", categories.get(0).parentId);
        assertEquals("电影片", categories.get(0).name);
        assertEquals("6", categories.get(1).id);
        assertEquals("1", categories.get(1).parentId);
    }
}
