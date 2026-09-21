package com.tvbox.android44.domain.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** IPTV 无扩展名 HLS 与明确普通媒体扩展名的判断。 */
public class IptvMediaTypeDetectorTest {

    @Test
    public void extensionlessLiveEndpoint_isHls() {
        assertTrue(IptvMediaTypeDetector.isHlsUrl("https://cdn.example/live/cctv1"));
    }

    @Test
    public void m3u8WithQuery_isHls() {
        assertTrue(IptvMediaTypeDetector.isHlsUrl(
                "http://example/live/index.m3u8?auth=test"));
    }

    @Test
    public void progressiveExtensions_areNotHls() {
        assertFalse(IptvMediaTypeDetector.isHlsUrl("http://example/live/movie.mp4"));
        assertFalse(IptvMediaTypeDetector.isHlsUrl("http://example/live/channel.flv"));
    }

    @Test
    public void nullAndEmpty_areSafe() {
        assertFalse(IptvMediaTypeDetector.isHlsUrl(null));
        assertFalse(IptvMediaTypeDetector.isHlsUrl("  "));
    }
}
