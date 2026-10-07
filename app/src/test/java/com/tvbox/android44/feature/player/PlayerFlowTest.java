package com.tvbox.android44.feature.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.video.VideoSize;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.*;
import com.tvbox.android44.domain.model.*;
import com.tvbox.android44.feature.detail.DetailActivity;
import com.tvbox.android44.testutil.TestSettings;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19}, application = PlayerFlowTest.LocalApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlayerFlowTest {
    public static class LocalApplication extends TvBoxApp {
        TestSettings localSettings;
        MovieRepository localMovies;
        DetailSupplement localSupplement;
        void configure(String url) {
            localSettings = new TestSettings(new ApiLine("local", "测试源", url, false));
            localMovies = new MovieRepository(executors().network(), executors().sourceRequests());
            localSupplement = new DetailSupplement(executors().network(), localMovies, localSettings);
        }
        @Override public SettingsRepository settings() { return localSettings == null ? super.settings() : localSettings; }
        @Override public MovieRepository movies() { return localMovies == null ? super.movies() : localMovies; }
        @Override public DetailSupplement supplement() { return localSupplement == null ? super.supplement() : localSupplement; }
    }
    public static class TestPlayerActivity extends PlayerActivity {
        final List<FakePlayer> players = new ArrayList<>();
        long time = 100000;
        @Override protected long playbackTime() { return time; }
        @Override protected ExoPlayer createPlayer() {
            FakePlayer player = new FakePlayer(); players.add(player); return player.proxy;
        }
        FakePlayer current() { return players.get(players.size() - 1); }
        void tick(long millis) {
            time += millis;
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        }
    }
    static class FakePlayer implements InvocationHandler {
        final List<Player.Listener> listeners = new ArrayList<>();
        final ExoPlayer proxy = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class[]{ExoPlayer.class}, this);
        int state = Player.STATE_BUFFERING, prepared, released;
        long position, duration = 100000;
        boolean playWhenReady;
        PlaybackParameters parameters = PlaybackParameters.DEFAULT;
        final List<MediaSource> sources = new ArrayList<>();
        void state(int next) {
            state = next;
            for (Player.Listener listener : new ArrayList<>(listeners)) listener.onPlaybackStateChanged(next);
        }
        @Override public Object invoke(Object ignored, Method method, Object[] args) {
            switch (method.getName()) {
                case "addListener": listeners.add((Player.Listener) args[0]); return null;
                case "removeListener": listeners.remove(args[0]); return null;
                case "getApplicationLooper": return Looper.getMainLooper();
                case "getPlaybackState": return state;
                case "getPlayWhenReady": return playWhenReady;
                case "isPlaying": return playWhenReady && state == Player.STATE_READY;
                case "getCurrentPosition": case "getBufferedPosition": return position;
                case "getDuration": case "getContentDuration": return duration;
                case "getCurrentTimeline": return Timeline.EMPTY;
                case "getAvailableCommands": return Player.Commands.EMPTY;
                case "getCurrentTracks": return Tracks.EMPTY;
                case "getVideoSize": return VideoSize.UNKNOWN;
                case "getCueGroup": return CueGroup.EMPTY_TIME_ZERO;
                case "getCurrentCues": return Collections.emptyList();
                case "getAudioAttributes": return AudioAttributes.DEFAULT;
                case "getMediaMetadata": case "getPlaylistMetadata": return MediaMetadata.EMPTY;
                case "getPlaybackParameters": return parameters;
                case "setPlaybackParameters": parameters = (PlaybackParameters) args[0]; return null;
                case "setMediaSource": sources.add((MediaSource) args[0]); position = (Long) args[1]; return null;
                case "prepare": prepared++; return null;
                case "release": released++; listeners.clear(); return null;
                case "setPlayWhenReady":
                    playWhenReady = (Boolean) args[0];
                    for (Player.Listener listener : new ArrayList<>(listeners)) listener.onPlayWhenReadyChanged(playWhenReady, 1);
                    return null;
                case "seekTo": position = (Long) args[args.length - 1]; return null;
                case "toString": return "controlled player";
                case "equals": return ignored == args[0];
                case "hashCode": return System.identityHashCode(ignored);
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 1f;
                    return null;
            }
        }
    }

    private MockWebServer server;
    private LocalApplication app;
    private final List<ActivityController<?>> screens = new ArrayList<>();
    private CountDownLatch unblockDisk;
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        app = (LocalApplication) RuntimeEnvironment.getApplication(); app.configure(server.url("/api/").toString());
        app.settings().setAutoLineSwitch(true);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setBody(detail()); }
        });
    }
    @After public void tearDown() throws Exception {
        if (unblockDisk != null) unblockDisk.countDown();
        for (ActivityController<?> screen : screens) screen.pause().stop().destroy();
        app.executors().disk().submit(() -> {}).get(3, TimeUnit.SECONDS);
        app.executors().shutdown(); server.shutdown();
    }
    private static String detail() {
        return "{\"list\":[{\"vod_id\":1,\"type_id\":1,\"vod_name\":\"测试影片\","
                + "\"vod_play_from\":\"lineA$$$lineB\",\"vod_play_url\":\""
                + "第1集$https://example.com/1.mp4#第2集$https://example.com/2.mp4#第3集$https://example.com/3.mp4$$$"
                + "第1集$https://example.com/a.mp4#第2集$https://example.com/b.mp4#第3集$https://example.com/c.mp4\"}]}";
    }
    private void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
        assertTrue("playback UI did not load", ready.getAsBoolean());
    }
    private TestPlayerActivity player(Intent intent) throws Exception {
        ActivityController<TestPlayerActivity> screen = Robolectric.buildActivity(TestPlayerActivity.class, intent).create().start().resume().visible();
        screens.add(screen); await(() -> !screen.get().players.isEmpty()); return screen.get();
    }
    private TestPlayerActivity player() throws Exception {
        return player(new Intent(app, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_API_ID, "local")
                .putExtra(PlayerActivity.EXTRA_MOVIE_ID, "1").putExtra(PlayerActivity.EXTRA_LINE_ID, "local|lineA"));
    }
    private String line(TestPlayerActivity player) { return ReflectionHelpers.getField(player, "lineId"); }
    private void key(TestPlayerActivity player, int code) { player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); }

    @Test public void continuousBufferingSwitchesDespiteIsPlayingFalse() throws Exception {
        TestPlayerActivity player = player(); FakePlayer first = player.current();
        first.state(Player.STATE_READY); first.state(Player.STATE_BUFFERING); player.onIsPlayingChanged(false);
        player.tick(5500);
        assertEquals(2, player.players.size()); assertEquals(1, first.released); assertTrue(line(player).endsWith("lineB"));
    }
    @Test public void readyEndsBufferWindowsAndFrequentBuffersSwitchOnce() throws Exception {
        TestPlayerActivity player = player(); player.current().state(Player.STATE_READY);
        for (int i = 0; i < 3; i++) {
            player.current().state(Player.STATE_BUFFERING); player.time += 1000; player.current().state(Player.STATE_READY);
        }
        assertEquals(2, player.players.size()); assertTrue(line(player).endsWith("lineB"));
    }
    @Test public void pausedSeekingAndDisabledAutomaticSwitchDoNotSwitch() throws Exception {
        TestPlayerActivity player = player(); player.current().state(Player.STATE_READY);
        player.current().state(Player.STATE_BUFFERING); player.current().proxy.setPlayWhenReady(false); player.tick(6000);
        assertEquals(1, player.players.size());
        player.current().state(Player.STATE_READY); player.current().proxy.setPlayWhenReady(true);
        player.current().state(Player.STATE_BUFFERING); player.time += 4000;
        key(player, KeyEvent.KEYCODE_DPAD_RIGHT); player.tick(2000); assertEquals(1, player.players.size());
        player.current().state(Player.STATE_READY); app.settings().setAutoLineSwitch(false);
        player.current().state(Player.STATE_BUFFERING); player.tick(6000); assertEquals(1, player.players.size());
    }
    @Test public void exhaustedLinesDoNotLoopAndStoppingReleasesPlayer() throws Exception {
        TestPlayerActivity player = player(); player.tick(6000); player.tick(6000); player.tick(6000);
        assertEquals(2, player.players.size()); assertEquals(View.VISIBLE, player.findViewById(R.id.player_error).getVisibility());
        screens.get(0).pause().stop(); assertEquals(1, player.current().released);
        player.tick(10000); assertEquals(2, player.players.size());
    }
    @Test public void parsingFallbackIsLimitedToOneAndHttpErrorsDoNotRetryType() throws Exception {
        app.settings().setAutoLineSwitch(false);
        TestPlayerActivity player = player(); FakePlayer fake = player.current();
        PlaybackException badType = new PlaybackException("format", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED);
        player.onPlayerError(badType); assertEquals(2, fake.sources.size());
        player.onPlayerError(badType); assertEquals(2, fake.sources.size());
        assertEquals(View.VISIBLE, player.findViewById(R.id.player_error).getVisibility());
        assertTrue(fake.sources.get(1) instanceof com.google.android.exoplayer2.source.hls.HlsMediaSource);
    }
    @Test public void httpFailureDoesNotTriggerFormatFallback() throws Exception {
        app.settings().setAutoLineSwitch(false); TestPlayerActivity player = player();
        player.onPlayerError(new PlaybackException("HTTP", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
        assertEquals(1, player.current().sources.size());
    }
    @Test public void resultRestoresLatestLineEpisodeAndContinueWithoutWaitingForDisk() throws Exception {
        Intent detailIntent = new Intent(app, DetailActivity.class).putExtra(DetailActivity.EXTRA_API_ID, "local").putExtra(DetailActivity.EXTRA_MOVIE_ID, "1");
        ActivityController<DetailActivity> details = Robolectric.buildActivity(DetailActivity.class, detailIntent).create().start().resume().visible();
        screens.add(details); await(() -> ReflectionHelpers.getField(details.get(), "movie") != null);
        details.get().findViewById(R.id.detail_play).performClick();
        org.robolectric.shadows.ShadowActivity.IntentForResult launch = Shadows.shadowOf(details.get()).getNextStartedActivityForResult();
        assertEquals(PlayerActivity.REQUEST_PLAYBACK, launch.requestCode);
        TestPlayerActivity player = player(launch.intent); player.current().state(Player.STATE_READY);
        key(player, KeyEvent.KEYCODE_3); player.current().state(Player.STATE_READY);
        key(player, KeyEvent.KEYCODE_3); player.current().state(Player.STATE_READY);
        player.findViewById(R.id.player_change_line).performClick(); player.current().state(Player.STATE_READY);
        unblockDisk = new CountDownLatch(1); CountDownLatch blocked = new CountDownLatch(1);
        app.executors().disk().execute(() -> { blocked.countDown(); try { unblockDisk.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(blocked.await(3, TimeUnit.SECONDS));
        player.current().position = 25000; player.finish();
        Intent result = Shadows.shadowOf(player).getResultIntent();
        Shadows.shadowOf(details.get()).receiveResult(launch.intent, Activity.RESULT_OK, result);
        assertEquals(2, (int) ReflectionHelpers.getField(details.get(), "selectedEpisode"));
        assertTrue(((String) ReflectionHelpers.getField(details.get(), "selectedLineId")).endsWith("lineB"));
        assertEquals("继续播放 第3集", ((TextView) details.get().findViewById(R.id.detail_continue)).getText().toString());
        androidx.recyclerview.widget.RecyclerView grid = details.get().findViewById(R.id.detail_episodes);
        grid.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 1200, 640); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(grid.findViewHolderForAdapterPosition(2)); assertTrue(grid.findViewHolderForAdapterPosition(2).itemView.hasFocus());
        details.get().findViewById(R.id.detail_continue).performClick();
        WatchHistoryItem next = (WatchHistoryItem) Shadows.shadowOf(details.get()).getNextStartedActivityForResult().intent.getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        assertEquals(2, next.episodeIndex); assertEquals(25000, next.position); assertTrue(next.lineId.endsWith("lineB"));
    }
    @Test public void nearEndResumeUsesNextEpisodeFromZero() throws Exception {
        WatchHistoryItem saved = new WatchHistoryItem(); saved.apiLineId = "local"; saved.movieId = "1";
        saved.lineId = "local|lineA"; saved.episodeTitle = "第1集"; saved.duration = 100000; saved.position = 99000;
        TestPlayerActivity player = player(new Intent(app, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_API_ID, "local")
                .putExtra(PlayerActivity.EXTRA_MOVIE_ID, "1").putExtra(PlayerActivity.EXTRA_HISTORY_ITEM, saved));
        assertEquals(1, (int) ReflectionHelpers.getField(player, "episodeIndex")); assertEquals(0, player.current().position);
    }

    @Test public void naturalEndAdvancesFromZeroAndReturnsCurrentEpisode() throws Exception {
        TestPlayerActivity player = player(); FakePlayer first = player.current();
        first.state(Player.STATE_READY); first.position = first.duration; first.state(Player.STATE_ENDED);
        assertEquals(2, player.players.size()); assertEquals(0, player.current().position);
        assertEquals(1, (int) ReflectionHelpers.getField(player, "episodeIndex"));
        player.current().state(Player.STATE_READY); player.current().position = 15000; player.finish();
        WatchHistoryItem result = (WatchHistoryItem) Shadows.shadowOf(player).getResultIntent().getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        assertEquals(1, result.episodeIndex); assertEquals("第2集", result.episodeTitle); assertEquals(15000, result.position);
    }

    @Test public void finalEpisodeEndRemainsAtZeroWhenReturningAndStopping() throws Exception {
        TestPlayerActivity player = player(new Intent(app, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_API_ID, "local")
                .putExtra(PlayerActivity.EXTRA_MOVIE_ID, "1").putExtra(PlayerActivity.EXTRA_EPISODE, 2));
        FakePlayer fake = player.current(); fake.state(Player.STATE_READY); fake.position = fake.duration;
        fake.state(Player.STATE_ENDED); assertEquals(1, player.players.size()); player.finish();
        WatchHistoryItem result = (WatchHistoryItem) Shadows.shadowOf(player).getResultIntent().getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        assertEquals(2, result.episodeIndex); assertEquals(0, result.position);
        screens.get(0).pause().stop(); app.executors().disk().submit(() -> {}).get(3, TimeUnit.SECONDS);
        WatchHistoryItem saved = app.history().find("local", "1");
        assertNotNull(saved); assertEquals(2, saved.episodeIndex); assertEquals(0, saved.position);
    }

    @Test public void idleFromPlaybackFailureDoesNotTriggerASecondBufferRecovery() throws Exception {
        TestPlayerActivity player = player();
        player.current().state(Player.STATE_READY);
        for (int i = 0; i < 2; i++) {
            player.current().state(Player.STATE_BUFFERING); player.time += 1000; player.current().state(Player.STATE_READY);
        }
        player.current().state(Player.STATE_BUFFERING); player.time += 1000; player.current().state(Player.STATE_IDLE);
        assertEquals(1, player.players.size());
        player.onPlayerError(new PlaybackException("HTTP", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
        assertEquals(2, player.players.size()); assertTrue(line(player).endsWith("lineB"));
    }

    @Test public void pausingPersistsCurrentPositionWithoutWaitingForPeriodicSave() throws Exception {
        TestPlayerActivity player = player(); player.current().state(Player.STATE_READY);
        player.current().position = 27000; player.current().proxy.setPlayWhenReady(false);
        app.executors().disk().submit(() -> {}).get(3, TimeUnit.SECONDS);
        WatchHistoryItem saved = app.history().find("local", "1");
        assertNotNull(saved); assertEquals(27000, saved.position);
    }

    @Test public void recreatingNearEndSessionKeepsTheCurrentEpisode() throws Exception {
        TestPlayerActivity player = player(); player.current().state(Player.STATE_READY);
        player.current().position = 99000;
        android.os.Bundle saved = new android.os.Bundle(); screens.get(0).saveInstanceState(saved).pause().stop().destroy();
        screens.clear();
        ActivityController<TestPlayerActivity> restored = Robolectric.buildActivity(TestPlayerActivity.class, player.getIntent())
                .create(saved).start().resume().visible();
        screens.add(restored); await(() -> !restored.get().players.isEmpty());
        assertEquals(0, (int) ReflectionHelpers.getField(restored.get(), "episodeIndex"));
        assertEquals(99000, restored.get().current().position);
    }

    @Test public void failedFirstDetailCanLaunchLastUrlWithOriginalHistoryMetadata() throws Exception {
        server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(500); } });
        WatchHistoryItem saved = new WatchHistoryItem(); saved.apiLineId = "local"; saved.movieId = "1";
        saved.lineId = "local|lineA"; saved.lineName = "lineA"; saved.apiLineName = "测试源";
        saved.movieName = "上次影片"; saved.episodeIndex = 2; saved.episodeTitle = "第3集";
        saved.episodeUrl = "https://example.com/3.mp4"; saved.position = 25000; saved.duration = 100000;
        Intent intent = new Intent(app, DetailActivity.class).putExtra(DetailActivity.EXTRA_API_ID, "local")
                .putExtra(DetailActivity.EXTRA_MOVIE_ID, "1").putExtra(PlayerActivity.EXTRA_HISTORY_ITEM, saved);
        ActivityController<DetailActivity> detail = Robolectric.buildActivity(DetailActivity.class, intent).create().start().resume().visible();
        screens.add(detail); await(() -> (boolean) ReflectionHelpers.getField(detail.get(), "fallbackOffered"));
        android.app.Dialog prompt = org.robolectric.shadows.ShadowDialog.getLatestDialog(); assertNotNull(prompt);
        prompt.findViewById(R.id.dialog_ok).performClick();
        Intent launch = Shadows.shadowOf(detail.get()).getNextStartedActivityForResult().intent;
        assertTrue(launch.getBooleanExtra(PlayerActivity.EXTRA_USE_LAST_URL, false));
        TestPlayerActivity player = player(launch); player.current().state(Player.STATE_READY); player.finish();
        WatchHistoryItem result = (WatchHistoryItem) Shadows.shadowOf(player).getResultIntent().getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        assertEquals("上次影片", result.movieName); assertEquals(2, result.episodeIndex); assertEquals("第3集", result.episodeTitle);
        assertEquals(25000, result.position); assertEquals(1, server.getRequestCount());
    }
}
