package com.tvbox.android44.feature.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.HealthStore;
import com.tvbox.android44.data.repository.MovieRepository;
import com.tvbox.android44.data.remote.HttpClients;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.LineHealth;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PlayEpisode;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import com.tvbox.android44.domain.playback.AutoSwitchPolicy;
import com.tvbox.android44.domain.playback.BufferJudger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 点播播放器：
 * - HLS/MP4（OkHttp 数据源，API19 TLS 兼容）；FIT 比例；电视保持横屏。
 * - 播放/暂停、seek ±10s、上/下一集、倍速、自动下一集、手动/自动换线。
 * - 历史节流保存；音频焦点；屏幕常亮；离开释放全部资源。
 */
public class PlayerActivity extends AppCompatActivity implements Player.Listener {

    public static final String EXTRA_API_ID = "apiId";
    public static final String EXTRA_MOVIE_ID = "movieId";
    public static final String EXTRA_LINE_ID = "lineId";
    public static final String EXTRA_EPISODE = "episode";
    public static final String EXTRA_RESUME_POSITION = "resumePos";
    public static final String EXTRA_FALLBACK_URL = "fallbackUrl";

    private static final int MSG_SAVE_HISTORY = 1;
    private static final int MSG_UPDATE_TIME = 2;

    public static void startForResult(android.app.Activity from, String apiId, String movieId,
                                      String lineId, int episodeIndex) {
        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra(EXTRA_API_ID, apiId);
        i.putExtra(EXTRA_MOVIE_ID, movieId);
        i.putExtra(EXTRA_LINE_ID, lineId);
        i.putExtra(EXTRA_EPISODE, episodeIndex);
        from.startActivityForResult(i, 2001);
    }

    public static void startForResultWithHistory(android.app.Activity from, String apiId,
                                                  String movieId, String lineId, int episodeIndex,
                                                  long resumePosition, String fallbackUrl,
                                                  String episodeTitle) {
        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra(EXTRA_API_ID, apiId);
        i.putExtra(EXTRA_MOVIE_ID, movieId);
        i.putExtra(EXTRA_LINE_ID, lineId);
        i.putExtra(EXTRA_EPISODE, episodeIndex);
        i.putExtra(EXTRA_RESUME_POSITION, resumePosition);
        i.putExtra(EXTRA_FALLBACK_URL, fallbackUrl);
        from.startActivityForResult(i, 2001);
    }

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private PlayerControllerView controller;
    private ProgressBar buffering;
    private TextView hint;
    private View errorOverlay;
    private TextView errorText;

    private String apiId;
    private String movieId;
    private String lineId;
    private int episodeIndex;
    private long resumePosition;
    private String fallbackUrl;

    private Movie movie;
    private PlaySource currentSource;
    private PlayEpisode currentEpisode;

