package com.tvbox.android44.domain.playback;

import com.tvbox.android44.domain.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackSelectionTest {
    private Movie movie() {
        Movie movie = new Movie("1", "source", "来源", "影片");
        PlaySource ordinary = new PlaySource("ordinary", "mp4", "来源");
        ordinary.episodes.add(new PlayEpisode(0, "第1集", "https://example.com/1.mp4"));
        PlaySource hls = new PlaySource("new-id", "hls-m3u8", "来源");
        hls.episodes.add(new PlayEpisode(0, "第3集", "https://example.com/3.m3u8"));
        hls.episodes.add(new PlayEpisode(1, "第1集", "https://example.com/1.m3u8"));
        hls.episodes.add(new PlayEpisode(2, "第2集", "https://example.com/2.m3u8"));
        movie.playSources.add(ordinary); movie.playSources.add(hls);
        return movie;
    }
    private WatchHistoryItem saved() {
        WatchHistoryItem saved = new WatchHistoryItem();
        saved.lineId = "old-id"; saved.lineName = "hls-m3u8";
        saved.episodeIndex = 0; saved.episodeTitle = "第1集";
        saved.position = 25000; saved.duration = 100000;
        return saved;
    }
    @Test public void renamedLineAndReorderedEpisodesRestoreByNames() {
        PlaybackSelection result = PlaybackSelection.resolve(movie(), saved(), true);
        assertEquals("new-id", result.source.lineId); assertEquals(1, result.episodeIndex); assertEquals(25000, result.position);
    }
    @Test public void nearEndMovesToNextEpisodeWithZeroPosition() {
        WatchHistoryItem saved = saved(); saved.position = 99000;
        PlaybackSelection result = PlaybackSelection.resolve(movie(), saved, true);
        assertEquals(2, result.episodeIndex); assertEquals(0, result.position);
    }
    @Test public void nearEndOfLastEpisodeRestartsThatEpisode() {
        WatchHistoryItem saved = saved(); saved.episodeTitle = "第2集"; saved.position = 99000;
        PlaybackSelection result = PlaybackSelection.resolve(movie(), saved, true);
        assertEquals(2, result.episodeIndex); assertEquals(0, result.position);
    }
    @Test public void returningFromPlaybackDoesNotAdvanceNearEndSelection() {
        WatchHistoryItem saved = saved(); saved.position = 99000;
        PlaybackSelection result = PlaybackSelection.resolve(movie(), saved, false);
        assertEquals(1, result.episodeIndex); assertEquals(99000, result.position);
    }
    @Test public void missingTitleUsesClampedIndex() {
        WatchHistoryItem saved = saved(); saved.episodeTitle = "已删除的集"; saved.episodeIndex = 99;
        assertEquals(2, PlaybackSelection.resolve(movie(), saved, true).episodeIndex);
        saved.episodeIndex = -1; saved.position = -10;
        PlaybackSelection result = PlaybackSelection.resolve(movie(), saved, true);
        assertEquals(0, result.episodeIndex); assertEquals(0, result.position);
    }
    @Test public void defaultPrefersPlayableHlsAndEmptyPlaylistsReturnNoSelection() {
        Movie movie = movie(); assertEquals("new-id", PlaybackSelection.resolve(movie, null, true).source.lineId);
        movie.playSources.get(1).episodes.clear(); assertEquals("ordinary", PlaybackSelection.resolve(movie, null, true).source.lineId);
        movie.playSources.get(0).episodes.clear(); assertNull(PlaybackSelection.resolve(movie, saved(), true));
    }
}
