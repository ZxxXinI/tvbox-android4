package com.tvbox.android44.domain.parser;

import com.tvbox.android44.domain.model.Movie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多来源增量合并：
 * 去重键 = 规范化片名 + "|" + 年份；主来源优先；首现顺序稳定（追加不洗牌）。
 * 相同作品保留首现卡片，记录其他可用来源供详情补线参考。
 */
public final class SearchResultMerger {

    public static final class Merged {
        public final List<Movie> movies = new ArrayList<Movie>();
        /** 首现顺序的去重键列表。 */
        final List<String> order = new ArrayList<String>();
        final Map<String, Movie> byKey = new HashMap<String, Movie>();

        public void add(Movie m) {
            if (m == null) return;
            String key = NameNormalizer.dedupeKey(m.name, m.year);
            Movie exist = byKey.get(key);
            if (exist == null) {
                byKey.put(key, m);
                order.add(key);
                movies.add(m);
                if (!m.availableSourceIds.contains(m.apiLineId)) {
                    m.availableSourceIds.add(m.apiLineId);
                }
                return;
            }
            // 主来源优先：首现即保留；补充可用来源记录
            if (!exist.availableSourceIds.contains(m.apiLineId)) {
                exist.availableSourceIds.add(m.apiLineId);
            }
            // 主卡片缺元数据时用替代来源补齐
            fillMissing(exist, m);
        }

        private static void fillMissing(Movie target, Movie other) {
            if (isEmpty(target.posterUrl) && !isEmpty(other.posterUrl)) {
                target.posterUrl = other.posterUrl;
            }
            if (isEmpty(target.year) && !isEmpty(other.year)) {
                target.year = other.year;
            }
            if (isEmpty(target.area) && !isEmpty(other.area)) {
                target.area = other.area;
            }
            if (isEmpty(target.language) && !isEmpty(other.language)) {
                target.language = other.language;
            }
            if (isEmpty(target.actor) && !isEmpty(other.actor)) {
                target.actor = other.actor;
            }
            if (isEmpty(target.director) && !isEmpty(other.director)) {
                target.director = other.director;
            }
            if (isEmpty(target.description) && !isEmpty(other.description)) {
                target.description = other.description;
            }
            if (isEmpty(target.remarks) && !isEmpty(other.remarks)) {
                target.remarks = other.remarks;
            }
        }

        private static boolean isEmpty(String s) {
            return s == null || s.trim().isEmpty();
        }

        public int size() {
            return movies.size();
        }

        public boolean containsKey(String name, String year) {
            return byKey.containsKey(NameNormalizer.dedupeKey(name, year));
        }
    }

    private SearchResultMerger() {
    }

    public static Merged newMerger() {
        return new Merged();
    }
}
