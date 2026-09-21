package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** OTA 清单检查与 APK 下载。 */
public class OtaClient {

    public interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    public String fetchManifest(String url, @Nullable CancelScope scope) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build();
        return HttpExecutor.executeForString(HttpClients.withTimeout(15000L), request, scope);
    }

    /**
     * 流式下载 APK 到临时文件并计算 SHA-256；返回十六进制小写哈希。
     */
    public String downloadApk(String url, File target, @Nullable DownloadProgress progress,
                              @Nullable CancelScope scope) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        Response response = HttpExecutor.executeForStream(
                HttpClients.withTimeout(0L), request, scope);
        try {
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("EMPTY_BODY");
            }
            long total = body.contentLength();
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 不可用");
            }
            InputStream in = body.byteStream();
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            long done = 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (progress != null) {
                progress.onProgress(done, total);
            }
            return HttpExecutor.toHex(md.digest());
        } finally {
            response.close();
        }
    }
}
