package com.tvbox.android44.data.local;

import com.tvbox.android44.domain.model.WatchHistoryItem;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HistoryStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private WatchHistoryItem item(String id, long time) {
        WatchHistoryItem item = new WatchHistoryItem();
        item.movieId = id; item.apiLineId = "source"; item.movieName = "影片" + id;
        item.lineId = "source|m3u8"; item.lineName = "m3u8";
        item.episodeIndex = 2; item.episodeTitle = "第3集";
        item.episodeUrl = "https://example.com/video.m3u8";
        item.position = 123456; item.duration = 240000; item.updatedAt = time;
        return item;
    }

    @Test public void restartRestoresPlaybackFieldsAndUpsertMovesToTop() {
        File file = new File(folder.getRoot(), "history.json");
        HistoryStore store = new HistoryStore(file);
        assertTrue(store.addOrUpdate(item("1", 1)));
        assertTrue(store.addOrUpdate(item("2", 2)));
        WatchHistoryItem updated = item("1", 3);
        updated.position = 160000;
        assertTrue(store.addOrUpdate(updated));
        List<WatchHistoryItem> restored = new HistoryStore(file).load();
        assertEquals(2, restored.size());
        assertEquals("1", restored.get(0).movieId);
        assertEquals(160000, restored.get(0).position);
        assertEquals("source|m3u8", restored.get(0).lineId);
        assertEquals("第3集", restored.get(0).episodeTitle);
        assertEquals(2, restored.get(0).episodeIndex);
    }

    @Test public void capsAt100AndReturnsIndependentSnapshots() {
        HistoryStore store = new HistoryStore(new File(folder.getRoot(), "history.json"));
        for (int i = 0; i < 103; i++) assertTrue(store.addOrUpdate(item(String.valueOf(i), i)));
        List<WatchHistoryItem> first = store.load();
        assertEquals(100, first.size());
        assertEquals("102", first.get(0).movieId);
        first.get(0).position = 0;
        first.clear();
        assertEquals(100, store.load().size());
        assertEquals(123456, store.find("source", "102").position);
    }

    @Test public void corruptJsonIsBackedUpAndBadRowsDoNotHideValidHistory() throws Exception {
        File file = new File(folder.getRoot(), "history.json");
        Files.write(file.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        assertTrue(new HistoryStore(file).load().isEmpty());
        assertTrue(new File(folder.getRoot(), "history.json.corrupt").isFile());
        HistoryStore.HistoryList list = new HistoryStore.HistoryList();
        list.items.add(null); list.items.add(new WatchHistoryItem());
        list.items.add(item("1", 1)); list.items.add(item("2", 3)); list.items.add(item("1", 2));
        JsonIo.write(file, list);
        List<WatchHistoryItem> actual = new HistoryStore(file).load();
        assertEquals(2, actual.size());
        assertEquals("2", actual.get(0).movieId);
        assertEquals(2, actual.get(1).updatedAt);
    }

    @Test public void failedWriteDoesNotReplaceCacheOrNotifyObservers() throws Exception {
        File parent = folder.newFolder("parent");
        File file = new File(parent, "history.json");
        HistoryStore store = new HistoryStore(file);
        assertTrue(store.addOrUpdate(item("1", 1)));
        byte[] saved = Files.readAllBytes(file.toPath());
        AtomicInteger notifications = new AtomicInteger();
        store.addListener(notifications::incrementAndGet);
        assertTrue(file.delete()); assertTrue(parent.delete());
        assertTrue(parent.createNewFile());
        assertFalse(store.addOrUpdate(item("2", 2)));
        assertFalse(store.clear());
        assertEquals(0, notifications.get());
        assertEquals("1", store.load().get(0).movieId);
        assertTrue(parent.delete()); assertTrue(parent.mkdir());
        Files.write(file.toPath(), saved);
        assertEquals("1", new HistoryStore(file).load().get(0).movieId);
        assertTrue(store.clear());
        assertTrue(new HistoryStore(file).load().isEmpty());
    }
}
