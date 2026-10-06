package com.tvbox.android44.feature.settings;

import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ConfigHttpServerTest {
    private static final InetAddress LOOPBACK;
    static { try { LOOPBACK = InetAddress.getByName("127.0.0.1"); } catch (Exception e) { throw new AssertionError(e); } }
    private final BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final AtomicLong clock = new AtomicLong(100);
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicReference<Map<String, String>> fields = new AtomicReference<>();
    private final AtomicReference<ConfigHttpServer.CloseReason> reason = new AtomicReference<>();
    private final List<ConfigHttpServer> sessions = new ArrayList<>();
    private final ExecutorService phone = Executors.newSingleThreadExecutor();
    private final Timer timer = new Timer();

    static class Timer extends ScheduledThreadPoolExecutor {
        final List<Task> tasks = new ArrayList<>();
        Timer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit unit) {
            Task task = new Task(runnable); tasks.add(task); return task;
        }
        void expire() { for (Task task : new ArrayList<>(tasks)) if (!task.cancelled) task.runnable.run(); }
    }
    static class Task implements ScheduledFuture<Object> {
        final Runnable runnable; volatile boolean cancelled;
        Task(Runnable runnable) { this.runnable = runnable; }
        public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return cancelled; }
        public Object get() { return null; }
        public Object get(long timeout, TimeUnit unit) { return null; }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
    private ConfigHttpServer session(ConfigHttpServer.Mode mode, int base, int tries) {
        ConfigHttpServer session = new ConfigHttpServer(mode, LOOPBACK, base, tries, 300000, timer,
                main::add, clock::get, (owner, submitted) -> {
                    assertEquals(mode, owner.mode()); saves.incrementAndGet(); fields.set(submitted); return true;
                }, (owner, why) -> reason.set(why));
        sessions.add(session); assertTrue(session.start()); return session;
    }
    @After public void tearDown() {
        for (ConfigHttpServer session : sessions) session.stop();
        phone.shutdownNow(); timer.shutdownNow();
    }
    private Future<String> request(ConfigHttpServer session, String method, String token, String body) {
        int port = session.port();
        return phone.submit(() -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String headers = method + " /" + token + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + payload.length + "\r\n\r\n";
            return raw(port, headers, payload);
        });
    }
    private static String raw(int port, String headers, byte[] payload) throws Exception {
        try (Socket socket = new Socket(LOOPBACK, port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().write(payload);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
            try { int count; while ((count = socket.getInputStream().read(buffer)) >= 0) out.write(buffer, 0, count); }
            catch (SocketException ignored) { }
            return out.toString("UTF-8");
        }
    }
    private void callback() throws Exception {
        Runnable runnable = main.poll(2, TimeUnit.SECONDS); assertNotNull("callback not queued", runnable); runnable.run();
    }
    private void drainCallbacks() { Runnable callback; while ((callback = main.poll()) != null) callback.run(); }
    private void assertPortReleased(int port) throws Exception {
        try (ServerSocket available = new ServerSocket()) { available.setReuseAddress(true); available.bind(new InetSocketAddress(LOOPBACK, port)); }
    }

    @Test public void formUsesNoStoreAndDoesNotEchoKeys() throws Exception {
        ConfigHttpServer session = session(ConfigHttpServer.Mode.AI, 0, 1);
        String response = request(session, "GET", session.token(), "").get(2, TimeUnit.SECONDS);
        assertTrue(response.startsWith("HTTP/1.1 200")); assertTrue(response.contains("Cache-Control: no-store"));
        assertTrue(response.contains("type=\"password\"")); assertEquals(0, saves.get());
    }
    @Test public void utf8PostReturnsSuccessBeforeClosingAndTokenCannotBeReused() throws Exception {
        ConfigHttpServer session = session(ConfigHttpServer.Mode.API, 0, 1);
        String token = session.token();
        Future<String> response = request(session, "POST", token, "name=测试来源&baseUrl=https%3A%2F%2Fexample.com%2Fapi%2F");
        callback();
        assertTrue(response.get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 200"));
        callback(); assertEquals(ConfigHttpServer.CloseReason.SAVED, reason.get());
        assertEquals("测试来源", fields.get().get("name")); assertEquals(1, saves.get());
        assertFalse(session.isRunning()); assertNull(session.token()); assertFalse(session.start()); assertPortReleased(session.port());
        ConfigHttpServer fresh = session(ConfigHttpServer.Mode.API, session.port(), 1);
        assertNotEquals(token, fresh.token());
        assertTrue(request(fresh, "GET", token, "").get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 403"));
    }
    @Test public void successfulAiPostDoesNotEchoSubmittedKey() throws Exception {
        ConfigHttpServer session = session(ConfigHttpServer.Mode.AI, 0, 1);
        Future<String> response = request(session, "POST", session.token(), "provider=deepseek&model=fixture-model&apiKey=unit-test-key-marker");
        callback(); String text = response.get(2, TimeUnit.SECONDS);
        assertTrue(text.startsWith("HTTP/1.1 200")); assertFalse(text.contains("unit-test-key-marker")); assertEquals(1, saves.get());
    }
    @Test public void expiredIdleSessionClosesPortWithoutReceivingAnyRequest() throws Exception {
        ConfigHttpServer session = session(ConfigHttpServer.Mode.API, 0, 1);
        clock.set(300100); timer.expire(); callback();
        assertFalse(session.isRunning()); assertEquals(ConfigHttpServer.CloseReason.EXPIRED, reason.get()); assertPortReleased(session.port());
    }
    @Test public void stoppingClosesAcceptedConnectionAndDiscardsPendingSubmission() throws Exception {
        ConfigHttpServer old = session(ConfigHttpServer.Mode.AI, 0, 1);
        Future<String> response = request(old, "POST", old.token(), "provider=deepseek&model=fixture&apiKey=test-key");
        Runnable pending = main.poll(2, TimeUnit.SECONDS); assertNotNull(pending);
        old.stop();
        ConfigHttpServer fresh = session(ConfigHttpServer.Mode.API, old.port(), 1);
        pending.run(); drainCallbacks(); response.get(2, TimeUnit.SECONDS);
        assertEquals(0, saves.get());
        Future<String> next = request(fresh, "POST", fresh.token(), "name=新来源&baseUrl=https%3A%2F%2Fexample.com%2Fapi%2F");
        callback(); assertTrue(next.get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 200"));
        assertEquals(1, saves.get()); assertEquals("新来源", fields.get().get("name"));
    }
    @Test public void bothWrongTokensAndInvalidFormsEnforceFailureLimit() throws Exception {
        ConfigHttpServer badToken = session(ConfigHttpServer.Mode.API, 0, 1);
        for (int i = 0; i < 5; i++) assertTrue(request(badToken, "GET", "wrong", "").get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 403"));
        callback(); assertEquals(ConfigHttpServer.CloseReason.FAILURES, reason.get()); assertFalse(badToken.isRunning());
        ConfigHttpServer badFields = session(ConfigHttpServer.Mode.API, 0, 1); String token = badFields.token();
        for (int i = 0; i < 5; i++) assertTrue(request(badFields, "POST", token, "name=source&baseUrl=http%3A%2F%2Fbad%20host").get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 400"));
        callback(); assertFalse(badFields.isRunning()); assertEquals(0, saves.get());
    }
    @Test public void malformedFormAndOversizedBodyDoNotKillListener() throws Exception {
        ConfigHttpServer session = session(ConfigHttpServer.Mode.API, 0, 1);
        assertTrue(request(session, "POST", session.token(), "name=%ZZ&baseUrl=https%3A%2F%2Fexample.com").get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 400"));
        String large = raw(session.port(), "POST /" + session.token() + " HTTP/1.1\r\nContent-Length: 65536\r\n\r\n", new byte[0]);
        assertTrue(large.startsWith("HTTP/1.1 413"));
        assertTrue(request(session, "GET", session.token(), "").get(2, TimeUnit.SECONDS).startsWith("HTTP/1.1 200"));
        assertEquals(0, saves.get());
    }
    @Test public void portFallbackAndRepeatedOpenCloseReleaseEveryPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 50, LOOPBACK)) {
            ConfigHttpServer session = session(ConfigHttpServer.Mode.API, occupied.getLocalPort(), 4);
            assertNotEquals(occupied.getLocalPort(), session.port()); session.stop(); assertPortReleased(session.port());
        }
        for (int i = 0; i < 10; i++) {
            ConfigHttpServer session = session(ConfigHttpServer.Mode.API, 0, 1); session.stop(); assertPortReleased(session.port());
        }
    }
}
