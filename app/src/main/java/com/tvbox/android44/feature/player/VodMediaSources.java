package com.tvbox.android44.feature.player;

import android.net.Uri;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Observes the actual media response, without an extra probe request. */
final class VodMediaSources implements DataSource.Factory {
    private final DataSource.Factory delegate;
    private volatile String contentType = "";

    VodMediaSources(DataSource.Factory delegate) { this.delegate = delegate; }

    static boolean isHls(String url) {
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    MediaSource create(String url, boolean hls) {
        MediaItem item = MediaItem.fromUri(url);
        return hls ? new HlsMediaSource.Factory(this).createMediaSource(item)
                : new ProgressiveMediaSource.Factory(this).createMediaSource(item);
    }

    boolean shouldFallback(PlaybackException error, boolean currentHls) {
        boolean markedHls = contentType.contains("mpegurl");
        if (markedHls && !currentHls) return true;
        return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    @Override public DataSource createDataSource() {
        final DataSource data = delegate.createDataSource();
        return new DataSource() {
            @Override public void addTransferListener(TransferListener listener) { data.addTransferListener(listener); }
            @Override public long open(DataSpec spec) throws IOException {
                long length = data.open(spec);
                for (Map.Entry<String, List<String>> header : data.getResponseHeaders().entrySet()) {
                    if ("content-type".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                        contentType = header.getValue().get(0).toLowerCase(Locale.ROOT);
                        break;
                    }
                }
                return length;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException { return data.read(buffer, offset, length); }
            @Override public Uri getUri() { return data.getUri(); }
            @Override public Map<String, List<String>> getResponseHeaders() { return data.getResponseHeaders(); }
            @Override public void close() throws IOException { data.close(); }
        };
    }
}
