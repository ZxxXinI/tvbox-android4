package com.tvbox.android44.domain.playback;

import java.util.Locale;

/** IPTV URL 类型判断：无扩展名的直播端点默认按 HLS 处理。 */
public final class IptvMediaTypeDetector {

    private IptvMediaTypeDetector() {
    }

    public static boolean isHlsUrl(String rawUrl) {
        if (rawUrl == null) {
            return false;
        }
        String url = rawUrl.trim().toLowerCase(Locale.ROOT);
        if (url.isEmpty()) {
            return false;
        }
        if (url.contains(".m3u8")) {
            return true;
        }
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = url.length();
        if (query >= 0) {
            end = query;
        }
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        String path = url.substring(0, end);
        int slash = path.lastIndexOf('/');
        String lastSegment = slash >= 0 ? path.substring(slash + 1) : path;
        // 明确的普通媒体扩展名继续走 DefaultMediaSourceFactory。
        if (lastSegment.endsWith(".mp4") || lastSegment.endsWith(".flv")
                || lastSegment.endsWith(".ts") || lastSegment.endsWith(".mkv")
                || lastSegment.endsWith(".avi") || lastSegment.endsWith(".mov")
                || lastSegment.endsWith(".webm") || lastSegment.endsWith(".mp3")
                || lastSegment.endsWith(".aac")) {
            return false;
        }
        // IPTV 服务常用 /live/cctv1、/stream/channel 等无扩展名 HLS 端点。
        return !lastSegment.contains(".");
    }
}
