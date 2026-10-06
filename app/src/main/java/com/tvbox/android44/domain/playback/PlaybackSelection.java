package com.tvbox.android44.domain.playback;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.model.WatchHistoryItem;

/** Shared selection rules for detail, resume and changed playlists. */
public final class PlaybackSelection {
    public final PlaySource source;
    public final int episodeIndex;
    public final long position;

    private PlaybackSelection(PlaySource source, int episodeIndex, long position) {
        this.source = source;
        this.episodeIndex = episodeIndex;
        this.position = position;
    }

    public static PlaybackSelection resolve(Movie movie, WatchHistoryItem saved, boolean applyEndPolicy) {
        PlaySource selected = null;
        if (saved != null) {
            for (PlaySource source : movie.playSources) {
                if (!source.episodes.isEmpty() && source.lineId.equals(saved.lineId)) { selected = source; break; }
            }
            if (selected == null && saved.lineName != null && !saved.lineName.trim().isEmpty()) {
                for (PlaySource source : movie.playSources) {
                    if (!source.episodes.isEmpty() && saved.lineName.trim().equals(source.lineName)) {
                        selected = source; break;
                    }
                }
            }
        }
        if (selected == null) {
            for (PlaySource source : movie.playSources) {
                if (!source.episodes.isEmpty() && source.lineName != null
                        && source.lineName.toLowerCase(java.util.Locale.ROOT).contains("m3u8")) {
                    selected = source; break;
                }
            }
        }
        if (selected == null) {
            for (PlaySource source : movie.playSources) {
                if (!source.episodes.isEmpty()) { selected = source; break; }
            }
        }
        if (selected == null) return null;
        int index = saved == null ? 0 : Math.max(0, Math.min(saved.episodeIndex, selected.episodes.size() - 1));
        if (saved != null && saved.episodeTitle != null && !saved.episodeTitle.trim().isEmpty()) {
            for (int i = 0; i < selected.episodes.size(); i++) {
                if (saved.episodeTitle.trim().equals(selected.episodes.get(i).title)) { index = i; break; }
            }
        }
        long position = saved == null ? 0 : Math.max(0, saved.position);
        if (applyEndPolicy && saved != null && saved.duration > 0
                && position >= saved.duration * AppConstants.HISTORY_END_RESTART_RATIO) {
            if (index + 1 < selected.episodes.size()) index++;
            position = 0;
        }
        return new PlaybackSelection(selected, index, position);
    }
}
