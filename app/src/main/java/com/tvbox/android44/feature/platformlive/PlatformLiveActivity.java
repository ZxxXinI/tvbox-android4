package com.tvbox.android44.feature.platformlive;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.data.remote.HttpClients;
import com.tvbox.android44.data.remote.PlatformLiveClient;
import com.tvbox.android44.domain.model.PlatformLive;
import com.tvbox.android44.feature.player.OkHttpDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台直播：Sites → 父分类 → 子分类 → 房间（分页去重）→ 播放器。
 * 返回键严格逐级退栈；每级保存滚动/焦点由 RecyclerView 自身状态承担。
 * 播放恢复：CDN 候选依次切换；地址可重取两次（退避）；用尽后 resolve refresh=1。
 */
public class PlatformLiveActivity extends AppCompatActivity implements Player.Listener {

    private enum Level {SITES, PARENT_CATS, SUB_CATS, ROOMS, PLAYER}

    private StateLayout state;
    private TextView title;
    private TextView status;
    private RecyclerView list;
    private PlatformAdapters.RowAdapter rowAdapter;
    private PlatformAdapters.RoomAdapter roomAdapter;

    private View playerOverlay;
    private com.google.android.exoplayer2.ui.PlayerView playerView;
    private ProgressBar playerBuffering;
    private TextView playerHint;
    private TextView playerError;

    private Level level = Level.SITES;
    private final java.util.Deque<Level> backStack = new java.util.ArrayDeque<Level>();

    private PlatformLive.Site currentSite;
    private PlatformLive.Category currentParentCat;
    private PlatformLive.Category currentSubCat;
    private PlatformLive.Room currentRoom;

    private int roomsPage = 1;
    private boolean roomsHasMore = true;
    private boolean loadingRooms;

