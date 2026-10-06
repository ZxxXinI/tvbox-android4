package com.tvbox.android44.data.repository;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.OtaClient;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.testutil.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = StartupUpdateFlowTest.LocalApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StartupUpdateFlowTest {
    static class CountingUpdates extends UpdateRepository {
        int checks;
        final CountDownLatch done = new CountDownLatch(1);
        CountingUpdates(LocalApplication app, String url) { super(app.executors().network(), app, new OtaClient(), url); }
        @Override public CheckHandle check(CheckCallback callback) {
            checks++;
            return super.check(result -> { callback.onResult(result); done.countDown(); });
        }
    }
    public static class LocalApplication extends TvBoxApp {
        TestSettings localSettings;
        MovieRepository localMovies;
        CountingUpdates localUpdates;
        void configure(String source, String manifest) {
            localSettings = new TestSettings(new ApiLine("local", "测试源", source, false));
            localSettings.setCheckUpdateOnStart(true);
            localMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            localUpdates = new CountingUpdates(this, manifest);
        }
        @Override public SettingsRepository settings() { return localSettings == null ? super.settings() : localSettings; }
        @Override public MovieRepository movies() { return localMovies == null ? super.movies() : localMovies; }
        @Override public UpdateRepository updates() { return localUpdates == null ? super.updates() : localUpdates; }
    }
    private MockWebServer server;
    private LocalApplication app;
    private final List<ActivityController<MainActivity>> screens = new ArrayList<>();
    private boolean error, stalled;
    private final CountDownLatch received = new CountDownLatch(1);
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); app = (LocalApplication) RuntimeEnvironment.getApplication();
        app.configure(server.url("/api/").toString(), server.url("/update.json").toString());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/update.json")) {
                    received.countDown();
                    if (stalled) return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    if (error) return new MockResponse().setResponseCode(500);
                    return new MockResponse().setBody("{\"versionCode\":" + (BuildConfig.VERSION_CODE + 1)
                            + ",\"versionName\":\"fixture-version\",\"apkUrl\":\"" + server.url("/app.apk")
                            + "\",\"apkSha256\":\"" + String.join("", Collections.nCopies(64, "0")) + "\",\"changelog\":[\"fixture update\"]}");
                }
                return new MockResponse().setBody("{\"list\":[]}");
            }
        });
    }
    @After public void tearDown() throws Exception {
        for (ActivityController<MainActivity> screen : screens) screen.pause().stop().destroy();
        app.executors().shutdown(); server.shutdown();
    }
    private ActivityController<MainActivity> launch(Bundle saved) {
        ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        screens.add(screen); return screen;
    }
    @Test public void enabledStartupPromptsAndForwardsUpdateWithoutRequestingInstallation() throws Exception {
        ActivityController<MainActivity> screen = launch(null); AsyncTest.await(app.localUpdates.done);
        androidx.appcompat.app.AlertDialog prompt = ReflectionHelpers.getField(screen.get(), "startupPrompt");
        assertNotNull(prompt); assertEquals(1, app.localUpdates.checks);
        assertNull(Shadows.shadowOf(screen.get()).getNextStartedActivity());
        prompt.findViewById(R.id.dialog_ok).performClick();
        assertEquals(MainActivity.Tab.SETTINGS, ReflectionHelpers.getField(screen.get(), "current"));
        assertEquals(1, app.localUpdates.checks); assertNull(Shadows.shadowOf(screen.get()).getNextStartedActivity());
    }
    @Test public void recreationDoesNotRepeatCheckOrPrompt() throws Exception {
        ActivityController<MainActivity> first = launch(null); AsyncTest.await(app.localUpdates.done);
        Bundle saved = new Bundle(); first.saveInstanceState(saved).pause().stop().destroy(); screens.remove(first);
        ActivityController<MainActivity> second = launch(saved); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, app.localUpdates.checks); assertNull(ReflectionHelpers.getField(second.get(), "startupPrompt"));
    }
    @Test public void disabledSwitchAndEmptyUrlSkipRepositoryRequest() {
        app.settings().setCheckUpdateOnStart(false); launch(null); assertEquals(0, app.localUpdates.checks);
    }
    @Test public void emptyUrlSkipsRepositoryRequest() {
        app.configure(server.url("/api/").toString(), ""); launch(null); assertEquals(0, app.localUpdates.checks);
    }
    @Test public void networkFailureIsSilentAndHomeRemainsUsable() throws Exception {
        error = true; ActivityController<MainActivity> screen = launch(null); AsyncTest.await(app.localUpdates.done);
        assertNull(ReflectionHelpers.getField(screen.get(), "startupPrompt")); assertEquals(0, ShadowToast.shownToastCount());
        assertEquals(MainActivity.Tab.HOME, ReflectionHelpers.getField(screen.get(), "current"));
    }
    @Test public void leavingActivityCancelsInFlightCheckAndLateCallbacks() throws Exception {
        stalled = true; ActivityController<MainActivity> screen = launch(null); assertTrue(received.await(2, TimeUnit.SECONDS));
        screen.pause().stop(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ReflectionHelpers.getField(screen.get(), "startupPrompt")); assertEquals(1, app.localUpdates.done.getCount());
        assertTrue(app.startupUpdates().begin(true, "fixture", android.os.SystemClock.elapsedRealtime()) > 0);
    }
}
