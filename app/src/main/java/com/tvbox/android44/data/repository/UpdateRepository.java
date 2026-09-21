package com.tvbox.android44.data.repository;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

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
    private final OtaClient client = new OtaClient();
    private final Context context;

    public UpdateRepository(ExecutorService executor, Context context) {
        this.executor = executor;
        this.context = context;
    }

    public String manifestUrl() {
        return BuildConfig.OTA_MANIFEST_URL;
    }

    public CheckHandle check(final CheckCallback cb) {
        final String url = manifestUrl();
        final CancelScope scope = new CancelScope();
        if (url == null || url.isEmpty()) {
            deliverCheck(cb, new Result.Failure<AppUpdate>(
                    ErrorKind.OTHER, "未配置更新清单地址", null));
            return new CheckHandle(null, null);
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
        executor.submit(task);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    deliverCheck(cb, task.get());
                } catch (Exception ignored) {
                }
            }
        });
        return new CheckHandle(scope, task);
    }

    /** 下载 + 校验；失败删除临时 APK 并禁止安装。 */
    public CheckHandle download(final AppUpdate update, final DownloadCallback cb) {
        final CancelScope scope = new CancelScope();
        final FutureTask<Result<File>> task =
                new FutureTask<Result<File>>(new java.util.concurrent.Callable<Result<File>>() {
                    @Override
                    public Result<File> call() {
                        File dir = new File(context.getCacheDir(), "ota");
                        if (!dir.exists()) {
                            dir.mkdirs();
                        }
                        final File tmp = new File(dir, "update-" + update.versionCode + ".apk.tmp");
                        try {
                            String sha = client.downloadApk(update.apkUrl, tmp,
                                    new OtaClient.DownloadProgress() {
                                        @Override
                                        public void onProgress(final long done, final long total) {
                                            TvBoxApp.get().executors().main(new Runnable() {
                                                @Override
                                                public void run() {
                                                    cb.onProgress(done, total);
                                                }
                                            });
                                        }
                                    }, scope);
                            if (scope.isCancelled()) {
                                tmp.delete();
                                return cancelled();
                            }
                            if (update.apkSize > 0 && tmp.length() != update.apkSize) {
                                tmp.delete();
                                return new Result.Failure<File>(ErrorKind.PARSE,
                                        "安装包大小校验失败，已取消安装", null);
                            }
                            if (!update.apkSha256.isEmpty()
                                    && !sha.equalsIgnoreCase(update.apkSha256)) {
                                tmp.delete();
                                return new Result.Failure<File>(ErrorKind.PARSE,
                                        "SHA-256 校验失败，已取消安装", null);
                            }
                            File dest = new File(dir, "update-" + update.versionCode + ".apk");
                            if (dest.exists()) {
                                dest.delete();
                            }
                            if (!tmp.renameTo(dest)) {
                                tmp.delete();
                                return new Result.Failure<File>(ErrorKind.OTHER, "保存安装包失败", null);
                            }
                            return new Result.Success<File>(dest);
                        } catch (Exception e) {
                            tmp.delete();
                            if (scope.isCancelled()) {
                                return cancelled();
                            }
                            ErrorKind kind = ErrorKind.fromException(e);
                            return new Result.Failure<File>(kind, "下载失败：" + kind.userMessage(), e);
                        }
                    }
                });
        executor.submit(task);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final Result<File> r = task.get();
                    TvBoxApp.get().executors().main(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDone(r);
                        }
                    });
                } catch (Exception ignored) {
                }
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

    /** FileProvider content URI + 系统安装器。 */
    public Intent buildInstallIntent(File apk) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android-package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> cancelled() {
        return (Result<T>) Result.Cancelled.INSTANCE;
    }

    private static void deliverCheck(final CheckCallback cb, final Result<AppUpdate> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
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
