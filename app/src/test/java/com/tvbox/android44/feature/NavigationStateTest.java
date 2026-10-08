package com.tvbox.android44.feature;

import android.os.Bundle;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.ui.*;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.*;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.feature.home.HomeFragment;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.feature.search.SearchFragment;
import com.tvbox.android44.testutil.TestSettings;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19}, application = NavigationStateTest.LocalApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationStateTest {
    public static class LocalApplication extends TvBoxApp {
        private TestSettings testSettings;
        private MovieRepository testMovies;
        private MultiSourceSearch testSearch;
        void configure(String url) {
            testSettings = new TestSettings(new ApiLine("local", "测试源", url, false));
            testMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            testSearch = new MultiSourceSearch(executors().network(), testMovies, testSettings);
        }
        @Override public SettingsRepository settings() { return testSettings == null ? super.settings() : testSettings; }
        @Override public MovieRepository movies() { return testMovies == null ? super.movies() : testMovies; }
        @Override public MultiSourceSearch search() { return testSearch == null ? super.search() : testSearch; }
    }
    private MockWebServer server;
    private ActivityController<MainActivity> controller;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        ((LocalApplication) RuntimeEnvironment.getApplication()).configure(server.url("/api/").toString());
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("{\"page\":1,\"pagecount\":1,\"total\":2,\"class\":["
                        + "{\"type_id\":1,\"type_pid\":0,\"type_name\":\"电影\"},"
                        + "{\"type_id\":2,\"type_pid\":1,\"type_name\":\"动作\"}],\"list\":["
                        + "{\"vod_id\":100,\"type_id\":2,\"vod_name\":\"影片一\"},"
                        + "{\"vod_id\":101,\"type_id\":2,\"vod_name\":\"影片二\"}]}" );
            }
        });
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
    }
    @After public void tearDown() throws Exception {
        controller.pause().stop().destroy(); TvBoxApp.get().executors().shutdown(); server.shutdown();
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            new java.util.concurrent.CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("UI did not reach expected state", condition.getAsBoolean());
    }
    @Test public void homeUsesSourceContentAndSavesCategorySelection() throws Exception {
        RecyclerView tabs = controller.get().findViewById(R.id.home_tabs);
        await(() -> tabs.getAdapter().getItemCount() == 2);
        tabs.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(100, 1073741824));
        tabs.layout(0, 0, 1200, 100);
        tabs.findViewHolderForAdapterPosition(1).itemView.performClick();
        HomeFragment home = (HomeFragment) controller.get().getSupportFragmentManager().findFragmentByTag("HOME");
        Bundle saved = new Bundle(); home.onSaveInstanceState(saved);
        assertEquals("1", saved.getString("tab")); assertEquals("local", saved.getString("api"));
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        for (int i = 1; i < server.getRequestCount(); i++) {
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS).getPath().startsWith("/api/"));
        }
    }
    @Test public void presetQueryBeforeViewCreationRunsAndQueryIsSaved() throws Exception {
        SearchFragment search = new SearchFragment();
        search.presetQuery("影片一");
        controller.get().getSupportFragmentManager().beginTransaction().replace(R.id.content, search, "test-search").commitNow();
        RecyclerView grid = search.getView().findViewById(R.id.search_grid);
        await(() -> grid.getAdapter().getItemCount() == 2);
        assertEquals("影片一", ((EditText) search.getView().findViewById(R.id.search_input)).getText().toString());
        Bundle saved = new Bundle(); search.onSaveInstanceState(saved);
        assertEquals("影片一", saved.getString("query"));
    }
    @Test public void bookmarkTracksStableCardAfterMetadataChangesAndReordering() throws Exception {
        RecyclerView grid = controller.get().findViewById(R.id.home_grid);
        await(() -> grid.getAdapter().getItemCount() == 2);
        grid.setItemAnimator(null);
        grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(800, 1073741824));
        grid.layout(0, 0, 1200, 800);
        android.view.View card = grid.findViewHolderForAdapterPosition(1).itemView;
        assertTrue("API 19 cards must be explicitly keyboard-focusable", card.isFocusable());
        assertTrue(card.requestFocus());
        Bundle bookmark = PageFocusState.capture(controller.get().findViewById(R.id.content));
        assertEquals(R.id.home_grid, bookmark.getInt("list"));
        assertEquals(1, bookmark.getInt("position"));
        PosterGridAdapter adapter = (PosterGridAdapter) grid.getAdapter();
        List<PosterEntry> entries = new ArrayList<>(adapter.entries());
        PosterEntry selected = entries.get(1);
        entries.set(1, new PosterEntry(selected.key, "更新的主源元数据", "", "", selected.payload));
        adapter.updateEntries(entries);
        grid.layout(0, 0, 1200, 800);
        assertEquals(bookmark.getLong("item"), adapter.getItemId(1));
        Collections.reverse(entries); adapter.setEntries(entries);
        grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(800, 1073741824));
        grid.layout(0, 0, 1200, 800);
        assertTrue(PageFocusState.restore(controller.get().findViewById(R.id.content), bookmark));
        await(() -> grid.findFocus() != null && grid.findContainingItemView(grid.findFocus()) != null
                && grid.getChildAdapterPosition(grid.findContainingItemView(grid.findFocus())) == 0);
    }
    @Test public void hiddenLiveTabsResolveToSourceHome() {
        controller.get().selectTab(MainActivity.Tab.LIVE_TV, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        assertFalse(controller.get().getSupportFragmentManager().findFragmentByTag("HOME").isHidden());
        assertNull(controller.get().getSupportFragmentManager().findFragmentByTag("LIVE_TV"));
    }

    @Test public void activityRecreationRestoresSelectedSourceCategory() throws Exception {
        RecyclerView tabs = controller.get().findViewById(R.id.home_tabs);
        await(() -> tabs.getAdapter().getItemCount() == 2);
        tabs.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, 1073741824),
                android.view.View.MeasureSpec.makeMeasureSpec(100, 1073741824));
        tabs.layout(0, 0, 1200, 100);
        tabs.findViewHolderForAdapterPosition(1).itemView.performClick();
        Bundle saved = new Bundle(); controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        RecyclerView restored = controller.get().findViewById(R.id.home_tabs);
        await(() -> restored.getAdapter().getItemCount() == 2);
        ChipAdapter chips = (ChipAdapter) restored.getAdapter();
        assertTrue(chips.chips().get(chips.indexOf("1")).selected);
        assertFalse(chips.chips().get(chips.indexOf("all")).selected);
    }

    @Test public void fontSettingAppliesToMainPageAfterRecreation() {
        TvBoxApp.get().settings().setFontScale(SettingsRepository.FONT_LARGE);
        Bundle saved = new Bundle(); controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        assertEquals(com.tvbox.android44.common.FontScale.SCALE_LARGE,
                controller.get().getResources().getConfiguration().fontScale, 0.001f);
    }

    @Test @Config(sdk = {16, 19, 23, 28})
    public void settingsOnlyKeepsQrConfigurationAndManagementEntries() {
        controller.get().selectTab(MainActivity.Tab.SETTINGS, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        android.view.View settings = controller.get().getSupportFragmentManager()
                .findFragmentByTag("SETTINGS").getView();
        assertNotNull(settings);
        for (String removed : new String[]{"settings_api_add", "settings_ai_model"}) {
            int id = controller.get().getResources().getIdentifier(removed, "id", controller.get().getPackageName());
            assertTrue(id == 0 || settings.findViewById(id) == null);
        }
        assertTrue(settings.findViewById(R.id.settings_api_manage).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_api_qr).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_ai_qr).isFocusable());
        assertTrue(settings.findViewById(R.id.settings_ai_key).isFocusable());
    }
}
