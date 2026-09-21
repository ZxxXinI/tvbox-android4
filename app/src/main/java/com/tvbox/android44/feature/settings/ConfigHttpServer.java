package com.tvbox.android44.feature.settings;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.data.local.SettingsRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网临时配置服务（文档 09 §5）：
 * - 只在配置弹窗打开时运行；关闭/超时/完成必须关闭 ServerSocket。
 * - 一次性随机 token，短有效期、失败次数过多即失效。
 * - HTTP 响应 no-store；不回显完整 API Key。
 * - 手机提交成功后回调主线程保存并关闭会话。
 */
public class ConfigHttpServer {

    public enum Mode {AI, API}

    public interface SubmitListener {
        /** 主线程回调；返回 true 表示保存成功（会话关闭）。 */
        boolean onSubmit(Map<String, String> fields);
    }

    private final Mode mode;
    private final SubmitListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tvbox-config-http");
                    t.setDaemon(true);
                    return t;
                }
            });
    private ServerSocket serverSocket;
    private int port = -1;
    private String token;
    private long tokenExpireAt;
    private int failures;

    public ConfigHttpServer(Mode mode, SubmitListener listener) {
        this.mode = mode;
        this.listener = listener;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    /** 启动；失败返回 false（端口占用且无临近空闲端口）。 */
    public synchronized boolean start() {
        if (running.get()) {
            return true;
        }
        int base = mode == Mode.AI ? AppConstants.CONFIG_PORT_AI : AppConstants.CONFIG_PORT_API;
        for (int i = 0; i < AppConstants.CONFIG_PORT_FALLBACK_TRIES; i++) {
            try {
                serverSocket = new ServerSocket(base + i);
                port = base + i;
                break;
            } catch (IOException e) {
                serverSocket = null;
            }
        }
        if (serverSocket == null) {
            return false;
        }
        token = randomToken();
        tokenExpireAt = System.currentTimeMillis() + AppConstants.CONFIG_TOKEN_TTL_MS;
        failures = 0;
        running.set(true);
        executor.submit(acceptLoop);
        return true;
    }

    private final Runnable acceptLoop = new Runnable() {
        @Override
        public void run() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(8000);
                    handle(socket);
                } catch (SocketException e) {
                    // serverSocket closed
                } catch (IOException e) {
                    if (running.get()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
    };

    private void handle(Socket socket) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(socket, 400, "Bad Request", "text/plain; charset=utf-8", "400");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            // 读取头（Content-Length 用）
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(idx + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // token 校验
            String tokenFromPath = extractToken(path);
            boolean tokenValid = token != null && token.equals(tokenFromPath)
                    && System.currentTimeMillis() <= tokenExpireAt;
            if (!tokenValid) {
                failures++;
                if (failures >= AppConstants.CONFIG_MAX_TOKEN_FAILURES) {
                    // 失败次数过多：使会话失效
                    stop();
                }
                respond(socket, 403, "Forbidden", "text/plain; charset=utf-8",
                        "会话已过期或无效，请在电视端重新生成二维码");
                return;
            }
            if ("GET".equals(method)) {
                respond(socket, 200, "OK", "text/html; charset=utf-8",
                        formHtml(tokenFromPath));
            } else if ("POST".equals(method)) {
                StringBuilder body = new StringBuilder();
                int read = 0;
                char[] buf = new char[2048];
                while (read < contentLength) {
                    int n = reader.read(buf, 0, Math.min(buf.length, contentLength - read));
                    if (n < 0) {
                        break;
                    }
                    read += n;
                    body.append(buf, 0, n);
                }
                final Map<String, String> fields = parseForm(body.toString());
                if (!validate(fields)) {
                    failures++;
                    respond(socket, 400, "Bad Request", "text/html; charset=utf-8",
                            resultHtml(false, "字段不合法：请检查名称/URL/模型是否填写正确"));
                    return;
                }
                final boolean[] ok = new boolean[1];
                final Object lock = new Object();
                TvBoxApp.get().executors().main(new Runnable() {
                    @Override
                    public void run() {
                        ok[0] = listener.onSubmit(fields);
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                });
                synchronized (lock) {
                    try {
                        lock.wait(5000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (ok[0]) {
                    // 一次性 token：成功后主动关闭会话
                    respond(socket, 200, "OK", "text/html; charset=utf-8",
                            resultHtml(true, "配置已保存，电视端会话已关闭，可以关闭本页"));
                    stop();
                } else {
                    respond(socket, 500, "Error", "text/html; charset=utf-8",
                            resultHtml(false, "保存失败，请重试"));
                }
            } else {
                respond(socket, 405, "Method Not Allowed", "text/plain; charset=utf-8", "405");
            }
        } catch (IOException ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

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
            return known && !model.trim().isEmpty() && !key.trim().isEmpty();
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
            StringBuilder options = new StringBuilder();
            for (com.tvbox.android44.domain.model.AiProvider p : SettingsRepository.AI_PROVIDERS) {
                options.append("<option value=\"").append(p.id).append("\">")
                        .append(p.name).append("</option>");
            }
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>TVBox AI 配置</title></head>"
                    + "<body style=\"font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 16px\">"
                    + "<h2>TVBox 4.4 · AI 配置</h2>"
                    + "<form method=\"POST\" action=\"" + action + "\">"
                    + "<p>提供方：<br><select name=\"provider\" style=\"width:100%;padding:8px\">"
                    + options.toString() + "</select></p>"
                    + "<p>模型名：<br><input name=\"model\" style=\"width:100%;padding:8px\" "
                    + "placeholder=\"例如 deepseek-chat\"></p>"
                    + "<p>API Key：<br><input name=\"apiKey\" type=\"password\" "
                    + "style=\"width:100%;padding:8px\" placeholder=\"只用于本次保存\"></p>"
                    + "<p><button type=\"submit\" style=\"padding:10px 28px\">保存配置</button></p>"
                    + "</form>"
                    + "<p style=\"color:#888;font-size:12px\">本页面仅在本局域网会话期间有效，"
                    + "提交成功后服务自动关闭；Key 只保存在电视端应用私有存储。</p>"
                    + "</body></html>";
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>TVBox 视频接口配置</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 16px\">"
                + "<h2>TVBox 4.4 · 自定义视频接口</h2>"
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

    public boolean isRunning() {
        return running.get();
    }

    /** 必须显式关闭（弹窗关闭 / Activity 停止 / 超时）。 */
    public synchronized void stop() {
        running.set(false);
        executor.shutdownNow();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }
}
