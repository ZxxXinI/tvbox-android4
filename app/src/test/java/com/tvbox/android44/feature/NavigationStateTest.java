package com.tvbox.android44.feature;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.ui.*;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.local.HistoryStore;
import com.tvbox.android44.data.repository.*;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.AiRecommendItem;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import com.tvbox.android44.feature.home.HomeFragment;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.feature.search.SearchFragment;
import com.tvbox.android44.feature.player.PlayerActivity;
import com.tvbox.android44.feature.settings.ConfigHttpServer;
import com.tvbox.android44.feature.settings.LoopbackPhoneSearchFragment;
import com.tvbox.android44.testutil.TestSettings;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
        private volatile List<WatchHistoryItem> historyItems = Collections.emptyList();
        private HistoryStore testHistory;
        void configure(String url) {
            configure(new ApiLine("local", "测试源", url, false));
        }
        void configure(ApiLine... sources) {
            testSettings = new TestSettings(sources);
            testMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            testSearch = new MultiSourceSearch(executors().network(), testMovies, testSettings);
        }
        @Override public SettingsRepository settings() { return testSettings == null ? super.settings() : testSettings; }
        @Override public MovieRepository movies() { return testMovies == null ? super.movies() : testMovies; }
        @Override public MultiSourceSearch search() { return testSearch == null ? super.search() : testSearch; }
        @Override public synchronized HistoryStore history() {
            if (testHistory == null) {
                testHistory = new HistoryStore(this) {
                    @Override public synchronized List<WatchHistoryItem> load() {
                        return new ArrayList<>(historyItems);
                    }
                };
            }
            return testHistory;
        }
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
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().postResume().visible();
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

    @Test public void homeSkipsFailedAndEmptySourcesAndUsesBackupForCategories() throws Exception {
        List<String> pages = Collections.synchronizedList(new ArrayList<String>());
        List<String> categories = Collections.synchronizedList(new ArrayList<String>());
        restartWithSources(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("list".equals(request.getRequestUrl().queryParameter("ac"))) {
                    categories.add(path);
                    // A late category response from the failed source must not appear with the backup.
                    return "/working/".equals(path)
                            ? new MockResponse().setBody("{\"class\":[{\"type_id\":7,\"type_name\":\"备用分类\"}]}")
                            : new MockResponse().setBodyDelay(300, TimeUnit.MILLISECONDS)
                                    .setBody("{\"class\":[{\"type_id\":9,\"type_name\":\"旧分类\"}]}");
                }
                pages.add(path);
                if ("/primary/".equals(path)) return new MockResponse().setResponseCode(504);
                if ("/empty/".equals(path)) return new MockResponse().setBody("{\"list\":[]}");
                return homeMovies();
            }
        }, "primary", "empty", "working");

        RecyclerView grid = controller.get().findViewById(R.id.home_grid);
        RecyclerView tabs = controller.get().findViewById(R.id.home_tabs);
        ChipAdapter chips = (ChipAdapter) tabs.getAdapter();
        await(() -> grid.getAdapter().getItemCount() == 1 && chips.indexOf("7") >= 0);
        assertEquals(Arrays.asList("/primary/", "/empty/", "/working/"), new ArrayList<>(pages));
        assertTrue(categories.contains("/working/"));
        assertEquals(-1, chips.indexOf("9"));
        Movie shown = (Movie) ((PosterGridAdapter) grid.getAdapter()).entries().get(0).payload;
        assertEquals("working", shown.apiLineId);
        assertEquals("primary", TvBoxApp.get().settings().currentApi().id);
        android.widget.TextView status = controller.get().findViewById(R.id.home_status);
        assertTrue(status.getText().toString().contains("working"));
        assertFalse(status.isFocusable());
    }

    @Test public void homeExhaustsBackupsOnceAndRetryPrefersTheDefaultAgain() throws Exception {
        AtomicBoolean primaryRecovered = new AtomicBoolean();
        List<String> pages = Collections.synchronizedList(new ArrayList<String>());
        restartWithSources(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("detail".equals(request.getRequestUrl().queryParameter("ac"))) pages.add(path);
                if ("/primary/".equals(path) && primaryRecovered.get()) return homeMovies();
                return new MockResponse().setResponseCode(503);
            }
        }, "primary", "backup");

        View retry = controller.get().findViewById(R.id.state_retry);
        await(() -> retry.getVisibility() == View.VISIBLE);
        assertEquals(Arrays.asList("/primary/", "/backup/"), new ArrayList<>(pages));
        primaryRecovered.set(true);
        retry.performClick();
        RecyclerView grid = controller.get().findViewById(R.id.home_grid);
        await(() -> grid.getAdapter().getItemCount() == 1);
        assertEquals(Arrays.asList("/primary/", "/backup/", "/primary/"), new ArrayList<>(pages));
        Movie shown = (Movie) ((PosterGridAdapter) grid.getAdapter()).entries().get(0).payload;
        assertEquals("primary", shown.apiLineId);
    }

    @Test public void homeContinuesUnfinishedHistoryUsingTheOriginalSourceAndProgress() throws Exception {
        MainActivity activity = controller.get();
        View section = activity.findViewById(R.id.home_continue_section);
        RecyclerView row = activity.findViewById(R.id.home_continue_grid);
        assertEquals(View.GONE, section.getVisibility());
        List<WatchHistoryItem> items = new ArrayList<>();
        items.add(historyItem("finished", 99000));
        items.add(historyItem("not-started", 0));
        for (int i = 0; i < 7; i++) items.add(historyItem("item-" + i, 30000));
        ((LocalApplication) RuntimeEnvironment.getApplication()).historyItems = items;
        HomeFragment home = (HomeFragment) activity.getSupportFragmentManager().findFragmentByTag("HOME");
        home.onHistoryChanged();
        await(() -> row.getAdapter().getItemCount() == 6);
        assertEquals(View.VISIBLE, section.getVisibility());
        PosterEntry first = ((PosterGridAdapter) row.getAdapter()).entries().get(0);
        assertEquals("old-source|item-0", first.key);
        assertEquals("第3集 · 30%", first.subtitle);
        row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        row.layout(0, 0, 1000, 100);
        View card = row.findViewHolderForAdapterPosition(0).itemView;
        assertTrue(card.requestFocus());
        assertEquals(R.id.nav_history, card.getNextFocusUpId());
        card.performClick();
        org.robolectric.shadows.ShadowActivity.IntentForResult started =
                Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(PlayerActivity.class.getName(), started.intent.getComponent().getClassName());
        WatchHistoryItem history = (WatchHistoryItem) started.intent.getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        assertEquals("old-source", history.apiLineId);
        assertEquals(2, history.episodeIndex);
        assertEquals(30000, history.position);
    }

    private static WatchHistoryItem historyItem(String id, long position) {
        WatchHistoryItem item = new WatchHistoryItem();
        item.apiLineId = "old-source";
        item.movieId = id;
        item.movieName = id;
        item.lineId = "old-source|line";
        item.episodeIndex = 2;
        item.duration = 100000;
        item.position = position;
        return item;
    }

    @Test public void phoneSearchSubmitsKeywordToTvAndLeavingSearchStopsTheSession() throws Exception {
        MainActivity activity = controller.get();
        SearchFragment search = new LoopbackPhoneSearchFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(R.id.content, search, "SEARCH").hide(search).commitNow();
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        ConfigHttpServer session = openLoopbackSearch(search);
        String url = "http://127.0.0.1:" + session.port() + "/" + session.token();
        Future<Integer> response = TvBoxApp.get().executors().network().submit(() -> {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try {
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    stream.write("query=%E5%BD%B1%E7%89%87%E4%B8%80".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        });
        await(response::isDone);
        assertEquals(200, (int) response.get());
        EditText input = activity.findViewById(R.id.search_input);
        RecyclerView results = activity.findViewById(R.id.search_grid);
        await(() -> !session.isRunning() && results.getAdapter().getItemCount() == 2
                && org.robolectric.util.ReflectionHelpers.getField(search, "phoneSearchSession") == null);
        assertEquals("影片一", input.getText().toString());
        assertEquals(input.length(), input.getSelectionStart());

        ConfigHttpServer reopened = openLoopbackSearch(search);
        activity.selectTab(MainActivity.Tab.HOME, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(search.isHidden());
        assertFalse(reopened.isRunning());
    }

    private static ConfigHttpServer openLoopbackSearch(SearchFragment search) {
        assertTrue("Search fragment must be resumed before opening a session", search.isResumed());
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(search, "openPhoneSearchAt",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class, "127.0.0.1"));
        ConfigHttpServer session = org.robolectric.util.ReflectionHelpers.getField(search, "phoneSearchSession");
        assertNotNull(session);
        assertTrue(session.isRunning());
        return session;
    }

    private void restartWithSources(Dispatcher dispatcher, String... names) {
        controller.pause().stop().destroy();
        ApiLine[] sources = new ApiLine[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            sources[i] = new ApiLine(name, name, server.url("/" + name + "/").toString(), false);
        }
        server.setDispatcher(dispatcher);
        ((LocalApplication) RuntimeEnvironment.getApplication()).configure(sources);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().postResume().visible();
    }

    private static MockResponse homeMovies() {
        return new MockResponse().setBody("{\"page\":1,\"pagecount\":1,\"total\":1,"
                + "\"list\":[{\"vod_id\":300,\"type_id\":7,\"vod_name\":\"测试影片\"}]}");
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

    @Test @Config(sdk = {16, 19, 23, 28})
    public void settingsRemoteFocusFollowsVisibleRowsAndDoesNotJumpByGeometry() {
        controller.get().selectTab(MainActivity.Tab.SETTINGS, true);
        controller.get().getSupportFragmentManager().executePendingTransactions();
        android.view.View settings = controller.get().getSupportFragmentManager()
                .findFragmentByTag("SETTINGS").getView();
        assertNotNull(settings);

        assertTrue(settings.findViewById(R.id.settings_font).requestFocus());
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_api);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_font);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.nav_settings);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_font);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_api);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_api_manage);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_provider);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_api_manage);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_api_qr);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_provider);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_api_qr);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_provider);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_key);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_ai_qr);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_auto_line);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_stats);
        direction(KeyEvent.KEYCODE_DPAD_RIGHT, R.id.settings_stats_clear);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_update_action);
        direction(KeyEvent.KEYCODE_DPAD_UP, R.id.settings_stats_clear);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_update_action);
        direction(KeyEvent.KEYCODE_DPAD_DOWN, R.id.settings_update_action);
    }

    private void direction(int code, int target) {
        View focused = controller.get().getWindow().getDecorView().findFocus();
        assertTrue("D-pad " + code + " from " + focused + " to " + controller.get().findViewById(target),
                controller.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
        controller.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        assertTrue(controller.get().findViewById(target).hasFocus());
    }

    @Test public void focusedSearchEditorReceivesDigitsAndShortcutsDoNotRepeat() {
        MainActivity activity = controller.get();
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        EditText input = activity.findViewById(R.id.search_input);
        assertTrue(input.requestFocus());
        KeyEvent[] typing = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents("2012".toCharArray());
        assertNotNull(typing);
        for (KeyEvent event : typing) {
            activity.dispatchKeyEvent(event);
        }
        activity.getSupportFragmentManager().executePendingTransactions();
        assertFalse(activity.getSupportFragmentManager().findFragmentByTag("SEARCH").isHidden());
        assertNull(activity.getSupportFragmentManager().findFragmentByTag("HISTORY"));
        assertTrue(input.hasFocus());
        assertTrue(activity.findViewById(R.id.search_button).requestFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6)));
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.findViewById(R.id.settings_api).requestFocus());
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6, 1)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_6)));
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.findViewById(R.id.settings_api).hasFocus());
    }

    @Test public void searchOpenedFromRecommendationReturnsToItsCard() throws Exception {
        MainActivity activity = controller.get();
        activity.selectTab(MainActivity.Tab.RECOMMEND, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        RecyclerView list = activity.findViewById(R.id.recommend_list);
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(list.getAdapter(), "setItems",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(List.class,
                        Collections.singletonList(new AiRecommendItem("Title", "Title", "Reason"))));
        ((com.tvbox.android44.common.StateLayout) activity.findViewById(R.id.recommend_state)).showContent();
        list.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1000, 500);
        View card = list.findViewHolderForAdapterPosition(0).itemView;
        assertTrue(card.requestFocus());
        card.performClick();
        assertEquals("Title", ((EditText) activity.findViewById(R.id.search_input)).getText().toString());
        activity.onBackPressed();
        activity.getSupportFragmentManager().executePendingTransactions();
        await(() -> card.hasFocus());
        activity.selectTab(MainActivity.Tab.SEARCH, true);
        activity.getSupportFragmentManager().executePendingTransactions();
        activity.onBackPressed();
        activity.getSupportFragmentManager().executePendingTransactions();
        assertFalse(activity.getSupportFragmentManager().findFragmentByTag("HOME").isHidden());
    }
}
