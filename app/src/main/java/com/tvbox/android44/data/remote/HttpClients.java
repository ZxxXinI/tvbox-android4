package com.tvbox.android44.data.remote;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.AppExecutors;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionSpec;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 统一 HTTP 入口：OkHttp 3.12 冻结分支（API 9+，自带 API19 TLS1.2 兼容处理）。
 * 禁止在别处再建 HttpURLConnection 或混入 OkHttp 4/5。
 */
public final class HttpClients {

    public static final String DEFAULT_UA = "okhttp/3.12.13";
    /** 豆瓣系域名需浏览器 UA + Referer（API 与图片 CDN 均校验）。 */
    private static final String DOUBAN_UA =
            "Mozilla/5.0 (Linux; Android 4.4.4) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/33.0.0.0 Mobile Safari/537.36";

    private static OkHttpClient base;

    private HttpClients() {
    }

    public static void init(AppExecutors executors) {
        okhttp3.OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionSpecs(java.util.Arrays.<ConnectionSpec>asList(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS,
                        ConnectionSpec.CLEARTEXT))
                .connectTimeout(AppConstants.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(AppConstants.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(AppConstants.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        // Android 4.x 默认不启用 TLSv1.2，显式开启（不改信任策略）。
        // 必须用三参数 sslSocketFactory(factory, trustManager)：单参数重载经反射
        // 提取 TrustManager，在 R8 混淆后失败（IllegalStateException）。
        javax.net.ssl.SSLSocketFactory tls12 = Tls12SocketFactory.createIfNecessary(
                android.os.Build.VERSION.SDK_INT);
        if (tls12 != null) {
            javax.net.ssl.X509TrustManager trustManager = systemDefaultTrustManager();
            if (trustManager != null) {
                builder.sslSocketFactory(tls12, trustManager);
            }
        }
        base = builder
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request r = chain.request();
                        String host = r.url().host();
                        boolean douban = host.contains("douban");
                        if (douban && r.header("Referer") == null) {
                            r = r.newBuilder()
                                    .header("Referer", "https://m.douban.com/")
                                    .header("Origin", "https://m.douban.com")
                                    .build();
                        }
                        if (r.header("User-Agent") == null) {
                            r = r.newBuilder()
                                    .header("User-Agent", douban ? DOUBAN_UA : DEFAULT_UA)
                                    .build();
                        }
                        return chain.proceed(r);
                    }
                })
                .build();
    }

    /** 系统默认信任锚（仅用于配合自定义 SSLSocketFactory，校验行为不变）。 */
    private static javax.net.ssl.X509TrustManager systemDefaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager) {
                    return (javax.net.ssl.X509TrustManager) tm;
                }
            }
        } catch (Exception ignored) {
            // 获取失败时调用方回退系统默认 SSLSocketFactory
        }
        return null;
    }

    /** 共享连接池与分发器的派生客户端（自定义整体超时）。 */
    public static OkHttpClient withTimeout(long timeoutMs) {
        return base.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public static OkHttpClient client() {
        return base;
    }
}
