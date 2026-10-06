package com.tvbox.android44.domain.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.Movie;

import org.junit.Test;

/** 多来源增量合并：主来源优先、稳定顺序、增量追加、元数据补齐。 */
public class SearchResultMergerTest {
    @Test
    public void lateMainSourceReplacesCardWithoutChangingOrder() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        merged.add(movie("other", "otherSource", "影片", "2025"));
        merged.add(movie("second", "otherSource", "其他影片", "2025"));
        merged.add(movie("main", "mainSource", "影片", "2025"), true);
        assertEquals("main", merged.movies.get(0).id);
        assertEquals("second", merged.movies.get(1).id);
        assertTrue(merged.movies.get(0).availableSourceIds.contains("otherSource"));
        assertTrue(merged.movies.get(0).availableSourceIds.contains("mainSource"));
    }

    private static Movie movie(String id, String sourceId, String name, String year) {
        Movie m = new Movie(id, sourceId, "源" + sourceId, name);
        m.year = year;
        return m;
    }

    @Test
    public void firstOccurrenceWins_stableOrder() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        merged.add(movie("a1", "src1", "流浪地球", "2023"));
        merged.add(movie("b1", "src2", "满江红", "2023"));
        merged.add(movie("a2", "src1", "流浪地球", "2023")); // 同作品重复
        merged.add(movie("c1", "src3", "三体", "2023"));

        assertEquals(3, merged.size());
        assertEquals("a1", merged.movies.get(0).id);
        assertEquals("b1", merged.movies.get(1).id);
        assertEquals("c1", merged.movies.get(2).id);
    }

    @Test
    public void availableSourceIds_recorded() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        merged.add(movie("a1", "src1", "流浪地球", "2023"));
        merged.add(movie("b1", "src2", "流浪地球", "2023"));
        // “流浪地球2”与“流浪地球”数字不同，是两部作品
        merged.add(movie("c1", "src3", "流浪地球 2", "2023"));

        assertEquals(2, merged.size());
        Movie first = merged.movies.get(0);
        assertEquals("a1", first.id);
        assertTrue(first.availableSourceIds.contains("src1"));
        assertTrue(first.availableSourceIds.contains("src2"));
        assertTrue(merged.containsKey("流浪地球 2", "2023"));
    }

    @Test
    public void missingMetadata_filledFromDuplicate() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        Movie main = movie("a1", "src1", "流浪地球", "2023");
        merged.add(main);
        Movie other = movie("b1", "src2", "流浪地球", "2023");
        other.posterUrl = "http://p/2.jpg";
        other.actor = "吴京";
        merged.add(other);

        assertEquals("http://p/2.jpg", main.posterUrl);
        assertEquals("吴京", main.actor);
    }

    @Test
    public void existingMetadata_notOverwritten() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        Movie main = movie("a1", "src1", "流浪地球", "2023");
        main.posterUrl = "http://p/1.jpg";
        merged.add(main);
        Movie other = movie("b1", "src2", "流浪地球", "2023");
        other.posterUrl = "http://p/2.jpg";
        merged.add(other);

        assertEquals("http://p/1.jpg", main.posterUrl);
    }

    @Test
    public void nullAndWhitespaceSafe() {
        SearchResultMerger.Merged merged = SearchResultMerger.newMerger();
        merged.add(null);
        assertEquals(0, merged.size());
    }
}
