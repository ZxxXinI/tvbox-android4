package com.tvbox.android44.data.repository;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.OtaClient;
import com.tvbox.android44.domain.model.AppUpdate;
import com.tvbox.android44.domain.parser.OtaManifestParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;

/**
 * OTA 更新仓库：清单比较（远端 versionCode > 当前才提示）、
 * 下载进度、大小/SHA-256 校验、FileProvider + 系统安装器。
 */
public class UpdateRepository {

    public interface CheckCallback {
        void onResult(Result<AppUpdate> result);
    }

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onDone(Result<File> result);
    }

    private final ExecutorService executor;
    private final OtaClient client;
    private final String manifestUrl;
    private final Context context;

    public UpdateRepository(ExecutorService executor, Context context) {
        this(executor, context, new OtaClient(), BuildConfig.OTA_MANIFEST_URL);
    }

    UpdateRepository(ExecutorService executor, Context context, OtaClient client, String manifestUrl) {
        this.executor = executor;
        this.context = context.getApplicationContext();
        this.client = client;
        this.manifestUrl = manifestUrl;
    }

    public String manifestUrl() {
        return manifestUrl;
    }

    public CheckHandle check(final CheckCallback cb) {
        final String url = manifestUrl();
        final CancelScope scope = new CancelScope();
        if (url == null || url.isEmpty()) {
            TvBoxApp.get().executors().main(new Runnable() {
                public void run() {
                    if (!scope.isCancelled()) cb.onResult(new Result.Failure<AppUpdate>(
                            ErrorKind.OTHER, "未配置更新清单地址", null));
                }
            });
            return new CheckHandle(scope, null);
        }
        final FutureTask<Result<AppUpdate>> task =
                new FutureTask<Result<AppUpdate>>(new java.util.concurrent.Callable<Result<AppUpdate>>() {
                    @Override
                    public Result<AppUpdate> call() {
                        try {
                            String json = client.fetchManifest(url, scope);
                            AppUpdate u = OtaManifestParser.parse(json);
                            if (u.versionCode <= BuildConfig.VERSION_CODE) {
                                return new Result.Failure<AppUpdate>(
                                        ErrorKind.OTHER, "当前已是最新版本", null);
                            }
                            return new Result.Success<AppUpdate>(u);
                        } catch (OtaManifestParser.ManifestException e) {
                            return new Result.Failure<AppUpdate>(ErrorKind.PARSE,
                                    "更新清单格式错误：" + e.getMessage(), null);
                        } catch (Exception e) {
                            if (scope.isCancelled()) {
                                return cancelled();
                            }
                            ErrorKind kind = ErrorKind.fromException(e);
                            return new Result.Failure<AppUpdate>(kind, kind.userMessage(), e);
                        }
                    }
                });
        return execute(scope, task, new Completion<AppUpdate>() {
            public void onResult(Result<AppUpdate> result) { cb.onResult(result); }
        });
    }

    /** Only a verified package can become an installable result. */
    public CheckHandle download(final AppUpdate update, final DownloadCallback cb) {
        final CancelScope scope = new CancelScope();
        FutureTask<Result<File>> task = new FutureTask<Result<File>>(new Callable<Result<File>>() {
            public Result<File> call() {
                try {
                    File apk = client.downloadVerified(update, new File(context.getCacheDir(), "ota"),
                            new OtaClient.DownloadProgress() {
                                public void onProgress(final long done, final long total) {
                                    TvBoxApp.get().executors().main(new Runnable() {
                                        public void run() {
                                            if (!scope.isCancelled()) cb.onProgress(done, total);
                                        }
                                    });
                                }
                            }, scope);
                    return new Result.Success<File>(apk);
                } catch (Exception e) {
                    if (scope.isCancelled()) return cancelled();
                    if (e instanceof OtaClient.VerificationException) {
                        return new Result.Failure<File>(ErrorKind.PARSE, e.getMessage(), null);
                    }
                    ErrorKind kind = ErrorKind.fromException(e);
                    return new Result.Failure<File>(kind, "下载失败：" + kind.userMessage(), e);
                }
            }
        });
        return execute(scope, task, new Completion<File>() {
            public void onResult(Result<File> result) { cb.onDone(result); }
        });
    }

    private interface Completion<T> { void onResult(Result<T> result); }

    private <T> CheckHandle execute(final CancelScope scope, final FutureTask<Result<T>> task,
                                   final Completion<T> completion) {
        // Run and deliver on the same worker; never occupy another worker waiting on task.get().
        executor.execute(new Runnable() {
            public void run() {
                task.run();
                if (scope.isCancelled() || task.isCancelled()) return;
                Result<T> value;
                try {
                    value = task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (CancellationException e) {
                    return;
                } catch (ExecutionException e) {
                    value = new Result.Failure<T>(ErrorKind.OTHER, "更新操作失败，请重试", e.getCause());
                }
                final Result<T> result = value;
                TvBoxApp.get().executors().main(new Runnable() {
                    public void run() {
                        if (!scope.isCancelled()) completion.onResult(result);
                    }
                });
            }
        });
        return new CheckHandle(scope, task);
    }

    /** 是否需要先授予“安装未知应用”权限（API 26+ 分支）。 */
    public boolean canRequestInstalls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 打开未知来源安装权限设置（API 26+）。 */
    public Intent buildInstallsPermissionIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
        }
        return null;
    }

    /** FileProvider content URI + 安装专用系统安装器入口。 */
    @SuppressWarnings("deprecation")
    public Intent buildInstallIntent(File apk) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, "application/vnd.android-package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** 保存已校验安装包到公开 Download/TVBox，方便用户通过文件管理器手动安装。 */
    public String saveApkToDownloads(File apk) throws IOException {
        if (apk == null || !apk.isFile()) {
            throw new IOException("安装包不存在");
        }
        String displayName = savedApkName(apk);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveApkToMediaStore(apk, displayName);
        }
        return saveApkToLegacyDownloads(apk, displayName);
    }

    private String saveApkToMediaStore(File apk, String displayName) throws IOException {
        final String relativePath = Environment.DIRECTORY_DOWNLOADS + "/TVBox/";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(Uri.parse("content://media/external/downloads"), values);
        if (uri == null) {
            throw new IOException("无法在下载目录创建安装包");
        }
        boolean published = false;
        try {
            OutputStream output = resolver.openOutputStream(uri, "w");
            if (output == null) {
                throw new IOException("无法写入下载目录");
            }
            copyApk(apk, output);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw new IOException("无法完成保存的安装包");
            }
            published = true;
            return relativePath + displayName;
        } finally {
            if (!published) {
                resolver.delete(uri, null, null);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private String saveApkToLegacyDownloads(File apk, String displayName) throws IOException {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            throw new IOException("外部存储不可用");
        }
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "TVBox");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 Download/TVBox 目录");
        }
        File destination = uniqueFile(directory, displayName);
        File temporary = File.createTempFile("tvbox-ota-", ".tmp", directory);
        boolean saved = false;
        try {
            copyApk(apk, new FileOutputStream(temporary));
            if (!temporary.renameTo(destination)) {
                throw new IOException("无法保存到 Download/TVBox");
            }
            saved = true;
            MediaScannerConnection.scanFile(context,
                    new String[]{destination.getAbsolutePath()},
                    new String[]{"application/vnd.android.package-archive"}, null);
            return Environment.DIRECTORY_DOWNLOADS + "/TVBox/" + destination.getName();
        } finally {
            if (!saved && temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private String savedApkName(File apk) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date());
        return "TVBox4.1+-" + apk.getName().replace(".apk", "") + "-" + timestamp + ".apk";
    }

    private File uniqueFile(File directory, String desiredName) {
        File candidate = new File(directory, desiredName);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = desiredName.lastIndexOf('.');
        String base = dot > 0 ? desiredName.substring(0, dot) : desiredName;
        String extension = dot > 0 ? desiredName.substring(dot) : "";
        int suffix = 1;
        do {
            candidate = new File(directory, base + " (" + suffix++ + ")" + extension);
        } while (candidate.exists());
        return candidate;
    }

    private void copyApk(File source, OutputStream output) throws IOException {
        InputStream input = null;
        long copied = 0L;
        try {
            input = new FileInputStream(source);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }
            output.flush();
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } finally {
                output.close();
            }
        }
        if (copied != source.length()) {
            throw new IOException("保存后的安装包大小不一致");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> cancelled() {
        return (Result<T>) Result.Cancelled.INSTANCE;
    }

    public static final class CheckHandle {
        private final CancelScope scope;
        private final FutureTask<?> task;

        CheckHandle(CancelScope scope, FutureTask<?> task) {
            this.scope = scope;
            this.task = task;
        }

        public void cancel() {
            if (scope != null) {
                scope.cancel();
            }
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}