    // 播放状态
    private SimpleExoPlayer player;
    private PlatformLive.Stream stream;
    private int candidateIndex = -1;
    private int refreshCount = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            playerHint.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_platform_live);
        state = findViewById(R.id.platform_state);
        title = findViewById(R.id.platform_title);
        status = findViewById(R.id.platform_status);
        list = findViewById(R.id.platform_list);
        playerOverlay = findViewById(R.id.platform_player);
        playerView = findViewById(R.id.platform_player_view);
        playerBuffering = findViewById(R.id.platform_player_buffering);
        playerHint = findViewById(R.id.platform_player_hint);
        playerError = findViewById(R.id.platform_player_error);
        playerView.setUseController(false);

        rowAdapter = new PlatformAdapters.RowAdapter(new PlatformAdapters.RowAdapter.OnRowClick() {
            @Override
            public void onRowClick(String id, String label) {
                onRowClicked(id, label);
            }
        });
        roomAdapter = new PlatformAdapters.RoomAdapter(new PlatformAdapters.RoomAdapter.OnRoomClick() {
            @Override
            public void onRoomClick(PlatformLive.Room room) {
                enterPlayer(room);
            }
        });

        findViewById(R.id.platform_player_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitPlayer();
            }
        });

        loadSites();
    }

    // ===== 数据加载 =====

    private void showAsRows() {
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(rowAdapter);
    }

    private void showAsRooms() {
        list.setLayoutManager(new GridLayoutManager(this, 4));
        list.setAdapter(roomAdapter);
        list.clearOnScrollListeners();
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                maybeLoadMoreRooms();
            }
        });
    }

    private void loadSites() {
        level = Level.SITES;
        title.setText("平台直播");
        showAsRows();
        state.showLoading(null);
        TvBoxApp.get().platformLive().sites(new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<List<PlatformLive.Site>>() {
            @Override
            public void onResult(final Result<List<PlatformLive.Site>> result) {
                if (isFinishing() || level != Level.SITES) {
                    return;
                }
                if (result.isSuccess() && result.data() != null && !result.data().isEmpty()) {
                    List<PlatformAdapters.RowAdapter.Row> rows = new ArrayList<PlatformAdapters.RowAdapter.Row>();
                    for (PlatformLive.Site s : result.data()) {
                        String label = s.name + (s.description == null || s.description.isEmpty()
                                ? "" : " · " + s.description);
                        rows.add(new PlatformAdapters.RowAdapter.Row(s.id, label));
                    }
                    rowAdapter.setRows(rows);
                    state.showContent();
                    if (list.findFocus() == null && list.getChildCount() > 0) {
                        list.getChildAt(0).requestFocus();
                    }
                } else {
                    state.showError("平台列表加载失败，请检查设置中的服务地址（"
                                    + hostOf() + "）后重试。",
                            new StateLayout.OnRetryListener() {
                                @Override
                                public void onRetry() {
                                    loadSites();
                                }
                            });
                }
            }
        });
    }

    private String hostOf() {
        String url = TvBoxApp.get().settings().platformLiveUrl();
        if (url == null || url.isEmpty()) {
            return "未配置";
        }
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private void onRowClicked(String id, String label) {
        if (level == Level.SITES) {
            currentSite = new PlatformLive.Site(id, label, "");
            loadParentCategories();
        } else if (level == Level.PARENT_CATS) {
            currentParentCat = new PlatformLive.Category(id, "p", label, null);
            loadSubCategories(id);
        } else if (level == Level.SUB_CATS) {
            currentSubCat = new PlatformLive.Category(id, "s", label, null);
            loadRooms(true);
        }
    }

    private void loadParentCategories() {
        backStack.push(level);
        level = Level.PARENT_CATS;
        title.setText(currentSite.name + " · 分类");
        showAsRows();
        state.showLoading(null);
        TvBoxApp.get().platformLive().categories(currentSite.id, null,
                new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<List<PlatformLive.Category>>() {
                    @Override
                    public void onResult(final Result<List<PlatformLive.Category>> result) {
                        if (isFinishing() || level != Level.PARENT_CATS) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            List<PlatformLive.Category> cats = result.data();
                            boolean hasParent = false;
                            for (PlatformLive.Category c : cats) {
                                if (c.parentId == null || c.parentId.isEmpty()) {
                                    hasParent = true;
                                    break;
                                }
                            }
                            if (!hasParent && !cats.isEmpty()) {
                                // 没有父分类的平台：直接展示子分类
                                currentParentCat = null;
                                setSubCategories(cats);
                                return;
                            }
                            List<PlatformAdapters.RowAdapter.Row> rows =
                                    new ArrayList<PlatformAdapters.RowAdapter.Row>();
                            for (PlatformLive.Category c : cats) {
                                if (c.parentId == null || c.parentId.isEmpty()) {
                                    rows.add(new PlatformAdapters.RowAdapter.Row(c.id, c.name));
                                }
                            }
                            if (rows.isEmpty()) {
                                state.showEmpty("该平台没有可用分类");
                            } else {
                                rowAdapter.setRows(rows);
                                state.showContent();
                                if (list.findFocus() == null && list.getChildCount() > 0) {
                                    list.getChildAt(0).requestFocus();
                                }
                            }
                        } else {
                            showErrorWithBack(result);
                        }
                    }
                });
    }

    private void loadSubCategories(String parentId) {
        level = Level.SUB_CATS;
        title.setText(currentSite.name + " · " + (currentParentCat != null ? currentParentCat.name : "分类"));
        showAsRows();
        state.showLoading(null);
        TvBoxApp.get().platformLive().categories(currentSite.id, parentId,
                new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<List<PlatformLive.Category>>() {
                    @Override
                    public void onResult(Result<List<PlatformLive.Category>> result) {
                        if (isFinishing() || level != Level.SUB_CATS) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            setSubCategories(result.data());
                        } else {
                            showErrorWithBack(result);
                        }
                    }
                });
    }

    private void setSubCategories(List<PlatformLive.Category> cats) {
        List<PlatformAdapters.RowAdapter.Row> rows = new ArrayList<PlatformAdapters.RowAdapter.Row>();
        for (PlatformLive.Category c : cats) {
            rows.add(new PlatformAdapters.RowAdapter.Row(c.id, c.name));
        }
        if (rows.isEmpty()) {
            state.showEmpty("该分类下没有子分类");
        } else {
            rowAdapter.setRows(rows);
            state.showContent();
            if (list.findFocus() == null && list.getChildCount() > 0) {
                list.getChildAt(0).requestFocus();
            }
        }
    }

    private void loadRooms(final boolean reset) {
        if (reset) {
            backStack.push(level == Level.ROOMS ? Level.SUB_CATS : level);
            level = Level.ROOMS;
            roomAdapter.clear();
            roomsPage = 1;
            roomsHasMore = true;
            showAsRooms();
        }
        title.setText(currentSite.name + " · " + currentSubCat.name);
        state.showLoading(null);
        loadingRooms = true;
        TvBoxApp.get().platformLive().rooms(currentSite.id, currentSubCat.id, roomsPage,
                new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<PlatformLiveClient.RoomsPage>() {
                    @Override
                    public void onResult(Result<PlatformLiveClient.RoomsPage> result) {
                        if (isFinishing() || level != Level.ROOMS) {
                            return;
                        }
                        loadingRooms = false;
                        if (result.isSuccess() && result.data() != null) {
                            PlatformLiveClient.RoomsPage page = result.data();
                            int added = roomAdapter.appendRooms(page.rooms);
                            roomsHasMore = page.hasMore && added > 0;
                            if (roomAdapter.getItemCount() == 0) {
                                state.showEmpty("该分类暂无直播间");
                            } else {
                                state.showContent();
                                if (reset && list.findFocus() == null && list.getChildCount() > 0) {
                                    list.getChildAt(0).requestFocus();
                                }
                            }
                        } else if (roomAdapter.getItemCount() == 0) {
                            showErrorWithBack(result);
                        } else {
                            // 服务端失败时保留已加载列表
                            status.setText("加载更多失败，可稍后重试");
                            status.setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    private void maybeLoadMoreRooms() {
        if (loadingRooms || !roomsHasMore || level != Level.ROOMS) {
            return;
        }
        GridLayoutManager lm = (GridLayoutManager) list.getLayoutManager();
        int last = lm.findLastVisibleItemPosition();
        int total = roomAdapter.getItemCount();
        if (total > 0 && last >= total - 6) {
            roomsPage++;
            loadRooms(false);
        }
    }

    private void showErrorWithBack(Result<?> result) {
        String msg = result.asFailure() != null ? result.asFailure().userMessage : "加载失败";
        state.showError(msg, new StateLayout.OnRetryListener() {
            @Override
            public void onRetry() {
                goBackOneLevel();
            }
        });
    }

    // ===== 播放器（房间解析） =====

    private void enterPlayer(PlatformLive.Room room) {
        currentRoom = room;
        playerOverlay.setVisibility(View.VISIBLE);
        playerError.setVisibility(View.GONE);
        playerBuffering.setVisibility(View.VISIBLE);
        showPlayerHint("正在解析 " + (room.title == null || room.title.isEmpty() ? room.roomId : room.title) + " …", true);
        refreshCount = 0;
        resolveAndPlay(false);
    }

    private void resolveAndPlay(final boolean refresh) {
        TvBoxApp.get().platformLive().resolve(currentRoom.siteId, currentRoom.roomId, refresh,
                new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<PlatformLive.Stream>() {
                    @Override
                    public void onResult(Result<PlatformLive.Stream> result) {
                        if (isFinishing() || playerOverlay.getVisibility() != View.VISIBLE) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null
                                && !result.data().candidates.isEmpty()) {
                            stream = result.data();
                            candidateIndex = 0;
                            playCandidate();
                        } else {
                            String msg = result.asFailure() != null
                                    ? result.asFailure().userMessage : "解析失败";
                            if ("EMPTY_BODY".equals(msg)) {
                                msg = "未获取到可播放地址，房间可能未开播";
                            }
                            playerBuffering.setVisibility(View.GONE);
                            playerError.setText(msg);
                            playerError.setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    private void playCandidate() {
        if (stream == null || candidateIndex < 0
                || candidateIndex >= stream.candidates.size()) {
            onAllCandidatesFailed();
            return;
        }
        PlatformLive.StreamCandidate c = stream.candidates.get(candidateIndex);
        releasePlayer();
        player = new SimpleExoPlayer.Builder(this).build();
        player.addListener(this);
        playerView.setPlayer(player);
        OkHttpDataSource.Factory base =
                new OkHttpDataSource.Factory(HttpClients.client(), HttpClients.DEFAULT_UA);
        for (Map.Entry<String, String> h : stream.headers.entrySet()) {
            base.setDefaultRequestProperty(h.getKey(), h.getValue());
        }
        DataSource.Factory dsFactory = new DefaultDataSourceFactory(this, base);
        MediaItem item = MediaItem.fromUri(c.url);
        MediaSource source;
        if (c.url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) {
            source = new HlsMediaSource.Factory(dsFactory).createMediaSource(item);
        } else {
            source = new DefaultMediaSourceFactory(dsFactory).createMediaSource(item);
        }
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        playerBuffering.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPlayerHint(currentRoom.title + " · " + (c.quality == null || c.quality.isEmpty()
                ? c.name : c.quality), true);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (player == null) {
            return;
        }
        if (state == Player.STATE_READY) {
            playerBuffering.setVisibility(View.GONE);
        } else if (state == Player.STATE_BUFFERING) {
            playerBuffering.setVisibility(View.VISIBLE);
        } else if (state == Player.STATE_ENDED) {
            // 直播流结束：切下一候选
            tryNextCandidate();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        tryNextCandidate();
    }

    /** 当前临时地址可重取两次（退避）；全部候选失效后 refresh=1 强制刷新。 */
    private void tryNextCandidate() {
        if (stream == null || playerOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        candidateIndex++;
        if (candidateIndex < stream.candidates.size()) {
            showPlayerHint("线路中断，切换到 " + stream.candidates.get(candidateIndex).name, false);
            playCandidate();
            return;
        }
        // 候选用尽：同一地址重取（最多 2 次，退避）
        refreshCount++;
        if (refreshCount <= 2) {
            long backoff = 1500L * refreshCount;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && playerOverlay.getVisibility() == View.VISIBLE) {
                        showPlayerHint("正在恢复直播地址（" + refreshCount + "/2）…", true);
                        resolveAndPlay(false);
                    }
                }
            }, backoff);
            return;
        }
        onAllCandidatesFailed();
    }

    private void onAllCandidatesFailed() {
        // 最后手段：强制刷新临时地址
        if (refreshCount <= 3) {
            refreshCount++;
            showPlayerHint("正在强制刷新播放地址…", true);
            resolveAndPlay(true);
            return;
        }
        playerBuffering.setVisibility(View.GONE);
        playerError.setText("直播暂时不可用，请稍后重试或返回房间列表");
        playerError.setVisibility(View.VISIBLE);
        releasePlayer();
    }

    private void showPlayerHint(String text, boolean longer) {
        playerHint.setText(text);
        playerHint.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, longer ? 4000 : 2500);
    }

    private void exitPlayer() {
        releasePlayer();
        playerOverlay.setVisibility(View.GONE);
        main.removeCallbacks(hideHint);
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ===== 返回逐级退栈 =====

    @Override
    public void onBackPressed() {
        if (playerOverlay.getVisibility() == View.VISIBLE) {
            exitPlayer();
            return;
        }
        if (!goBackOneLevel()) {
            super.onBackPressed();
        }
    }

    private boolean goBackOneLevel() {
        if (backStack.isEmpty()) {
            return false;
        }
        Level target = backStack.pop();
        switch (target) {
            case SITES:
                loadSites();
                return true;
            case PARENT_CATS:
                loadParentCategories();
                return true;
            case SUB_CATS:
                if (currentParentCat != null) {
                    loadSubCategories(currentParentCat.id);
                } else {
                    loadParentCategories();
                }
                return true;
            case ROOMS:
                loadRooms(true);
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && playerOverlay.getVisibility() == View.VISIBLE) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                return super.dispatchKeyEvent(event);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStop() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
