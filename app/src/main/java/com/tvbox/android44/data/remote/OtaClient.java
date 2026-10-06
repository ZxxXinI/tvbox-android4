package com.tvbox.android44.data.remote;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.io.InterruptedIOException;
import com.tvbox.android44.domain.model.AppUpdate;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** OTA 清单检查与 APK 下载。 */
public class OtaClient {

    public static class VerificationException extends IOException {
        public VerificationException(String message) { super(message); }
    }

    public File downloadVerified(AppUpdate update, File dir, DownloadProgress progress,
                                 CancelScope scope) throws IOException {
        if (update.apkSha256 == null || !update.apkSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new VerificationException("更新清单缺少有效 SHA-256，已取消安装");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        if (update.apkSize > 0 && dir.getUsableSpace() > 0
                && dir.getUsableSpace() < update.apkSize) throw new IOException("可用存储空间不足");
        File tmp = File.createTempFile("update-", ".apk.tmp", dir);
        try {
            if (scope != null && scope.isCancelled()) throw new InterruptedIOException();
            String sha = downloadApk(update.apkUrl, tmp, progress, scope);
            if (scope != null && scope.isCancelled()) throw new InterruptedIOException();
            if (update.apkSize > 0 && tmp.length() != update.apkSize) {
                throw new VerificationException("安装包大小校验失败，已取消安装");
            }
            if (!sha.equalsIgnoreCase(update.apkSha256)) {
                throw new VerificationException("SHA-256 校验失败，已取消安装");
            }
            File destination = new File(dir, "update-" + update.versionCode + ".apk");
            if (!tmp.renameTo(destination)) throw new IOException("保存安装包失败");
            return destination;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

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
