package com.tvbox.android44.feature.player;

import android.net.Uri;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.upstream.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class VodMediaSourcesTest {
    @Test public void mediaPathDeterminesInitialTypeIndependentlyOfQueryParameters() {
        assertTrue(VodMediaSources.isHls("https://example.com/VIDEO.M3U8?token=fixture"));
        assertFalse(VodMediaSources.isHls("https://example.com/video.mp4?next=other.m3u8"));
        assertFalse(VodMediaSources.isHls("https://example.com/signed/stream?token=fixture"));
    }
    @Test public void actualResponseTypeEnablesHlsFallbackWithoutAnotherProbe() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        VodMediaSources sources = new VodMediaSources(() -> new DataSource() {
            public void addTransferListener(TransferListener listener) { }
            public long open(DataSpec spec) { opens.incrementAndGet(); return 0; }
            public int read(byte[] buffer, int offset, int length) { return -1; }
            public Uri getUri() { return Uri.parse("https://example.com/stream"); }
            public Map<String, List<String>> getResponseHeaders() { return Collections.singletonMap("Content-Type", Collections.singletonList("application/vnd.apple.mpegurl; charset=utf-8")); }
            public void close() { }
        });
        sources.createDataSource().open(new DataSpec(Uri.parse("https://example.com/stream")));
        PlaybackException error = new PlaybackException("source", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        assertTrue(sources.shouldFallback(error, false)); assertFalse(sources.shouldFallback(error, true)); assertEquals(1, opens.get());
    }
}
