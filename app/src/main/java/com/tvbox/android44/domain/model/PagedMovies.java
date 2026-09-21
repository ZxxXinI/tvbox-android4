package com.tvbox.android44.domain.model;

import java.util.ArrayList;
import java.util.List;

/** 分页影片列表响应。 */
public class PagedMovies {
    public final int page;
    public final int pageCount;
    public final long total;
    public final ApiLine apiLine;
    public final List<Category> categories = new ArrayList<Category>();
    public final List<Movie> movies = new ArrayList<Movie>();

    public PagedMovies(ApiLine apiLine, int page, int pageCount, long total) {
        this.apiLine = apiLine;
        this.page = page;
        this.pageCount = pageCount;
        this.total = total;
    }
}
