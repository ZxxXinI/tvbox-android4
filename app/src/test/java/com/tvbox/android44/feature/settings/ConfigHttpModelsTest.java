package com.tvbox.android44.feature.settings;

import com.tvbox.android44.data.remote.AiModelsClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ConfigHttpModelsTest {
    private ConfigHttpServer session;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService phone = Executors.newSingleThreadExecutor();
    private final AtomicLong clock = new AtomicLong(0);
    private final AtomicInteger saved = new AtomicInteger();
    private final AtomicInteger queried = new AtomicInteger();
    private final AtomicReference<ConfigHttpServer.CloseReason> closeReason = new AtomicReference<>();
    private ConfigHttpServer start(ConfigHttpServer.Mode mode, ConfigHttpServer.ModelsLoader loader) throws Exception {
        return start(mode, loader, 300000);
    }
    private ConfigHttpServer start(ConfigHttpServer.Mode mode, ConfigHttpServer.ModelsLoader loader, long ttl)
            throws Exception {
        session = new ConfigHttpServer(mode, InetAddress.getByName("127.0.0.1"), 0, 1, ttl, timer,
                Runnable::run, clock::get, (owner, fields) -> { saved.incrementAndGet(); return true; },
                (owner, reason) -> closeReason.set(reason), loader);
        assertTrue(session.start()); return session;
    }
    private AiModelsClient.Catalog catalog() {
        return new AiModelsClient.Catalog("remote", "fixture", "",
                Collections.singletonList(new AiModelsClient.Model("fixture-model", "Fixture")));
    }
    private String request(String method, String path, String fields) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", session.port())) {
            socket.setSoTimeout(3000);
            byte[] data = fields.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: " + data.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(data);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int n;
            try { while ((n = socket.getInputStream().read(bytes)) >= 0) output.write(bytes, 0, n); }
            catch (SocketException ignored) { }
            return output.toString("UTF-8");
        }
    }
    @After public void stop() {
        if (session != null) session.stop(); timer.shutdownNow(); phone.shutdownNow();
    }
    @Test public void queryingModelsNeverSavesOrConsumesTheTokenAndFinalSaveWorks() throws Exception {
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> {
            assertEquals("mimo", provider.id); assertEquals("test-key-marker", key);
            queried.incrementAndGet(); return catalog();
        });
        String token = session.token();
        String body = request("POST", "/" + token + "/models", "provider=mimo&apiKey=test-key-marker");
        assertTrue(body.startsWith("HTTP/1.1 200")); assertTrue(body.contains("fixture-model"));
        assertFalse(body.contains("test-key-marker")); assertEquals(0, saved.get());
        assertEquals(token, session.token()); assertTrue(session.isRunning());
        assertEquals(1, queried.get());
        String savedBody = request("POST", "/" + token, "provider=mimo&apiKey=test-key-marker&model=fixture-model");
        assertTrue(savedBody.startsWith("HTTP/1.1 200")); assertEquals(1, saved.get());
        assertFalse(session.isRunning()); assertNull(session.token());
    }
    @Test public void authErrorsRemainRetryableAndDoNotSpendSessionFailureCount() throws Exception {
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) ->
                AiModelsClient.Catalog.error("API Key 无效或无权限"));
        String token = session.token();
        for (int i = 0; i < 7; i++) {
            assertTrue(request("POST", "/" + token + "/models", "provider=glm&apiKey=test-key")
                    .contains("\"source\":\"error\""));
        }
        assertTrue(session.isRunning()); assertEquals(token, session.token()); assertEquals(0, saved.get());
    }
    @Test public void foreignModeInvalidTokensAndInvalidProvidersCannotQuery() throws Exception {
        start(ConfigHttpServer.Mode.API, (provider, key, scope) -> { queried.incrementAndGet(); return catalog(); });
        assertTrue(request("POST", "/" + session.token() + "/models", "provider=glm&apiKey=test")
                .startsWith("HTTP/1.1 404")); assertEquals(0, queried.get());
        session.stop();
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> { queried.incrementAndGet(); return catalog(); });
        assertTrue(request("POST", "/wrong/models", "provider=glm&apiKey=test").startsWith("HTTP/1.1 403"));
        assertTrue(request("POST", "/" + session.token() + "/models", "provider=evil&apiKey=test")
                .startsWith("HTTP/1.1 400")); assertEquals(0, queried.get());
    }
    @Test public void stopCancelsAnInFlightSupplierRequestAndReleasesPort() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> {
            entered.countDown();
            try { while (!scope.isCancelled()) Thread.sleep(5); }
            catch (InterruptedException ignored) { }
            if (scope.isCancelled()) cancelled.countDown();
            throw new InterruptedIOException();
        });
        String path = "/" + session.token() + "/models";
        Future<String> response = phone.submit(() -> request("POST", path, "provider=kimi&apiKey=test"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        int port = session.port(); session.stop();
        assertTrue(cancelled.await(1, TimeUnit.SECONDS)); response.get(1, TimeUnit.SECONDS);
        assertEquals(0, saved.get());
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true); probe.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }
    @Test public void expiredModelsRouteCannotContactSupplier() throws Exception {
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> { queried.incrementAndGet(); return catalog(); });
        String token = session.token(); clock.set(300001);
        assertTrue(request("POST", "/" + token + "/models", "provider=deepseek&apiKey=test")
                .startsWith("HTTP/1.1 403")); assertEquals(0, queried.get());
    }
    @Test public void expiryAlsoCancelsAnAlreadyRunningModelRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> {
            entered.countDown();
            try { while (!scope.isCancelled()) Thread.sleep(5); }
            catch (InterruptedException ignored) { }
            if (scope.isCancelled()) cancelled.countDown();
            throw new InterruptedIOException();
        }, 400);
        String path = "/" + session.token() + "/models";
        Future<String> response = phone.submit(() -> request("POST", path, "provider=glm&apiKey=test"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS)); response.get(1, TimeUnit.SECONDS);
        assertFalse(session.isRunning()); assertEquals(0, saved.get());
        assertEquals(ConfigHttpServer.CloseReason.EXPIRED, closeReason.get());
    }
    @Test public void aNewModelQueryCancelsTheOldOneWithoutWaitingForItsTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> {
            if (loads.incrementAndGet() == 1) {
                entered.countDown();
                try { while (!scope.isCancelled()) Thread.sleep(5); }
                catch (InterruptedException ignored) { }
                if (scope.isCancelled()) cancelled.countDown();
                throw new InterruptedIOException();
            }
            assertEquals("new-key", key); return catalog();
        });
        String path = "/" + session.token() + "/models";
        Future<String> old = phone.submit(() -> request("POST", path, "provider=qwen&apiKey=old-key"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        String latest = request("POST", path, "provider=mimo&apiKey=new-key");
        assertTrue(latest.startsWith("HTTP/1.1 200")); assertTrue(latest.contains("fixture-model"));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS)); old.get(1, TimeUnit.SECONDS);
        assertEquals(0, saved.get()); assertTrue(session.isRunning());
    }
    @Test public void phonePageShowsFiveProvidersAndNoBrowserStorageCode() throws Exception {
        start(ConfigHttpServer.Mode.AI, (provider, key, scope) -> catalog());
        String html = request("GET", "/" + session.token(), "");
        for (String name : new String[]{"DeepSeek", "Qwen", "GLM", "KIMI", "MIMO", "获取模型列表", "TVBox4.1+"}) {
            assertTrue(html.contains(name));
        }
        assertFalse(html.contains("localStorage")); assertFalse(html.contains("sessionStorage"));
        assertTrue(html.contains("type=\"password\"")); assertTrue(html.contains("Cache-Control: no-store"));
        File directory = new File("build/test-ui"); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "phone-ai-page.html"))) {
            String page = AiConfigPage.render("test-session-token");
            output.write(("\uFEFF" + page).getBytes(StandardCharsets.UTF_8));
        }
    }
}
