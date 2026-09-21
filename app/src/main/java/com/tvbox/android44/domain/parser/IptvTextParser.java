package com.tvbox.android44.domain.parser;

import com.tvbox.android44.domain.model.LiveChannel;
import com.tvbox.android44.domain.model.LiveChannelGroup;
import com.tvbox.android44.domain.model.LiveChannelLine;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IPTV 文本解析（规则见文档 08）：
 * 1. trim、忽略空行；2. 首个英文逗号左侧为名称；3. `分组名,#genre#` 更新分组；
 * 4. 无分组忽略；5. URL 按首个 `$` 截断；6. 元数据只作线路名；7. 清理编号与 HTML；
 * 8. 同组同名合并、URL 去重；9. 按出现顺序连续编号（从 1 开始）。
 * 兼容 BOM / CRLF / LF / 畸形行 / 无分组旧格式。
 */
public final class IptvTextParser {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^[0-9]{1,4}[.、:：\\-\\s]+");
    private static final Pattern NAME_HTML = Pattern.compile("<[^>]*>");

    private IptvTextParser() {
    }

    public static List<LiveChannelGroup> parse(String text) {
        List<LiveChannelGroup> groups = new ArrayList<LiveChannelGroup>();
        if (text == null) {
            return groups;
        }
        String cleaned = stripBom(text);
        Map<String, LiveChannel> byGroupAndName = new HashMap<String, LiveChannel>();
        String currentGroup = null;
        BufferedReader reader = new BufferedReader(new StringReader(cleaned));
        try {
            String raw;
            int number = 0;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (comma <= 0) {
                    // 无逗号的行：可能是纯分组名（不带 #genre#），忽略
                    continue;
                }
                String name = cleanName(line.substring(0, comma).trim());
                String rest = line.substring(comma + 1).trim();

                if (rest.equals("#genre#") || "#genre#".equals(name) || rest.startsWith("#genre#")) {
                    if (!rest.equals("#genre#")) {
                        continue;
                    }
                    if (name.isEmpty()) {
                        continue;
                    }
                    currentGroup = name;
                    getOrCreateGroup(groups, name);
                    continue;
                }
                if (currentGroup == null || name.isEmpty()) {
                    // 没有当前分组时忽略频道行
                    continue;
                }
                // 文档 08 §5：频道来源按第一个 $ 截断为真实 URL，$ 后元数据只作线路名
                // （如 CCTV1,http://u$LR•IPV4•29『线路』）；兼容变体：$ 前为线路名、$ 后为 URL
                String before = rest;
                String after = "";
                int dollar = rest.indexOf('$');
                if (dollar >= 0) {
                    before = rest.substring(0, dollar).trim();
                    after = rest.substring(dollar + 1).trim();
                }
                String url = extractHttpUrl(before);
                String lineName;
                if (url != null) {
                    lineName = after.isEmpty() ? "线路1" : after;
                } else {
                    url = extractHttpUrl(after);
                    if (url == null) {
                        continue;
                    }
                    lineName = before.isEmpty() ? "线路1" : before;
                }

                String key = currentGroup + "\u0001" + name;
                LiveChannel channel = byGroupAndName.get(key);
                if (channel == null) {
                    channel = new LiveChannel(currentGroup, name);
                    channel.number = ++number;
                    byGroupAndName.put(key, channel);
                    getOrCreateGroup(groups, currentGroup).channels.add(channel);
                }
                // 重复 URL 去重
                boolean dup = false;
                for (LiveChannelLine l : channel.lines) {
                    if (l.url.equals(url)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    channel.lines.add(new LiveChannelLine(lineName, url));
                }
            }
        } catch (java.io.IOException e) {
            // StringReader 不会抛出
        }
        return groups;
    }

    private static LiveChannelGroup getOrCreateGroup(List<LiveChannelGroup> groups, String name) {
        for (LiveChannelGroup g : groups) {
            if (g.name.equals(name)) {
                return g;
            }
        }
        LiveChannelGroup g = new LiveChannelGroup(name);
        groups.add(g);
        return g;
    }

    /** 清理频道名前无意义编号与 HTML。 */
    static String cleanName(String raw) {
        String s = raw;
        s = NAME_HTML.matcher(s).replaceAll("");
        s = LEADING_NUMBER.matcher(s).replaceFirst("");
        return s.trim();
    }

    /** 提取 http/https URL：从 http 起到段尾，遇到 `$` 截断（后续是元数据）。 */
    static String extractHttpUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int idx;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            idx = 0;
        } else {
            idx = s.indexOf("http://");
            int httpsIdx = s.indexOf("https://");
            if (idx < 0 || (httpsIdx >= 0 && httpsIdx < idx)) {
                idx = httpsIdx;
            }
        }
        if (idx < 0) {
            return null;
        }
        String url = s.substring(idx).trim();
        int dollar = url.indexOf('$');
        if (dollar >= 0) {
            url = url.substring(0, dollar).trim();
        }
        return url.isEmpty() ? null : url;
    }

    static String stripBom(String s) {
        if (s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s;
    }
}
