package com.tvbox.android44.feature.recommend;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.*;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.View;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.*;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.*;
import com.tvbox.android44.domain.model.*;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.testutil.TestSettings;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19}, application = RecommendFlowTest.LocalApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RecommendFlowTest {
    public static class LocalApplication extends TvBoxApp {
        TestSettings localSettings;
        MovieRepository localMovies;
        RecommendRepository localRecommend;
        ExecutorService aiWorker;
        void configure(String url) {
            localSettings = new TestSettings(new ApiLine("local", "测试源", url, false)) {
                @Override public AiProvider aiProvider() { return new AiProvider("fixture", "Fixture", url, "model"); }
            };
            localSettings.setAiProvider("deepseek");
            localSettings.setAiApiKey("unit-test-key"); localSettings.setAiModel("fixture-model");
            localMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            aiWorker = Executors.newSingleThreadExecutor(); localRecommend = new RecommendRepository(aiWorker, localSettings);
        }
        @Override public SettingsRepository settings() { return localSettings == null ? super.settings() : localSettings; }
        @Override public MovieRepository movies() { return localMovies == null ? super.movies() : localMovies; }
        @Override public RecommendRepository recommend() { return localRecommend == null ? super.recommend() : localRecommend; }
    }
    private LocalApplication app;
    private MockWebServer server;
    private ActivityController<MainActivity> screen;
    private RecommendFragment page;
    private final AtomicInteger requests = new AtomicInteger();
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); app = (LocalApplication) RuntimeEnvironment.getApplication();
        app.configure(server.url("/api/").toString());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getMethod().equals("POST")) {
                    requests.incrementAndGet();
                    JsonObject body = JsonParser.parseString(request.getBody().clone().readUtf8()).getAsJsonObject();
                    String query = body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                    JsonObject recommendation = new JsonObject(); recommendation.addProperty("title", query); recommendation.addProperty("searchKeyword", query);
                    JsonArray data = new JsonArray(); data.add(recommendation);
                    JsonObject message = new JsonObject(); message.addProperty("content", data.toString());
                    JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                    JsonObject result = new JsonObject(); result.add("choices", choices); return new MockResponse().setBody(result.toString());
                }
                return new MockResponse().setBody("{\"list\":[]}");
            }
        });
        screen = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        screen.get().selectTab(MainActivity.Tab.RECOMMEND, false); screen.get().getSupportFragmentManager().executePendingTransactions();
        page = (RecommendFragment) screen.get().getSupportFragmentManager().findFragmentByTag("RECOMMEND");
    }
    @After public void tearDown() throws Exception {
        screen.pause().stop().destroy(); app.aiWorker.shutdownNow(); app.executors().shutdown(); server.shutdown();
    }
    private void ask(String query) {
        ((EditText) page.getView().findViewById(R.id.recommend_input)).setText(query);
        page.getView().findViewById(R.id.recommend_ask).performClick();
    }
    private void finishWorker() throws Exception { app.aiWorker.submit(() -> {}).get(3, TimeUnit.SECONDS); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private int items() { return ((RecyclerView) page.getView().findViewById(R.id.recommend_list)).getAdapter().getItemCount(); }
    @Test public void changingQueryCancelsQueuedOldResultAndKeepsLatestResult() throws Exception {
        ask("旧问题"); finishWorker(); ask("新问题"); finishWorker(); idle();
        assertEquals(2, requests.get()); assertEquals(1, items());
        assertTrue(((TextView) page.getView().findViewById(R.id.recommend_status)).getText().toString().contains("新问题"));
    }
    @Test public void duplicateSubmissionWhileBusyDoesNotSendAnotherRequest() throws Exception {
        ask("相同问题"); finishWorker(); ask("相同问题"); finishWorker(); idle(); assertEquals(1, requests.get());
    }

    @Test public void invalidCredentialsPreservePreviousRecommendationsAndShowSetupGuidance() throws Exception {
        ask("上一批"); finishWorker(); idle(); assertEquals(1, items());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(401); }
        });
        ask("新问题"); finishWorker(); idle(); assertEquals(1, items());
        String status = ((TextView) page.getView().findViewById(R.id.recommend_status)).getText().toString();
        assertTrue(status.contains("API Key")); assertTrue(status.contains("扫码配置")); assertTrue(status.contains("上一批"));
    }
    @Test public void hiddenPageDiscardsOldResultAndDoesNotStealNavigationFocus() throws Exception {
        ask("旧问题"); finishWorker(); screen.get().selectTab(MainActivity.Tab.HOME, false);
        screen.get().getSupportFragmentManager().executePendingTransactions();
        View nav = screen.get().findViewById(R.id.nav_history); assertTrue(nav.requestFocus()); idle();
        assertEquals(0, items()); assertTrue(nav.hasFocus());
    }
    @Test public void recreatedViewCanSubmitAgainAndOldResultIsDiscarded() throws Exception {
        ask("旧问题"); finishWorker();
        screen.get().getSupportFragmentManager().beginTransaction().detach(page).commitNow();
        screen.get().getSupportFragmentManager().beginTransaction().attach(page).commitNow();
        idle(); assertEquals(0, items()); ask("新问题"); finishWorker(); idle(); assertEquals(1, items());
        assertTrue(((TextView) page.getView().findViewById(R.id.recommend_status)).getText().toString().contains("新问题"));
    }

    @Test public void duplicateAndReorderedRecommendationsKeepFocusedCard() throws Exception {
        server.setDispatcher(new Dispatcher() {
            int batch;
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (!request.getMethod().equals("POST")) return new MockResponse().setBody("{\"list\":[]}");
                boolean first = batch++ == 0;
                JsonArray data = new JsonArray();
                String[] titles = first ? new String[]{"作品甲", "作品甲", "作品乙"} : new String[]{"作品乙", "作品甲"};
                for (String title : titles) {
                    JsonObject item = new JsonObject(); item.addProperty("title", title); item.addProperty("searchKeyword", title);
                    item.addProperty("reason", first ? "初次理由" : "更新理由"); data.add(item);
                }
                JsonObject message = new JsonObject(); message.addProperty("content", data.toString());
                JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
                JsonObject result = new JsonObject(); result.add("choices", choices); return new MockResponse().setBody(result.toString());
            }
        });
        ask("第一批"); finishWorker(); idle(); assertEquals(2, items());
        RecyclerView list = page.getView().findViewById(R.id.recommend_list);
        list.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1200, 640); idle();
        long focusedId = list.getAdapter().getItemId(0);
        assertTrue(list.findViewHolderForItemId(focusedId).itemView.requestFocus());
        ask("第二批"); finishWorker(); idle();
        list.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1200, 640); idle();
        RecyclerView.ViewHolder focused = list.findViewHolderForItemId(focusedId);
        assertNotNull(focused); assertTrue(focused.itemView.hasFocus());
        assertEquals(1, focused.getBindingAdapterPosition());
        assertEquals("更新理由", ((TextView) focused.itemView.findViewById(R.id.recommend_item_reason)).getText().toString());
    }
    @Test public void missingSpeechServiceFallsBackToTextInput() {
        assertEquals(View.GONE, page.getView().findViewById(R.id.recommend_voice).getVisibility());
        assertTrue(page.getView().findViewById(R.id.recommend_input).isEnabled());
    }
    @Test @Config(sdk = 23) public void microphonePermissionDenialAndGrantHaveUsableBranches() {
        page.onRequestPermissionsResult(1002, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_DENIED});
        assertTrue(ShadowToast.getTextOfLatestToast().contains("未授予麦克风权限"));
        assertNull(Shadows.shadowOf(screen.get()).getNextStartedActivityForResult());
        ResolveInfo available = new ResolveInfo(); available.activityInfo = new ActivityInfo();
        available.activityInfo.packageName = "fixture.speech"; available.activityInfo.name = "FixtureSpeech";
        available.activityInfo.enabled = true; available.activityInfo.exported = true;
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), available);
        page.onRequestPermissionsResult(1002, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_GRANTED});
        org.robolectric.shadows.ShadowActivity.IntentForResult intent = Shadows.shadowOf(screen.get()).getNextStartedActivityForResult();
        assertNotNull(intent); assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.intent.getAction());
    }
    @Test public void voiceResultAfterViewDestructionDoesNotStartOldRequest() {
        screen.get().getSupportFragmentManager().beginTransaction().detach(page).commitNow();
        Intent result = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Collections.singletonList("旧语音")));
        page.onActivityResult(1001, Activity.RESULT_OK, result); assertEquals(0, requests.get());
    }
}
