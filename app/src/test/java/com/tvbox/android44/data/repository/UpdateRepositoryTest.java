package com.tvbox.android44.data.repository;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.data.remote.HttpExecutor;
import com.tvbox.android44.data.remote.OtaClient;
import com.tvbox.android44.domain.model.AppUpdate;
import com.tvbox.android44.testutil.AsyncTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 28}, application = TvBoxApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateRepositoryTest {
    private MockWebServer server;
    private ExecutorService executor;
    private Context context;
    private UpdateRepository repo;
    private final byte[] payload = "verified test APK payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        server = new MockWebServer();
        server.start();
        executor = Executors.newSingleThreadExecutor();
        repo = new UpdateRepository(executor, context, new OtaClient(), server.url("/update.json").toString());
    }

    @After public void tearDown() throws Exception {
        executor.shutdownNow();
        TvBoxApp.get().executors().shutdown();
        server.shutdown();
    }

    private AppUpdate update() {
        AppUpdate u = new AppUpdate();
        u.versionCode = com.tvbox.android44.BuildConfig.VERSION_CODE + 1;
        u.apkUrl = server.url("/app.apk").toString();
        u.apkSha256 = HttpExecutor.sha256Hex(payload);
        u.apkSize = payload.length;
        return u;
    }

    @Test public void validManifestAndDownloadProduceContentInstallIntent() throws Exception {
        AppUpdate u = update();
        server.enqueue(new MockResponse().setBody("{\"versionCode\":" + u.versionCode + ",\"versionName\":\"0.0.2\",\"apkUrl\":\""
                + u.apkUrl + "\",\"apkSha256\":\"" + u.apkSha256 + "\",\"apkSize\":" + u.apkSize + "}"));
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Result<AppUpdate>> check = new AtomicReference<>();
        repo.check(r -> { check.set(r); checked.countDown(); });
        AsyncTest.await(checked);
        assertTrue(check.get().isSuccess());
        server.enqueue(new MockResponse().setBody(new Buffer().write(payload)));
        CountDownLatch downloaded = new CountDownLatch(1);
        AtomicReference<Result<File>> result = new AtomicReference<>();
        repo.download(check.get().data(), new UpdateRepository.DownloadCallback() {
            public void onProgress(long done, long total) { assertTrue(done <= total); }
            public void onDone(Result<File> r) { result.set(r); downloaded.countDown(); }
        });
        AsyncTest.await(downloaded);
        assertTrue(result.get().isSuccess());
        Intent install = repo.buildInstallIntent(result.get().data());
        assertEquals("content", install.getData().getScheme());
        assertEquals("application/vnd.android-package-archive", install.getType());
        assertTrue((install.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        String[] permissions = context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertTrue(java.util.Arrays.asList(permissions).contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        if (Build.VERSION.SDK_INT < 26) {
            assertTrue(repo.canRequestInstalls());
            assertNull(repo.buildInstallsPermissionIntent());
        } else {
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    repo.buildInstallsPermissionIntent().getAction());
        }
    }

    @Test public void hashAndSizeFailureLeaveNoPartialPackage() throws Exception {
        for (boolean hashFailure : new boolean[]{true, false}) {
            File dir = new File(context.getCacheDir(), "failed-" + hashFailure);
            AppUpdate u = update();
            if (hashFailure) u.apkSha256 = new String(new char[64]).replace('\0', '0');
            else u.apkSize++;
            server.enqueue(new MockResponse().setBody(new Buffer().write(payload)));
            try {
                new OtaClient().downloadVerified(u, dir, null, new CancelScope());
                fail("unverified APK must not become installable");
            } catch (OtaClient.VerificationException expected) { }
            assertEquals(0, dir.listFiles().length);
        }
    }

    @Test public void httpFailureAndCancellationCleanPartialDownload() throws Exception {
        File dir = new File(context.getCacheDir(), "http-failed");
        server.enqueue(new MockResponse().setResponseCode(500));
        try { new OtaClient().downloadVerified(update(), dir, null, new CancelScope()); fail(); }
        catch (IOException expected) { }
        assertEquals(0, dir.listFiles().length);
        CancelScope scope = new CancelScope();
        scope.cancel();
        try { new OtaClient().downloadVerified(update(), dir, null, scope); fail(); }
        catch (InterruptedIOException expected) { }
        assertEquals(0, dir.listFiles().length);
    }

    @Test public void cancelAfterWorkerFinishesSuppressesQueuedCallbacks() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"versionCode\":" + update().versionCode + ",\"versionName\":\"0.0.2\",\"apkUrl\":\""
                + update().apkUrl + "\",\"apkSha256\":\"" + update().apkSha256 + "\"}"));
        AtomicReference<Result<AppUpdate>> result = new AtomicReference<>();
        UpdateRepository.CheckHandle handle = repo.check(result::set);
        executor.submit(() -> {}).get(8, TimeUnit.SECONDS);
        handle.cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(result.get());
    }

    @Test public void cancelWhileStreamingRemovesPartialPackage() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(payload))
                .throttleBody(1, 100, TimeUnit.MILLISECONDS));
        File dir = new File(context.getCacheDir(), "stream-cancelled");
        CancelScope scope = new CancelScope();
        try {
            new OtaClient().downloadVerified(update(), dir, (done, total) -> {
                if (done > 0) scope.cancel();
            }, scope);
            fail("cancelled download must not become installable");
        } catch (IOException expected) { }
        assertTrue(scope.isCancelled());
        assertEquals(0, dir.listFiles().length);
    }
}
