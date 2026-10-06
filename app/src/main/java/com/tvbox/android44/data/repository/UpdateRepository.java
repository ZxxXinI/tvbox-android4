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
