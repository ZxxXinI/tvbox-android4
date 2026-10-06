package com.tvbox.android44.feature.player;

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
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
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
import com.tvbox.android44.domain.playback.PlaybackSelection;

import java.util.ArrayList;
import java.util.List;

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
    public static final String EXTRA_HISTORY_ITEM = "historyItem";
    public static final String EXTRA_USE_LAST_URL = "useLastUrl";
    public static final int REQUEST_PLAYBACK = 2001;

    private static final int MSG_SAVE_HISTORY = 1;
    private static final int MSG_UPDATE_TIME = 2;

    public static void startForResult(android.app.Activity from, String apiId, String movieId,
                                      String lineId, int episodeIndex) {
        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra(EXTRA_API_ID, apiId);
        i.putExtra(EXTRA_MOVIE_ID, movieId);
        i.putExtra(EXTRA_LINE_ID, lineId);
        i.putExtra(EXTRA_EPISODE, episodeIndex);
        from.startActivityForResult(i, REQUEST_PLAYBACK);
    }

    public static void startForResultWithHistory(android.app.Activity from, String apiId,
                                                  String movieId, String lineId, int episodeIndex,
                                                  long resumePosition, String fallbackUrl,
                                                  String episodeTitle) {
        WatchHistoryItem saved = new WatchHistoryItem();
        saved.apiLineId = apiId; saved.movieId = movieId; saved.lineId = lineId;
        saved.episodeIndex = episodeIndex; saved.position = resumePosition;
        saved.episodeUrl = fallbackUrl; saved.episodeTitle = episodeTitle;
        startForResultWithHistory(from, saved, false);
    }

    public static void startForResultWithHistory(android.app.Activity from, WatchHistoryItem saved, boolean useLastUrl) {
        Intent intent = new Intent(from, PlayerActivity.class);
        intent.putExtra(EXTRA_API_ID, saved.apiLineId);
        intent.putExtra(EXTRA_MOVIE_ID, saved.movieId);
        intent.putExtra(EXTRA_LINE_ID, saved.lineId);
        intent.putExtra(EXTRA_EPISODE, saved.episodeIndex);
        intent.putExtra(EXTRA_RESUME_POSITION, saved.position);
        intent.putExtra(EXTRA_FALLBACK_URL, saved.episodeUrl);
        intent.putExtra(EXTRA_HISTORY_ITEM, new WatchHistoryItem(saved));
        intent.putExtra(EXTRA_USE_LAST_URL, useLastUrl);
        from.startActivityForResult(intent, REQUEST_PLAYBACK);
    }

    private PlayerView playerView;
    private ExoPlayer player;
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
    private WatchHistoryItem resumeHistory;
    private VodMediaSources mediaSources;
    private boolean mediaHls;
    private boolean mediaFallbackUsed;
    private boolean stopped;
    private boolean usingLastUrl;
    private boolean resumeWhenVisible = true;
    private boolean restoringSession;

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
                pollBuffering();
                if (!stopped && player != null) sendEmptyMessageDelayed(MSG_UPDATE_TIME, 500);
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
        resumeHistory = (WatchHistoryItem) getIntent().getSerializableExtra(EXTRA_HISTORY_ITEM);
        if (savedInstanceState != null) {
            restoringSession = true;
            resumeHistory = (WatchHistoryItem) savedInstanceState.getSerializable(EXTRA_HISTORY_ITEM);
            if (resumeHistory != null) {
                lineId = resumeHistory.lineId; episodeIndex = resumeHistory.episodeIndex;
                resumePosition = resumeHistory.position;
            }
        }

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
                    showHint(getString(R.string.player_double_speed));
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
                showHint(getString(R.string.player_seek_to, PlayerControllerView.formatTime(seekTargetFromDrag)));
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
                    showHint(getString(R.string.player_brightness, (int) (next * 100)));
                } else {
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int next = Math.max(0, Math.min(max, cur + delta / 20));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                    showHint(getString(R.string.player_volume, next, max));
                }
            }
        }).bind(playerView);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        judger = new BufferJudger(BufferJudger.Mode.VOD, new BufferJudger.Clock() {
            @Override
            public long now() {
                return playbackTime();
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

        if (getIntent().getBooleanExtra(EXTRA_USE_LAST_URL, false)) tryFallbackUrl();
        else loadDetailAndPlay();
    }

    protected long playbackTime() { return android.os.SystemClock.elapsedRealtime(); }

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
                        if (isFinishing() || isDestroyed()) {
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
        if (fallbackUrl != null && okhttp3.HttpUrl.parse(fallbackUrl) != null) {
            usingLastUrl = true;
            if (lineId == null || lineId.isEmpty()) lineId = "history";
            showHint(getString(R.string.player_fallback_hint));
            movie = new Movie(movieId, apiId, resumeHistory == null ? "" : resumeHistory.apiLineName,
                    resumeHistory == null || resumeHistory.movieName == null ? getString(R.string.history_playback) : resumeHistory.movieName);
            if (resumeHistory != null) {
                movie.posterUrl = resumeHistory.posterUrl; movie.typeName = resumeHistory.typeName;
                movie.remarks = resumeHistory.remarks;
                if (resumeHistory.duration > 0 && resumePosition >= resumeHistory.duration * AppConstants.HISTORY_END_RESTART_RATIO) resumePosition = 0;
            }
            currentSource = new PlaySource(lineId, resumeHistory == null ? getString(R.string.history_line) : resumeHistory.lineName,
                    resumeHistory == null ? getString(R.string.history_playback) : resumeHistory.apiLineName);
            // A one-item temporary playlist has no valid previous/next episode.
            currentEpisode = new PlayEpisode(0, resumeHistory == null ? getString(R.string.history_episode) : resumeHistory.episodeTitle, fallbackUrl);
            currentSource.episodes.add(currentEpisode);
            movie.playSources.add(currentSource);
            episodeIndex = 0;
            startPlayback();
        } else {
            showError("影片详情加载失败，请返回重试");
        }
    }

    private void resolveLineAndEpisode() {
        WatchHistoryItem selection = resumeHistory;
        if (selection == null) {
            selection = new WatchHistoryItem();
            selection.lineId = lineId; selection.episodeIndex = episodeIndex;
            selection.position = resumePosition;
        }
        PlaybackSelection resolved = PlaybackSelection.resolve(movie, selection, resumeHistory != null && !restoringSession);
        if (resolved == null) {
            showError("没有可播放的线路");
            return;
        }
        currentSource = resolved.source; lineId = currentSource.lineId;
        episodeIndex = resolved.episodeIndex; resumePosition = resolved.position;
        currentEpisode = currentSource.episodes.get(episodeIndex);
        startPlayback();
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
        if (stopped) return;
        mediaSources = new VodMediaSources(buildDataSourceFactory());
        mediaHls = VodMediaSources.isHls(currentEpisode.url);
        mediaFallbackUsed = false;
        readyRecorded = false;
        judger.reset();
        player = createPlayer();
        player.addListener(this);
        playerView.setPlayer(player);

        MediaSource mediaSource = mediaSources.create(currentEpisode.url, mediaHls);
        player.setMediaSource(mediaSource, resumePosition > 0 ? resumePosition : 0);
        player.prepare();
        player.setPlayWhenReady(true);
        playing = true;
        applySpeed(speedIndex);
        requestAudioFocus();
        registerNoisy();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buffering.setVisibility(View.VISIBLE);
        int displayEpisode = usingLastUrl && resumeHistory != null ? resumeHistory.episodeIndex : episodeIndex;
        controller.setTitles(movie.name,
                currentSource.sourceName + "·" + currentSource.lineName
                        + " · 第" + (displayEpisode + 1) + "集 " + currentEpisode.title);
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

    protected ExoPlayer createPlayer() {
        return new SimpleExoPlayer.Builder(this, new DefaultRenderersFactory(this))
                .setTrackSelector(new DefaultTrackSelector(this)).build();
    }

    private void pollBuffering() {
        if (player == null || stopped || errorOverlay.getVisibility() == View.VISIBLE) return;
        judger.onPaused(!player.getPlayWhenReady());
        if (player.getPlaybackState() == Player.STATE_BUFFERING) onBufferingChanged(true);
    }

    // ===== 播放器事件 =====

    @Override
    public void onPlaybackStateChanged(int state) {
        if (player == null) {
            return;
        }
        switch (state) {
            case Player.STATE_READY:
                Player active = player;
                judger.onPaused(!player.getPlayWhenReady());
                onBufferingChanged(false);
                if (player != active) return;
                buffering.setVisibility(View.GONE);
                if (!readyRecorded) {
                    readyRecorded = true;
                    recordSuccessOnce();
                    scheduleHistorySave();
                    if (resumePosition > 0) {
                        showHint(getString(R.string.player_resume_hint, PlayerControllerView.formatTime(resumePosition)));
                        resumePosition = 0;
                    }
                }
                break;
            case Player.STATE_BUFFERING:
                buffering.setVisibility(View.VISIBLE);
                judger.onPaused(!player.getPlayWhenReady());
                onBufferingChanged(true);
                break;
            case Player.STATE_IDLE:
                buffering.setVisibility(View.GONE);
                judger.reset();
                break;
            case Player.STATE_ENDED:
                buffering.setVisibility(View.GONE);
                judger.reset();
                onEpisodeEnded();
                break;
            default:
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        playing = isPlaying;
        controller.setPlaying(isPlaying);
        if (isPlaying) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        judger.onPaused(!playWhenReady);
        if (!playWhenReady) saveHistory();
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
            showHint(getString(R.string.player_slow_hint));
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
        if (player == null || stopped) return;
        if (!mediaFallbackUsed && mediaSources.shouldFallback(error, mediaHls)) {
            mediaFallbackUsed = true;
            mediaHls = !mediaHls;
            long position = Math.max(0, Math.max(resumePosition, player.getCurrentPosition()));
            judger.reset();
            player.setMediaSource(mediaSources.create(currentEpisode.url, mediaHls), position);
            player.prepare();
            showHint(getString(R.string.media_type_retry));
            return;
        }
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
            showHint(getString(R.string.player_no_other_line));
            return;
        }
        PlaySource next = movie.playSources.get((idx + 1) % movie.playSources.size());
        switchToLine(next, "已切换到 " + next.sourceName + "·" + next.lineName);
    }

    private void switchToLine(PlaySource next, String message) {
        if (next.episodes.isEmpty()) { showHint(getString(R.string.line_no_episodes)); return; }
        saveHistory();
        // 按集标题匹配新线路
        String title = currentEpisode != null ? currentEpisode.title : null;
        lineId = next.lineId;
        currentSource = next;
        int newIndex = Math.max(0, Math.min(episodeIndex, next.episodes.size() - 1));
        if (title != null) {
            for (int i = 0; i < next.episodes.size(); i++) {
                if (next.episodes.get(i).title.equals(title)) {
                    newIndex = i;
                    break;
                }
            }
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
            showHint(getString(R.string.player_first_episode));
            return;
        }
        if (newIndex >= currentSource.episodes.size()) {
            showHint(getString(R.string.player_last_episode));
            return;
        }
        if (player == null || player.getPlaybackState() != Player.STATE_ENDED) saveHistory();
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
            showHint(getString(R.string.player_finished));
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
        showHint(getString(delta > 0 ? R.string.player_seek_forward : R.string.player_seek_backward,
                AppConstants.SEEK_STEP_MS / 1000));
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
        showHint(getString(R.string.player_speed, String.valueOf(speed)));
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
        persistHistory(item);
    }

    private void saveHistory() {
        if (movie == null || currentEpisode == null || player == null || !readyRecorded) {
            return;
        }
        persistHistory(buildHistoryItem());
    }

    private void persistHistory(final WatchHistoryItem item) {
        TvBoxApp.get().executors().disk().execute(new Runnable() {
            public void run() {
                if (!TvBoxApp.get().history().addOrUpdate(item)) {
                    android.util.Log.w("TVBOX_HISTORY", "历史记录保存失败");
                }
            }
        });
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
        item.position = player != null && player.getPlaybackState() == Player.STATE_ENDED ? 0 : Math.max(0, pos);
        item.duration = dur > 0 ? dur : 0;
        item.updatedAt = System.currentTimeMillis();
        if (usingLastUrl && resumeHistory != null) {
            item.episodeIndex = resumeHistory.episodeIndex;
            item.lineIndex = resumeHistory.lineIndex;
        }
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

    @Override public void finish() {
        if (movie != null && currentEpisode != null) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_HISTORY_ITEM, buildHistoryItem()));
        }
        super.finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (movie != null && currentEpisode != null) out.putSerializable(EXTRA_HISTORY_ITEM, buildHistoryItem());
    }

    @Override protected void onStart() {
        super.onStart();
        stopped = false;
        if (player != null) {
            handler.removeMessages(MSG_UPDATE_TIME);
            handler.sendEmptyMessage(MSG_UPDATE_TIME);
        } else if (currentEpisode != null) {
            startPlayback();
            if (player != null) player.setPlayWhenReady(resumeWhenVisible);
        }
    }

    @Override
    protected void onStop() {
        saveHistory();
        stopped = true;
        handler.removeMessages(MSG_UPDATE_TIME);
        handler.removeMessages(MSG_SAVE_HISTORY);
        if (player != null) {
            resumeWhenVisible = player.getPlayWhenReady();
            resumePosition = Math.max(0, player.getCurrentPosition());
            if (player.getPlaybackState() == Player.STATE_ENDED) resumePosition = 0;
        }
        releasePlayer();
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
        handler.removeMessages(MSG_UPDATE_TIME);
    }
}
