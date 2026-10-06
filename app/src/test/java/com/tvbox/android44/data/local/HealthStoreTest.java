package com.tvbox.android44.data.local;

import com.tvbox.android44.domain.model.LineHealth;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class HealthStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final Queue<Runnable> work = new ArrayDeque<>();
    private final Executor disk = work::add;
    private void drain() { while (!work.isEmpty()) work.remove().run(); }
    private HealthStore store(File file) { return new HealthStore(file, disk, Runnable::run); }

    @Test public void initializationAndReadsNeverPerformCallerDiskIo() throws Exception {
        File file = new File(folder.getRoot(), "health.json");
        Files.write(file.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        HealthStore store = store(file);
        assertNull(store.get("line"));
        assertTrue(store.statsSnapshot().isEmpty());
        assertTrue(file.isFile());
        assertFalse(new File(file + ".corrupt").exists());
        drain();
        assertTrue(new File(file + ".corrupt").isFile());
    }

    @Test public void orderedRecordsSurviveRestartAndSnapshotsAreIndependent() {
        File file = new File(folder.getRoot(), "health.json");
        HealthStore store = store(file);
        long now = System.currentTimeMillis();
        store.recordFail("line", now); store.recordSlow("line", now + 1); store.recordSuccess("line", now + 2);
        assertNull(store.get("line"));
        drain();
        LineHealth saved = store.get("line");
        assertEquals(1, saved.successCount); assertEquals(1, saved.failCount); assertEquals(1, saved.slowCount);
        assertEquals(0, saved.cooldownUntil);
        saved.successCount = 500; store.load().clear(); store.statsSnapshot().get(0).failCount = 900;
        assertEquals(1, store.get("line").successCount); assertEquals(1, store.get("line").failCount);
        HealthStore reopened = store(file); drain();
        assertEquals(now + 2, reopened.get("line").lastSuccessAt);
        assertEquals(0.5, reopened.get("line").successRate(), 0.0001);
    }

    @Test public void clearIsOrderedAndCallbackMeansDurableSuccess() {
        File file = new File(folder.getRoot(), "health.json");
        HealthStore store = store(file);
        AtomicBoolean cleared = new AtomicBoolean();
        long now = System.currentTimeMillis();
        store.recordSuccess("before", now);
        store.clearAll(cleared::set);
        store.recordFail("after", now);
        assertFalse(cleared.get());
        drain();
        assertTrue(cleared.get()); assertNull(store.get("before")); assertEquals(1, store.get("after").failCount);
        HealthStore reopened = store(file); drain();
        assertNull(reopened.get("before")); assertNotNull(reopened.get("after"));
    }

    @Test public void failedWriteAndClearPreserveSavedSnapshot() throws Exception {
        File parent = folder.newFolder("parent"), file = new File(parent, "health.json");
        HealthStore store = store(file);
        store.recordSuccess("saved", System.currentTimeMillis()); drain();
        byte[] original = Files.readAllBytes(file.toPath());
        assertTrue(file.delete()); assertTrue(parent.delete()); assertTrue(parent.createNewFile());
        AtomicBoolean success = new AtomicBoolean(true);
        store.recordFail("unsaved", System.currentTimeMillis()); store.clearAll(success::set); drain();
        assertFalse(success.get()); assertNull(store.get("unsaved")); assertNotNull(store.get("saved"));
        assertTrue(parent.delete()); assertTrue(parent.mkdir()); Files.write(file.toPath(), original);
        HealthStore reopened = store(file); drain(); assertNotNull(reopened.get("saved"));
    }

    @Test public void invalidExpiredAndExcessRowsAreCleanedUsingAllEventTimes() throws Exception {
        File file = new File(folder.getRoot(), "health.json");
        HealthStore.HealthMap wrapper = new HealthStore.HealthMap();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            LineHealth value = new LineHealth("row-" + i); value.lastSuccessAt = now + i;
            wrapper.entries.put(value.key, value);
        }
        LineHealth slow = new LineHealth("slow"); slow.lastSlowAt = now + 1000; slow.slowCount = -3;
        wrapper.entries.put("slow", slow);
        LineHealth expired = new LineHealth("expired"); expired.lastFailAt = now - 31L * 24 * 3600 * 1000;
        wrapper.entries.put("expired", expired); wrapper.entries.put("bad", null);
        JsonIo.write(file, wrapper);
        HealthStore store = store(file); drain();
        assertEquals(300, store.load().size()); assertNull(store.get("expired")); assertNull(store.get("bad"));
        assertNull(store.get("row-0")); assertEquals("slow", store.statsSnapshot().get(0).key);
        assertEquals(0, store.get("slow").slowCount);
    }

    @Test public void saturatedCountersDoNotOverflowSuccessRate() throws Exception {
        File file = new File(folder.getRoot(), "health.json");
        HealthStore.HealthMap wrapper = new HealthStore.HealthMap();
        LineHealth value = new LineHealth("line"); value.lastSuccessAt = System.currentTimeMillis();
        value.successCount = Integer.MAX_VALUE; value.failCount = Integer.MAX_VALUE;
        wrapper.entries.put("line", value); JsonIo.write(file, wrapper);
        HealthStore store = store(file); store.recordSuccess("line", System.currentTimeMillis()); drain();
        assertEquals(Integer.MAX_VALUE, store.get("line").successCount);
        assertEquals(0.5, store.get("line").successRate(), 0.0001);
    }
}
