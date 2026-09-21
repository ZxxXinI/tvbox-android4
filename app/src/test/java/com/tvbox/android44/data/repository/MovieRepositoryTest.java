package com.tvbox.android44.data.repository;

import static org.junit.Assert.assertEquals;

import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;

import org.junit.Test;

import java.util.Arrays;

public class MovieRepositoryTest {

    private static final ApiLine API = new ApiLine("test", "测试", "https://example.com/", true);

    @Test
    public void mergePages_sumsCountsAndDeduplicatesMovies() {
        PagedMovies action = new PagedMovies(API, 1, 3, 60);
        action.movies.add(movie("1", "动作片一"));
        action.movies.add(movie("2", "动作片二"));

        PagedMovies comedy = new PagedMovies(API, 1, 2, 40);
        comedy.movies.add(movie("2", "重复影片"));
        comedy.movies.add(movie("3", "喜剧片一"));

        PagedMovies merged = MovieRepository.mergePages(API, 1,
                Arrays.asList(action, comedy));

        assertEquals(3, merged.pageCount);
        assertEquals(100L, merged.total);
        assertEquals(3, merged.movies.size());
        assertEquals("动作片二", merged.movies.get(1).name);
    }

    private static Movie movie(String id, String name) {
        return new Movie(id, API.id, API.name, name);
    }
}