    private MovieRepository.Request detailRequest;
    private final List<String> triedLines = new ArrayList<String>();
    private BufferJudger judger;
    private boolean playing;
    private boolean readyRecorded;
    private int speedIndex = 2; // 1.0x
    private boolean lastLineFailed;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener audioFocusListener;
    private final BecomingNoisyReceiver noisyReceiver = new BecomingNoisyReceiver();
    private boolean noisyRegistered;
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_SAVE_HISTORY) {
                saveHistory();
                scheduleHistorySave();
            } else if (msg.what == MSG_UPDATE_TIME) {
                updateUiTime();
                sendEmptyMessageDelayed(MSG_UPDATE_TIME, 500);
            }
        }
    };
    private long seekTargetFromDrag = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        apiId = getIntent().getStringExtra(EXTRA_API_ID);
        movieId = getIntent().getStringExtra(EXTRA_MOVIE_ID);
        lineId = getIntent().getStringExtra(EXTRA_LINE_ID);
        episodeIndex = getIntent().getIntExtra(EXTRA_EPISODE, 0);
        resumePosition = getIntent().getLongExtra(EXTRA_RESUME_POSITION, 0);
        fallbackUrl = getIntent().getStringExtra(EXTRA_FALLBACK_URL);

        playerView = findViewById(R.id.player_view);
        buffering = findViewById(R.id.player_buffering);
        hint = findViewById(R.id.player_hint);
        errorOverlay = findViewById(R.id.player_error);
        errorText = findViewById(R.id.player_error_text);
        playerView.setUseController(false);
        playerView.requestFocus();

        controller = new PlayerControllerView(findViewById(R.id.player_controller), new PlayerControllerView.Listener() {
            @Override
            public void onTogglePlay() {
                togglePlayPause();
            }

            @Override
            public void onPrevEpisode() {
                switchEpisode(episodeIndex - 1);
            }

            @Override
            public void onNextEpisode() {
                switchEpisode(episodeIndex + 1);
            }

            @Override
            public void onCycleSpeed() {
                cycleSpeed();
            }

            @Override
            public void onChangeLine() {
                manualChangeLine();
            }

            @Override
            public void onExit() {
                finish();
            }

            @Override
            public void onSeekTo(int progressPercent) {
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * progressPercent / 1000);
                    judger.onSeekPerformed();
                }
            }
        });

        new PlayerGestureHelper(this, new PlayerGestureHelper.Callback() {
            @Override
            public void onSingleTap() {
                if (controller.isShown()) {
                    controller.hide();
                } else {
                    controller.show();
                    controller.focusPlayToggle();
                }
            }

            @Override
            public void onDoubleTapLeft() {
                seekBy(-AppConstants.SEEK_STEP_MS);
            }

            @Override
            public void onDoubleTapCenter() {
                togglePlayPause();
            }

            @Override
            public void onDoubleTapRight() {
                seekBy(AppConstants.SEEK_STEP_MS);
            }

            @Override
            public void onLongPressSpeed() {
                if (player != null) {
                    player.setPlaybackParameters(new PlaybackParameters(2.0f));
                    showHint("2倍速播放中");
                }
            }

            @Override
            public void onLongPressUp() {
                applySpeed(speedIndex);
            }

            @Override
            public void onHorizontalDrag(float deltaPx) {
                if (player == null) {
                    return;
                }
                long duration = player.getDuration();
                if (duration <= 0) {
                    return;
                }
                long pos = player.getCurrentPosition();
                long deltaMs = (long) (deltaPx * 600);
                seekTargetFromDrag = Math.max(0, Math.min(pos + deltaMs, duration));
                showHint("快进到 " + PlayerControllerView.formatTime(seekTargetFromDrag));
            }

            @Override
            public void onHorizontalDragEnd() {
                if (seekTargetFromDrag >= 0 && player != null) {
                    player.seekTo(seekTargetFromDrag);
                    judger.onSeekPerformed();
                    hideHint();
                }
                seekTargetFromDrag = -1;
            }

            @Override
            public void onVerticalDrag(boolean leftHalf, float deltaPx) {
                if (audioManager == null) {
                    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                }
                int delta = (int) (-deltaPx / 8f);
                if (leftHalf) {
                    float cur = getWindow().getAttributes().screenBrightness;
                    if (cur < 0) {
                        cur = 0.5f;
                    }
                    float next = Math.max(0.05f, Math.min(1f, cur + delta / 200f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = next;
                    getWindow().setAttributes(lp);
                    showHint("亮度 " + (int) (next * 100) + "%");
                } else {
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int next = Math.max(0, Math.min(max, cur + delta / 20));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                    showHint("音量 " + next + "/" + max);
                }
            }
        }).bind(playerView);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        judger = new BufferJudger(BufferJudger.Mode.VOD, new BufferJudger.Clock() {
            @Override
            public long now() {
                return SystemClockBridge.elapsedRealtime();
            }
        });

        findViewById(R.id.player_error_retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorOverlay.setVisibility(View.GONE);
                triedLines.clear();
                startPlayback();
            }
        });
        findViewById(R.id.player_error_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        loadDetailAndPlay();
    }

    private static final class SystemClockBridge {
        static long elapsedRealtime() {
            return android.os.SystemClock.elapsedRealtime();
        }
    }

    // ===== 数据与创建 =====

    private void loadDetailAndPlay() {
        buffering.setVisibility(View.VISIBLE);
        ApiLine api = findApi(apiId);
        if (api == null) {
            showError("视频接口不可用");
            return;
        }
        detailRequest = TvBoxApp.get().movies().fetchDetail(api, movieId,
                new MovieRepository.Callback<Movie>() {
                    @Override
                    public void onResult(Result<Movie> result) {
                        if (isFinishing()) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            movie = result.data();
                            resolveLineAndEpisode();
                        } else {
                            tryFallbackUrl();
                        }
                    }
                });
    }

    private void tryFallbackUrl() {
        // 历史恢复：详情取不到时短期尝试旧地址（提示用户）
        if (fallbackUrl != null && (fallbackUrl.startsWith("http://")
                || fallbackUrl.startsWith("https://"))) {
            showHint("原播放线路可能已更新，正在尝试上次地址");
            movie = new Movie(movieId, apiId, "", "历史播放");
            currentSource = new PlaySource(lineId, "临时", "历史");
            currentEpisode = new PlayEpisode(episodeIndex, "上次的集", fallbackUrl);
            startPlayback();
        } else {
            showError("影片详情加载失败，请返回重试");
        }
    }

    private void resolveLineAndEpisode() {
        // 历史位置距片尾过近时从头/下一集开始
        if (resumePosition > 0 && currentEpisodeDurationFromHistory() > 0) {
            long dur = currentEpisodeDurationFromHistory();
            if (resumePosition >= (long) (dur * AppConstants.HISTORY_END_RESTART_RATIO)) {
                PlaySource s = findSource(lineId);
                if (s != null && episodeIndex + 1 < s.episodes.size()) {
                    episodeIndex++;
                } else {
                    resumePosition = 0;
                }
            }
        }
        currentSource = findSource(lineId);
        if (currentSource == null && !movie.playSources.isEmpty()) {
            currentSource = movie.playSources.get(0);
            lineId = currentSource.lineId;
        }
        if (currentSource == null || currentSource.episodes.isEmpty()) {
            showError("没有可播放的线路");
            return;
        }
        if (episodeIndex < 0 || episodeIndex >= currentSource.episodes.size()) {
            episodeIndex = 0;
        }
        currentEpisode = currentSource.episodes.get(episodeIndex);
        startPlayback();
    }

    private long currentEpisodeDurationFromHistory() {
        WatchHistoryItem h = TvBoxApp.get().history().find(apiId, movieId);
        return h != null ? h.duration : 0;
    }

    private PlaySource findSource(String id) {
        for (PlaySource ps : movie.playSources) {
            if (ps.lineId.equals(id)) {
                return ps;
            }
        }
        return null;
    }

    private ApiLine findApi(String id) {
        for (ApiLine l : TvBoxApp.get().settings().allApis()) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }

    private void startPlayback() {
        releasePlayer();
        if (currentEpisode == null) {
            showError("播放地址无效");
            return;
        }
        player = new SimpleExoPlayer.Builder(this, new DefaultRenderersFactory(this))
                .setTrackSelector(new DefaultTrackSelector(this))
                .build();
        player.addListener(this);
        playerView.setPlayer(player);

        DataSource.Factory dsFactory = buildDataSourceFactory();
        MediaSource mediaSource = buildMediaSource(currentEpisode.url, dsFactory);
        player.setMediaSource(mediaSource, resumePosition > 0 ? resumePosition : 0);
        player.prepare();
        player.setPlayWhenReady(true);
        playing = true;
        readyRecorded = false;
        judger.reset();
        requestAudioFocus();
        registerNoisy();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buffering.setVisibility(View.VISIBLE);
        controller.setTitles(movie.name,
                currentSource.sourceName + "·" + currentSource.lineName
                        + " · 第" + (episodeIndex + 1) + "集 " + currentEpisode.title);
        controller.setSpeedLabel(AppConstants.SPEED_SEQUENCE[speedIndex]);
        controller.show();
        controller.focusPlayToggle();
        handler.removeMessages(MSG_UPDATE_TIME);
        handler.sendEmptyMessage(MSG_UPDATE_TIME);
    }

    private DataSource.Factory buildDataSourceFactory() {
        OkHttpDataSource.Factory base = new OkHttpDataSource.Factory(
                HttpClients.client(), HttpClients.DEFAULT_UA);
        return new DefaultDataSourceFactory(this, base);
    }

    private static MediaSource buildMediaSource(String url, DataSource.Factory dsFactory) {
        MediaItem item = MediaItem.fromUri(url);
        if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) {
            return new HlsMediaSource.Factory(dsFactory).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item);
    }

    // ===== 播放器事件 =====

    @Override
    public void onPlaybackStateChanged(int state) {
        if (player == null) {
            return;
        }
        switch (state) {
            case Player.STATE_READY:
                buffering.setVisibility(View.GONE);
                if (!readyRecorded) {
                    readyRecorded = true;
                    recordSuccessOnce();
                    scheduleHistorySave();
                    if (resumePosition > 0) {
                        showHint("从上次进度 " + PlayerControllerView.formatTime(resumePosition) + " 继续");
                        resumePosition = 0;
                    }
                }
                break;
            case Player.STATE_BUFFERING:
                buffering.setVisibility(View.VISIBLE);
                onBufferingChanged(true);
                break;
            case Player.STATE_IDLE:
                buffering.setVisibility(View.GONE);
                onBufferingChanged(false);
                break;
            case Player.STATE_ENDED:
                buffering.setVisibility(View.GONE);
                onBufferingChanged(false);
                onEpisodeEnded();
                break;
            default:
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        playing = isPlaying;
        judger.onPaused(!isPlaying);
        controller.setPlaying(isPlaying);
        if (isPlaying) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void onBufferingChanged(boolean nowBuffering) {
        BufferJudger.Verdict v = judger.onBufferingChanged(nowBuffering);
        if (v != BufferJudger.Verdict.NONE) {
            handleBufferVerdict(v);
        }
    }

    private void handleBufferVerdict(BufferJudger.Verdict verdict) {
        recordSlowForCurrentLine();
        if (TvBoxApp.get().settings().autoLineSwitch()) {
            autoSwitchLine("当前线路卡顿（" + verdictName(verdict) + "），已自动换线");
        } else {
            showHint("当前线路卡顿，可按换线按钮手动切换");
        }
    }

    private static String verdictName(BufferJudger.Verdict v) {
        switch (v) {
            case CONTINUOUS:
                return "连续缓冲";
            case FREQUENT:
                return "频繁缓冲";
            case CUMULATIVE:
                return "累计缓冲";
            default:
                return "播放停滞";
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        buffering.setVisibility(View.GONE);
        recordFailForCurrentLine();
        if (TvBoxApp.get().settings().autoLineSwitch()) {
            autoSwitchLine("当前线路播放失败，已自动换线");
        } else {
            showError("播放失败，可重试或返回详情手动换线");
        }
    }

    // ===== 换线/换集 =====

    private void autoSwitchLine(String message) {
        if (movie == null || currentSource == null) {
            showError("播放失败");
            return;
        }
        if (!triedLines.contains(lineId)) {
            triedLines.add(lineId);
        }
        final long now = System.currentTimeMillis();
        PlaySource next = AutoSwitchPolicy.pickNext(movie.playSources, lineId, triedLines,
                new AutoSwitchPolicy.HealthLookup() {
                    @Override
                    public LineHealth lookup(String id) {
                        return TvBoxApp.get().health().get(healthKey(id));
                    }
                }, now, episodeIndex);
        if (next == null) {
            showError("所有线路均不可用，请稍后重试或返回详情");
            return;
        }
        switchToLine(next, message);
    }

    private void manualChangeLine() {
        if (movie == null) {
            return;
        }
        // 手动换线：按当前顺序取下一条（含冷却的也允许手动尝试）
        int idx = -1;
        for (int i = 0; i < movie.playSources.size(); i++) {
            if (movie.playSources.get(i).lineId.equals(lineId)) {
                idx = i;
                break;
            }
        }
        if (movie.playSources.size() <= 1) {
            showHint("暂无其他线路");
            return;
        }
        PlaySource next = movie.playSources.get((idx + 1) % movie.playSources.size());
        switchToLine(next, "已切换到 " + next.sourceName + "·" + next.lineName);
    }

    private void switchToLine(PlaySource next, String message) {
        saveHistory();
        // 按集标题匹配新线路
        String title = currentEpisode != null ? currentEpisode.title : null;
        lineId = next.lineId;
        currentSource = next;
        int newIndex = 0;
        if (title != null) {
            for (int i = 0; i < next.episodes.size(); i++) {
                if (next.episodes.get(i).title.equals(title)) {
                    newIndex = i;
                    break;
                }
            }
        }
        if (newIndex >= next.episodes.size()) {
            newIndex = Math.min(episodeIndex, next.episodes.size() - 1);
        }
        episodeIndex = newIndex;
        currentEpisode = next.episodes.get(newIndex);
        long pos = player != null ? player.getCurrentPosition() : 0;
        resumePosition = pos;
        triedLines.add(next.lineId);
        showHint(message);
        startPlayback();
    }

    private void switchEpisode(int newIndex) {
        if (currentSource == null) {
            return;
        }
        if (newIndex < 0) {
            showHint("已经是第一集");
            return;
        }
        if (newIndex >= currentSource.episodes.size()) {
            showHint("已经是最后一集");
            return;
        }
        saveHistory();
        episodeIndex = newIndex;
        currentEpisode = currentSource.episodes.get(newIndex);
        resumePosition = 0;
        triedLines.clear();
        startPlayback();
    }

    private void onEpisodeEnded() {
        // 片尾自然结束：进入下一集
        if (currentSource != null && episodeIndex + 1 < currentSource.episodes.size()) {
            saveHistoryForEnded();
            switchEpisode(episodeIndex + 1);
        } else {
            saveHistoryForEnded();
            showHint("已播完最后一集");
            controller.show();
        }
    }

    // ===== 控制操作 =====

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.getPlayWhenReady()) {
            player.setPlayWhenReady(false);
            controller.show();
        } else {
            player.setPlayWhenReady(true);
        }
    }

    private void seekBy(long delta) {
        if (player == null || player.getDuration() <= 0) {
            return;
        }
        long target = Math.max(0, Math.min(player.getCurrentPosition() + delta,
                player.getDuration() - 500));
        player.seekTo(target);
        judger.onSeekPerformed();
        showHint((delta > 0 ? "快进 " : "快退 ") + AppConstants.SEEK_STEP_MS / 1000 + "秒");
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % AppConstants.SPEED_SEQUENCE.length;
        applySpeed(speedIndex);
    }

    private void applySpeed(int index) {
        float speed = AppConstants.SPEED_SEQUENCE[index];
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
        controller.setSpeedLabel(speed);
        showHint("倍速 " + speed + "x");
    }

    // ===== 遥控器按键 =====

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePlayPause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (controller.isShown()) {
                        seekBy(-AppConstants.SEEK_STEP_MS);
                    } else {
                        controller.show();
                        controller.focusPlayToggle();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (controller.isShown()) {
                        seekBy(AppConstants.SEEK_STEP_MS);
                    } else {
                        controller.show();
                        controller.focusPlayToggle();
                    }
                    return true;
                case KeyEvent.KEYCODE_1:
                case KeyEvent.KEYCODE_NUMPAD_1:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    switchEpisode(episodeIndex - 1);
                    return true;
                case KeyEvent.KEYCODE_3:
                case KeyEvent.KEYCODE_NUMPAD_3:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    switchEpisode(episodeIndex + 1);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    cycleSpeed();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (errorOverlay.getVisibility() == View.VISIBLE) {
                        errorOverlay.setVisibility(View.GONE);
                        return true;
                    }
                    if (controller.isShown()) {
                        controller.hide();
                        return true;
                    }
                    finish();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ===== 健康与历史 =====

    private String healthKey(String id) {
        return apiId + "|" + movieId + "|" + id;
    }

    private void recordSuccessOnce() {
        TvBoxApp.get().health().recordSuccess(healthKey(lineId), System.currentTimeMillis());
    }

    private void recordFailForCurrentLine() {
        TvBoxApp.get().health().recordFail(healthKey(lineId), System.currentTimeMillis());
    }

    private void recordSlowForCurrentLine() {
        TvBoxApp.get().health().recordSlow(healthKey(lineId), System.currentTimeMillis());
    }

    private void scheduleHistorySave() {
        handler.removeMessages(MSG_SAVE_HISTORY);
        handler.sendEmptyMessageDelayed(MSG_SAVE_HISTORY, AppConstants.HISTORY_SAVE_INTERVAL_MS);
    }

    private void saveHistoryForEnded() {
        WatchHistoryItem item = buildHistoryItem();
        item.position = 0;
        TvBoxApp.get().history().addOrUpdate(item);
    }

    private void saveHistory() {
        if (movie == null || currentEpisode == null || player == null || !readyRecorded) {
            return;
        }
        TvBoxApp.get().history().addOrUpdate(buildHistoryItem());
    }

    private WatchHistoryItem buildHistoryItem() {
        WatchHistoryItem item = new WatchHistoryItem();
        item.movieId = movieId;
        item.apiLineId = apiId;
        item.apiLineName = currentSource != null ? currentSource.sourceName : "";
        item.movieName = movie.name;
        item.posterUrl = movie.posterUrl == null ? "" : movie.posterUrl;
        item.typeName = movie.typeName == null ? "" : movie.typeName;
        item.remarks = movie.remarks == null ? "" : movie.remarks;
        item.lineIndex = indexOfLine(lineId);
        item.lineId = lineId;
        item.lineName = currentSource != null ? currentSource.lineName : "";
        item.episodeIndex = episodeIndex;
        item.episodeTitle = currentEpisode != null ? currentEpisode.title : "";
        item.episodeUrl = currentEpisode != null ? currentEpisode.url : "";
        long pos = player != null ? player.getCurrentPosition() : resumePosition;
        long dur = player != null ? player.getDuration() : 0;
        item.position = Math.max(0, pos);
        item.duration = dur > 0 ? dur : 0;
        item.updatedAt = System.currentTimeMillis();
        return item;
    }

    private int indexOfLine(String id) {
        if (movie == null) {
            return 0;
        }
        for (int i = 0; i < movie.playSources.size(); i++) {
            if (movie.playSources.get(i).lineId.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private void updateUiTime() {
        if (player != null) {
            controller.updateTime(player.getCurrentPosition(), player.getDuration());
        }
    }

    // ===== 音频焦点 / 广播 =====

    private void requestAudioFocus() {
        if (audioManager == null || audioFocusListener != null) {
            return;
        }
        audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    if (player != null && player.getPlayWhenReady()) {
                        player.setPlayWhenReady(false);
                        controller.show();
                    }
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (player != null) {
                        player.setPlayWhenReady(false);
                    }
                }
            }
        };
        audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusListener != null) {
            audioManager.abandonAudioFocus(audioFocusListener);
            audioFocusListener = null;
        }
    }

    private class BecomingNoisyReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (player != null && player.getPlayWhenReady()) {
                player.setPlayWhenReady(false);
                controller.show();
            }
        }
    }

    private void registerNoisy() {
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver,
                    new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    private void unregisterNoisy() {
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver);
            noisyRegistered = false;
        }
    }

    // ===== 提示 =====

    private void showHint(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hint.setVisibility(View.GONE);
            }
        }, 2500);
    }

    private void hideHint() {
        hint.setVisibility(View.GONE);
    }

    private void showError(String text) {
        buffering.setVisibility(View.GONE);
        errorText.setText(text);
        errorOverlay.setVisibility(View.VISIBLE);
        findViewById(R.id.player_error_retry).requestFocus();
    }

    // ===== 生命周期 =====

    @Override
    protected void onStop() {
        saveHistory();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        controller.destroy();
        releasePlayer();
        if (detailRequest != null) {
            detailRequest.cancel();
        }
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
        abandonAudioFocus();
        unregisterNoisy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeMessages(MSG_SAVE_HISTORY);
    }
}
