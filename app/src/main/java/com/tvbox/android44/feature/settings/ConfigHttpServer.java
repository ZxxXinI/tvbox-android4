package com.tvbox.android44.feature.settings;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.AiModelsClient;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.domain.model.AiProvider;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A temporary configuration session bound to one selected LAN address. */
public class ConfigHttpServer {
    public enum Mode { AI, API }
    public enum CloseReason { CLOSED, SAVED, EXPIRED, FAILURES }
    public interface SubmitListener { boolean onSubmit(ConfigHttpServer session, Map<String, String> fields); }
    public interface CloseListener { void onClosed(ConfigHttpServer session, CloseReason reason); }
    interface ModelsLoader {
        AiModelsClient.Catalog load(AiProvider provider, String key, CancelScope scope) throws IOException;
    }
    interface Clock { long now(); }

    private final Mode mode;
    private final InetAddress address;
    private final SubmitListener listener;
    private final CloseListener closeListener;
    private final Executor callbacks;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final long ttl;
    private final int basePort;
    private final int portTries;
    private final ModelsLoader modelsLoader;
    private volatile CancelScope modelScope;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tvbox-config-http");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService requests = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(8),
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "tvbox-config-request"); thread.setDaemon(true); return thread;
                }
            });
    private final java.util.Set<Socket> activeSockets = new java.util.HashSet<Socket>();
    private ServerSocket serverSocket;
    private ScheduledFuture<?> expiry;
    private volatile int port = -1;
    private volatile String token;
    private long deadline;
    private int failures;
    private boolean closed;

    public ConfigHttpServer(Mode mode, InetAddress address, SubmitListener listener, CloseListener closeListener) {
        this(mode, address, mode == Mode.AI ? AppConstants.CONFIG_PORT_AI : AppConstants.CONFIG_PORT_API,
                AppConstants.CONFIG_PORT_FALLBACK_TRIES, AppConstants.CONFIG_TOKEN_TTL_MS,
                TvBoxApp.get().executors().scheduler(), new Executor() {
                    @Override public void execute(Runnable runnable) { TvBoxApp.get().executors().main(runnable); }
                }, new Clock() {
                    @Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
                }, listener, closeListener);
        if (!address.isSiteLocalAddress()) throw new IllegalArgumentException("需要局域网地址");
    }

    ConfigHttpServer(Mode mode, InetAddress address, int basePort, int portTries, long ttl,
                     ScheduledExecutorService scheduler, Executor callbacks, Clock clock,
                     SubmitListener listener, CloseListener closeListener) {
        this(mode, address, basePort, portTries, ttl, scheduler, callbacks, clock, listener,
                closeListener, new ModelsLoader() {
                    @Override public AiModelsClient.Catalog load(AiProvider provider, String key, CancelScope scope)
                            throws IOException { return new AiModelsClient().fetch(provider, key, scope); }
                });
    }

    ConfigHttpServer(Mode mode, InetAddress address, int basePort, int portTries, long ttl,
                     ScheduledExecutorService scheduler, Executor callbacks, Clock clock,
                     SubmitListener listener, CloseListener closeListener, ModelsLoader modelsLoader) {
        this.mode = mode; this.address = address; this.basePort = basePort; this.portTries = portTries;
        this.ttl = ttl; this.scheduler = scheduler; this.callbacks = callbacks; this.clock = clock;
        this.listener = listener; this.closeListener = closeListener;
        this.modelsLoader = modelsLoader;
    }

    public Mode mode() { return mode; }
    public int port() { return port; }
    public String token() { return token; }
    public boolean isRunning() { return running.get(); }

    /** A stopped session is never reused; callers create a fresh token/session. */
    public synchronized boolean start() {
        if (closed) return false;
        if (running.get()) return true;
        for (int i = 0; i < portTries; i++) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(address, basePort + i));
                serverSocket = candidate;
                port = candidate.getLocalPort();
                break;
            } catch (IOException error) { close(candidate); }
        }
        if (serverSocket == null) { finish(CloseReason.CLOSED); return false; }
        token = randomToken();
        deadline = clock.now() + ttl;
        running.set(true);
        final ServerSocket listening = serverSocket;
        expiry = scheduler.schedule(new Runnable() {
            @Override public void run() { finish(CloseReason.EXPIRED); }
        }, ttl, TimeUnit.MILLISECONDS);
        executor.execute(new Runnable() {
            @Override public void run() { acceptLoop(listening); }
        });
        return true;
    }

    private void acceptLoop(ServerSocket listening) {
        while (running.get()) {
            Socket socket = null;
            try {
                socket = listening.accept();
                synchronized (this) {
                    if (!running.get()) { close(socket); break; }
                    activeSockets.add(socket);
                }
                socket.setSoTimeout(8000);
                final Socket client = socket;
                requests.execute(new Runnable() {
                    @Override public void run() {
                        try { handle(client); }
                        finally { close(client); synchronized (ConfigHttpServer.this) { activeSockets.remove(client); } }
                    }
                });
                socket = null; // Ownership transferred to the bounded request executor.
            } catch (SocketException error) {
                if (!running.get()) break;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // A full queue is closed rather than allocating unbounded clients/threads.
            } catch (IOException ignored) {
                // A broken client connection must not terminate the whole session.
            } finally {
                close(socket);
                if (socket != null) synchronized (this) { activeSockets.remove(socket); }
            }
        }
    }

    private synchronized boolean validNow() { return running.get() && token != null && clock.now() < deadline; }

    private void handle(Socket socket) {
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String request = line(input);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length != 3) { reject(socket, 400, "Bad Request", "请求格式不正确"); return; }
            int contentLength = 0, headerBytes = 0;
            boolean lengthSeen = false;
            String header;
            while ((header = line(input)) != null && !header.isEmpty()) {
                headerBytes += header.length();
                if (headerBytes > AppConstants.CONFIG_MAX_REQUEST_BYTES) throw new BadRequest();
                int split = header.indexOf(':');
                if (split < 1) throw new BadRequest();
                String name = header.substring(0, split).trim();
                if ("content-length".equalsIgnoreCase(name)) {
                    if (lengthSeen) throw new BadRequest();
                    lengthSeen = true;
                    try { contentLength = Integer.parseInt(header.substring(split + 1).trim()); }
                    catch (NumberFormatException error) { throw new BadRequest(); }
                } else if ("transfer-encoding".equalsIgnoreCase(name)) throw new BadRequest();
            }
            if (header == null || contentLength < 0) throw new BadRequest();
            if (contentLength > AppConstants.CONFIG_MAX_REQUEST_BYTES) {
                reject(socket, 413, "Payload Too Large", "提交内容过长"); return;
            }
            boolean modelsRequest = parts[1].endsWith("/models");
            String suppliedToken = extractToken(modelsRequest
                    ? parts[1].substring(0, parts[1].length() - 7) : parts[1]);
            boolean tokenValid;
            synchronized (this) {
                tokenValid = validNow() && token.equals(suppliedToken);
            }
            if (!tokenValid) {
                reject(socket, 403, "Forbidden", "会话已过期或无效，请在电视端重新生成二维码");
                return;
            }
            if (modelsRequest && (mode != Mode.AI || !"POST".equals(parts[0]))) {
                respond(socket, 404, "Not Found", "application/json; charset=utf-8",
                        new com.google.gson.Gson().toJson(AiModelsClient.Catalog.error("此会话不支持模型查询")));
                return;
            }
            if ("GET".equals(parts[0])) {
                respond(socket, 200, "OK", "text/html; charset=utf-8", formHtml(suppliedToken));
            } else if ("POST".equals(parts[0])) {
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < body.length) {
                    int count = input.read(body, read, body.length - read);
                    if (count < 0) throw new BadRequest();
                    read += count;
                }
                Map<String, String> fields = parseForm(new String(body, "UTF-8"));
                if (modelsRequest) {
                    loadModels(socket, fields);
                    return;
                }
                if (!validate(fields)) {
                    reject(socket, 400, "Bad Request", "字段不合法：请检查名称、URL 或模型"); return;
                }
                submit(socket, fields);
            } else reject(socket, 405, "Method Not Allowed", "不支持此请求");
        } catch (BadRequest | IllegalArgumentException error) {
            try { reject(socket, 400, "Bad Request", "请求格式不正确"); } catch (IOException ignored) { }
        } catch (IOException ignored) { }
    }

    private void loadModels(Socket socket, Map<String, String> fields) throws IOException {
        AiProvider provider = null;
        for (AiProvider candidate : SettingsRepository.AI_PROVIDERS) {
            if (candidate.id.equals(fields.get("provider"))) provider = candidate;
        }
        String key = fields.get("apiKey");
        if (provider == null || !AiProvider.validApiKey(key)) {
            respond(socket, 400, "Bad Request", "application/json; charset=utf-8",
                    new com.google.gson.Gson().toJson(AiModelsClient.Catalog.error("请检查提供方和 API Key")));
            return;
        }
        CancelScope scope = new CancelScope();
        synchronized (this) {
            if (!validNow()) return;
            if (modelScope != null) modelScope.cancel();
            modelScope = scope;
        }
        try {
            AiModelsClient.Catalog catalog;
            try { catalog = modelsLoader.load(provider, key.trim(), scope); }
            catch (IOException | RuntimeException error) {
                catalog = AiModelsClient.presets(provider.id, "模型列表获取失败");
            }
            if (scope.isCancelled() || !validNow()) return;
            respond(socket, 200, "OK", "application/json; charset=utf-8",
                    new com.google.gson.Gson().toJson(catalog));
        } finally {
            scope.cancel();
            synchronized (this) { if (modelScope == scope) modelScope = null; }
        }
    }

    private void submit(Socket socket, final Map<String, String> fields) throws IOException {
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicBoolean pending = new AtomicBoolean(true);
        final boolean[] saved = new boolean[1];
        callbacks.execute(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (ConfigHttpServer.this) {
                        if (!pending.get() || !validNow()) return;
                        saved[0] = listener.onSubmit(ConfigHttpServer.this, fields);
                        if (saved[0]) token = null;
                    }
                } catch (RuntimeException ignored) {
                    // A storage/validation error is returned without echoing submitted fields.
                } finally { delivered.countDown(); }
            }
        });
        try {
            if (!delivered.await(5, TimeUnit.SECONDS)) {
                pending.set(false);
                if (running.get()) reject(socket, 504, "Gateway Timeout", "保存超时，请重新提交");
                return;
            }
        } catch (InterruptedException error) {
            pending.set(false);
            Thread.currentThread().interrupt();
            return;
        }
        pending.set(false);
        if (!running.get()) return;
        if (saved[0]) {
            try {
                respond(socket, 200, "OK", "text/html; charset=utf-8",
                        resultHtml(true, "配置已保存，电视端会话已关闭，可以关闭本页"));
            } finally { finish(CloseReason.SAVED); }
        } else reject(socket, 500, "Error", "保存失败，请重试");
    }

    private void reject(Socket socket, int status, String reason, String message) throws IOException {
        boolean exhausted;
        synchronized (this) { exhausted = ++failures >= AppConstants.CONFIG_MAX_TOKEN_FAILURES; }
        try { respond(socket, status, reason, "text/html; charset=utf-8", resultHtml(false, message)); }
        finally { if (exhausted) finish(CloseReason.FAILURES); }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') break;
            if (bytes.size() >= AppConstants.CONFIG_MAX_REQUEST_BYTES) throw new BadRequest();
            bytes.write(value);
        }
        if (value < 0 && bytes.size() == 0) return null;
        return new String(bytes.toByteArray(), "UTF-8").replace("\r", "");
    }

    private static final class BadRequest extends IOException { }

    private static String extractToken(String path) {
        if (path == null) {
            return null;
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.isEmpty() ? null : path;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (IOException ignored) {
            }
        }
        return map;
    }

    private boolean validate(Map<String, String> fields) {
        if (mode == Mode.AI) {
            String provider = fields.get("provider");
            String model = fields.get("model");
            String key = fields.get("apiKey");
            if (provider == null || model == null || key == null) {
                return false;
            }
            boolean known = false;
            for (com.tvbox.android44.domain.model.AiProvider p : SettingsRepository.AI_PROVIDERS) {
                if (p.id.equals(provider)) {
                    known = true;
                    break;
                }
            }
            return known && !model.trim().isEmpty() && AiProvider.validApiKey(key);
        }
        String name = fields.get("name");
        String url = fields.get("baseUrl");
        return name != null && !name.trim().isEmpty()
                && url != null && SettingsRepository.isValidBaseUrl(url.trim());
    }

    private static void respond(Socket socket, int code, String reason, String contentType,
                                String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private String formHtml(String token) {
        String action = "/" + token;
        if (mode == Mode.AI) {
            return AiConfigPage.render(token);
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>TVBox4.1+ 视频接口配置</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 16px\">"
                + "<h2>TVBox4.1+ · 自定义视频接口</h2>"
                + "<form method=\"POST\" action=\"" + action + "\">"
                + "<p>接口名称：<br><input name=\"name\" style=\"width:100%;padding:8px\" "
                + "placeholder=\"例如 我的资源站\"></p>"
                + "<p>MacCMS 地址：<br><input name=\"baseUrl\" style=\"width:100%;padding:8px\" "
                + "placeholder=\"https://example.com/api.php/provide/vod/\"></p>"
                + "<p><button type=\"submit\" style=\"padding:10px 28px\">保存接口</button></p>"
                + "</form>"
                + "<p style=\"color:#888;font-size:12px\">地址必须以 http/https 开头，"
                + "保存时会自动规范化末尾斜杠。</p>"
                + "</body></html>";
    }

    private static String resultHtml(boolean ok, String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>配置结果</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:480px;margin:60px auto;"
                + "text-align:center;padding:0 16px\">"
                + "<h2 style=\"color:" + (ok ? "#00875a" : "#c0392b") + "\">"
                + (ok ? "✅ " : "❌ ") + message + "</h2>"
                + "</body></html>";
    }

    private static String randomToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[9];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }


    public void stop() { finish(CloseReason.CLOSED); }

    private synchronized void finish(final CloseReason reason) {
        if (closed) return;
        closed = true;
        running.set(false);
        token = null;
        if (expiry != null) expiry.cancel(false);
        if (modelScope != null) modelScope.cancel();
        close(serverSocket);
        for (Socket client : activeSockets) close(client);
        activeSockets.clear();
        serverSocket = null;
        executor.shutdownNow();
        requests.shutdownNow();
        if (closeListener != null) callbacks.execute(new Runnable() {
            @Override public void run() { closeListener.onClosed(ConfigHttpServer.this, reason); }
        });
    }

    // Socket/ServerSocket only implement Closeable from API 19 onward.
    private static void close(java.net.ServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static void close(java.net.Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
