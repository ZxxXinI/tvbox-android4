package com.tvbox.android44.domain.parser;

import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PlayEpisode;
import com.tvbox.android44.domain.model.PlaySource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MacCMS 播放串解析：
 * vod_play_from 与 vod_play_url 用 `$$$` 对齐线路，`#` 分集，第一个 `$` 分隔标题与 URL。
 * URL 允许直接 URL、Markdown 圆括号/方括号 URL；最终只接受 http/https。
 * 空线路、空集、空 URL 不进入领域模型。
 */
public final class PlayStringParser {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]\\)\"'<>]+");

    private PlayStringParser() {
    }

    public static List<PlaySource> parse(String apiLineId, String apiLineName,
                                         String vodPlayFrom, String vodPlayUrl) {
        List<PlaySource> result = new ArrayList<PlaySource>();
        if (vodPlayFrom == null || vodPlayUrl == null) {
            return result;
        }
        String[] froms = split(vodPlayFrom, "$$$");
        String[] urls = split(vodPlayUrl, "$$$");
        for (int i = 0; i < Math.min(froms.length, urls.length); i++) {
            String lineName = froms[i].trim();
            String lineUrl = urls[i];
            if (lineName.isEmpty()) {
                continue;
            }
            List<PlayEpisode> episodes = parseEpisodes(lineUrl);
            if (episodes.isEmpty()) {
                // 空线路不进入领域模型
                continue;
            }
            String lineId = Movie.stableLineId(apiLineId, lineName);
            PlaySource source = new PlaySource(lineId, lineName, apiLineName);
            source.episodes.addAll(episodes);
            result.add(source);
        }
        return result;
    }

    /** 字面量分隔符分割（不用正则：dalvik 与 JVM 行为一致，且更快）。 */
    static String[] split(String raw, String separator) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        int idx;
        while ((idx = raw.indexOf(separator, start)) >= 0) {
            parts.add(raw.substring(start, idx));
            start = idx + separator.length();
        }
        parts.add(raw.substring(start));
        return parts.toArray(new String[parts.size()]);
    }

    static List<PlayEpisode> parseEpisodes(String lineUrl) {
        List<PlayEpisode> episodes = new ArrayList<PlayEpisode>();
        if (lineUrl == null) {
            return episodes;
        }
        String[] parts = split(lineUrl, "#");
        int index = 0;
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            String title;
            String urlPart;
            int dollar = seg.indexOf('$');
            if (dollar >= 0) {
                title = seg.substring(0, dollar).trim();
                urlPart = seg.substring(dollar + 1).trim();
            } else {
                title = "";
                urlPart = seg;
            }
            String url = extractUrl(urlPart);
            if (url == null) {
                continue;
            }
            if (title.isEmpty()) {
                title = "第" + (index + 1) + "集";
            }
            episodes.add(new PlayEpisode(index, title, url));
            index++;
        }
        return episodes;
    }

    /** 提取合法 http/https URL，兼容 markdown 圆括号与方括号包裹。 */
    static String extractUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 直接是合法 URL
        if (isHttpUrl(s)) {
            return stripTrailing(s);
        }
        // Markdown 圆括号 (url) / 方括号 [url] 包裹
        Matcher m = URL_PATTERN.matcher(s);
        if (m.find()) {
            String url = m.group();
            return stripTrailing(url);
        }
        return null;
    }

    private static String stripTrailing(String url) {
        // 去掉尾部粘连的 markdown 结束符
        while (url.endsWith(")") || url.endsWith("]")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static boolean isHttpUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }
}
