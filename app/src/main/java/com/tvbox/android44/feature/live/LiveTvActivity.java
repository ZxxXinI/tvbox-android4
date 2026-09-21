package com.tvbox.android44.feature.live;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.remote.HttpClients;
import com.tvbox.android44.domain.model.LiveChannel;
import com.tvbox.android44.domain.model.LiveChannelGroup;
import com.tvbox.android44.domain.model.LiveChannelLine;
import com.tvbox.android44.domain.playback.BufferJudger;
import com.tvbox.android44.domain.playback.IptvMediaTypeDetector;
import com.tvbox.android44.feature.player.OkHttpDataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 电视直播：全屏播放 + 可隐藏频道列表。
 * 左右切台、上下切线（首尾循环）、确认显隐列表、数字选台（短延迟提交）。
 * 故障恢复：错误/结束/连续或频繁或累计缓冲/4 秒无进度 → 换本频道下一条未尝试线路；
 * 全部线路失败后停止自动循环；手动重试清空尝试集合。
 */
public class LiveTvActivity extends AppCompatActivity implements Player.Listener {

    private static final int MSG_HIDE_HINT = 1;
    private static final int MSG_POSITION_POLL = 2;
    private static final int MSG_COMMIT_DIGITS = 3;

    private SimpleExoPlayer player;
    private ProgressBar buffering;
    private TextView hint;
    private TextView digitView;
    private View channelPanel;
    private RecyclerView channelList;

    private List<LiveChannelGroup> groups = new ArrayList<LiveChannelGroup>();
    private final List<LiveChannel> flatChannels = new ArrayList<LiveChannel>();
    private ChannelAdapter adapter;

    private int channelIndex = -1;
    private int lineIndex = 0;
    private final Set<String> failedLinesThisSession = new HashSet<String>();
    private BufferJudger judger;
    private boolean recovering;
    private final StringBuilder digits = new StringBuilder();

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HIDE_HINT:
                    hint.setVisibility(View.GONE);
                    break;
                case MSG_POSITION_POLL:
                    pollPosition();
                    sendEmptyMessageDelayed(MSG_POSITION_POLL, 1000);
                    break;
                case MSG_COMMIT_DIGITS:
                    commitDigits();
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_tv);
        buffering = findViewById(R.id.live_buffering);
        hint = findViewById(R.id.live_hint);
        digitView = findViewById(R.id.live_digit);
        channelPanel = findViewById(R.id.live_channel_panel);
        channelList = findViewById(R.id.live_channel_list);

        com.google.android.exoplayer2.ui.PlayerView pv = findViewById(R.id.live_player_view);
        pv.setUseController(false);
        pv.requestFocus();

        channelList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChannelAdapter(new ChannelAdapter.OnChannelPick() {
            @Override
            public void onChannelPicked(int flatIndex) {
                toggleChannelList(false);
                manualSwitchChannel(flatIndex);
            }
        });
        channelList.setAdapter(adapter);

        judger = new BufferJudger(BufferJudger.Mode.LIVE, new BufferJudger.Clock() {
            @Override
            public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }
        });

        findViewById(R.id.live_root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleChannelList(channelPanel.getVisibility() != View.VISIBLE);
            }
        });

        loadChannels(false);
    }

    private void loadChannels(final boolean forceRefresh) {
        buffering.setVisibility(View.VISIBLE);
        TvBoxApp.get().live().load(forceRefresh, new com.tvbox.android44.data.repository.LiveRepository.Callback() {
            @Override
            public void onResult(Result<List<LiveChannelGroup>> result) {
                if (isFinishing()) {
                    return;
                }
                if (result.isSuccess() && result.data() != null && !result.data().isEmpty()) {
                    setChannels(result.data());
                } else {
                    buffering.setVisibility(View.GONE);
                    String msg = result.asFailure() != null
                            ? result.asFailure().userMessage
                            : "直播源为空";
                    Toast.makeText(LiveTvActivity.this,
                            "频道加载失败：" + msg + "，按返回键退出，菜单键刷新",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void setChannels(List<LiveChannelGroup> data) {
        groups = data;
        flatChannels.clear();
        for (LiveChannelGroup g : data) {
            for (LiveChannel c : g.channels) {
                flatChannels.add(c);
            }
        }
        if (flatChannels.isEmpty()) {
            buffering.setVisibility(View.GONE);
            Toast.makeText(this, "直播源没有可播放频道", Toast.LENGTH_LONG).show();
            return;
        }
        adapter.setChannels(flatChannels);
        // 初次进入：上次频道（按分组+名称记忆）或第一个频道
        int start = 0;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String lastGroup = prefs.getString("lastGroup", null);
        String lastName = prefs.getString("lastName", null);
        if (lastGroup != null && lastName != null) {
            for (int i = 0; i < flatChannels.size(); i++) {
                LiveChannel c = flatChannels.get(i);
                if (lastGroup.equals(c.groupName) && lastName.equals(c.name)) {
                    start = i;
                    break;
                }
            }
        }
        playChannel(start, 0, false);
    }

    // ===== 播放控制 =====

    private void playChannel(int channelIdx, int lineIdx, boolean keepLineIndex) {
        if (channelIdx < 0 || channelIdx >= flatChannels.size()) {
            return;
        }
        this.channelIndex = channelIdx;
        LiveChannel channel = flatChannels.get(channelIdx);
        if (channel.lines.isEmpty()) {
            showHint("频道无可用线路");
            return;
        }
        if (!keepLineIndex) {
            this.lineIndex = 0;
        } else {
            this.lineIndex = Math.min(Math.max(lineIdx, 0), channel.lines.size() - 1);
        }
        failedLinesThisSession.clear();
        judger.reset();
        playCurrentLine();
        rememberChannel(channel);
    }

    private void playCurrentLine() {
        LiveChannel channel = flatChannels.get(channelIndex);
        LiveChannelLine line = channel.lines.get(lineIndex);
        releasePlayer();
        player = new SimpleExoPlayer.Builder(this).build();
        player.addListener(this);
        ((com.google.android.exoplayer2.ui.PlayerView) findViewById(R.id.live_player_view))
                .setPlayer(player);
        DataSource.Factory dsFactory = new DefaultDataSourceFactory(this,
                new OkHttpDataSource.Factory(HttpClients.client(), HttpClients.DEFAULT_UA));
        MediaItem item = MediaItem.fromUri(line.url);
        com.google.android.exoplayer2.source.MediaSource source;
        if (IptvMediaTypeDetector.isHlsUrl(line.url)) {
            source = new HlsMediaSource.Factory(dsFactory).createMediaSource(item);
        } else {
            source = new DefaultMediaSourceFactory(dsFactory).createMediaSource(item);
        }
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        buffering.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showHint(channel.number + " " + channel.name + " · 线路"
                + (lineIndex + 1) + "/" + channel.lines.size()
                + (channel.lines.size() > 1 ? "（上/下键换线）" : ""));
        handler.removeMessages(MSG_POSITION_POLL);
        handler.sendEmptyMessage(MSG_POSITION_POLL);
    }

    private void rememberChannel(LiveChannel channel) {
        getPreferences(MODE_PRIVATE).edit()
                .putString("lastGroup", channel.groupName)
                .putString("lastName", channel.name)
                .apply();
    }

    // ===== 切台 / 切线 / 数字选台 =====

    /** 手动切台：重置当前频道的自动恢复计数。 */
    private void manualSwitchChannel(int idx) {
        playChannel(idx, 0, false);
    }

    private void switchChannel(int delta) {
        if (flatChannels.isEmpty()) {
            return;
        }
        int next = (channelIndex + delta + flatChannels.size()) % flatChannels.size();
        manualSwitchChannel(next);
    }

    private void switchLine(int delta) {
        LiveChannel channel = flatChannels.get(channelIndex);
        if (channel.lines.size() <= 1) {
            showHint("当前频道只有一条线路");
            return;
        }
        int next = (lineIndex + delta + channel.lines.size()) % channel.lines.size();
        lineIndex = next;
        judger.reset();
        playCurrentLine();
    }

    private void appendDigit(int d) {
        if (digits.length() >= 4) {
            digits.setLength(0);
        }
        digits.append(d);
        digitView.setText(digits.toString());
        digitView.setVisibility(View.VISIBLE);
        handler.removeMessages(MSG_COMMIT_DIGITS);
        handler.sendEmptyMessageDelayed(MSG_COMMIT_DIGITS, AppConstants.LIVE_DIGIT_COMMIT_DELAY_MS);
    }

    private void commitDigits() {
        String s = digits.toString();
        digits.setLength(0);
        digitView.setVisibility(View.GONE);
        if (s.isEmpty()) {
            return;
        }
        try {
            int num = Integer.parseInt(s);
            if (num >= 1 && num <= flatChannels.size()) {
                manualSwitchChannel(num - 1);
            } else {
                showHint("频道号 " + num + " 不存在（1-" + flatChannels.size() + "）");
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void toggleChannelList(boolean show) {
        channelPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            adapter.notifyDataSetChanged();
            // 焦点落在当前频道
            channelList.scrollToPosition(channelIndex);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    RecyclerView.ViewHolder vh =
                            channelList.findViewHolderForAdapterPosition(channelIndex);
                    if (vh != null) {
                        vh.itemView.requestFocus();
                    }
                }
            }, 100);
        } else {
            findViewById(R.id.live_player_view).requestFocus();
        }
    }

    // ===== 播放器事件与故障恢复 =====

    @Override
    public void onPlaybackStateChanged(int state) {
        if (player == null) {
            return;
        }
        if (state == Player.STATE_READY) {
            buffering.setVisibility(View.GONE);
        } else if (state == Player.STATE_BUFFERING) {
            buffering.setVisibility(View.VISIBLE);
        } else if (state == Player.STATE_ENDED) {
            // 直播流结束：尝试下一条线路
            recoverToNextLine("直播流结束");
        }
        BufferJudger.Verdict v = judger.onBufferingChanged(state == Player.STATE_BUFFERING);
        if (v != BufferJudger.Verdict.NONE) {
            recoverToNextLine(lineLabel(v));
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        recoverToNextLine("播放错误");
    }

    private void pollPosition() {
        if (player == null) {
            return;
        }
        BufferJudger.Verdict v = judger.onPositionPoll(player.getCurrentPosition());
        if (v == BufferJudger.Verdict.NO_PROGRESS) {
            recoverToNextLine("画面停滞");
        }
    }

    private static String lineLabel(BufferJudger.Verdict v) {
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

    /**
     * 恢复顺序：标记当前线路本会话已失败 → 尝试下一条未尝试线路；
     * 全部失败后停止自动循环（不弹阻断对话框，只显示状态）。
     */
    private void recoverToNextLine(String reason) {
        if (recovering || channelIndex < 0 || flatChannels.isEmpty()) {
            return;
        }
        LiveChannel channel = flatChannels.get(channelIndex);
        failedLinesThisSession.add(lineIndex + "");
        int next = -1;
        for (int i = 0; i < channel.lines.size(); i++) {
            if (!failedLinesThisSession.contains(i + "")) {
                next = i;
                break;
            }
        }
        if (next < 0) {
            recovering = false;
            buffering.setVisibility(View.GONE);
            showHint("当前频道所有线路暂不可用，按确认键打开列表换台，菜单键刷新");
            return;
        }
        recovering = true;
        lineIndex = next;
        judger.reset();
        showHint(reason + "，自动切换线路" + (lineIndex + 1));
        playCurrentLine();
        recovering = false;
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.removeMessages(MSG_HIDE_HINT);
        handler.sendEmptyMessageDelayed(MSG_HIDE_HINT, 3000);
    }

    // ===== 按键 =====

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (channelPanel.getVisibility() == View.VISIBLE) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (channelPanel.getVisibility() == View.VISIBLE) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchChannel(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (channelPanel.getVisibility() == View.VISIBLE) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchLine(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (channelPanel.getVisibility() == View.VISIBLE) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchLine(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    toggleChannelList(channelPanel.getVisibility() != View.VISIBLE);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    // 手动重试：清空尝试集合并强制刷新源
                    failedLinesThisSession.clear();
                    loadChannels(true);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (digitView.getVisibility() == View.VISIBLE) {
                        commitDigits();
                        return true;
                    }
                    if (channelPanel.getVisibility() == View.VISIBLE) {
                        toggleChannelList(false);
                        return true;
                    }
                    finish();
                    return true;
                default:
                    int digit = digitFromKey(event.getKeyCode());
                    if (digit >= 0 && channelPanel.getVisibility() != View.VISIBLE) {
                        appendDigit(digit);
                        return true;
                    }
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static int digitFromKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                return 0;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_NUMPAD_1:
                return 1;
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_2:
                return 2;
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_3:
                return 3;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_NUMPAD_4:
                return 4;
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_NUMPAD_5:
                return 5;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6:
                return 6;
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_NUMPAD_7:
                return 7;
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_NUMPAD_8:
                return 8;
            case KeyEvent.KEYCODE_9:
            case KeyEvent.KEYCODE_NUMPAD_9:
                return 9;
            default:
                return -1;
        }
    }

    @Override
    protected void onStop() {
        // 首版统一暂停直播
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeMessages(MSG_POSITION_POLL);
    }

    // ===== 频道列表适配器（分组头 + 频道行） =====

    static final class ChannelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_CHANNEL = 1;

        interface OnChannelPick {
            void onChannelPicked(int flatIndex);
        }

        private final List<LiveChannel> channels = new ArrayList<LiveChannel>();
        private final OnChannelPick pick;
        private int playingFlatIndex = -1;

        ChannelAdapter(OnChannelPick pick) {
            this.pick = pick;
        }

        void setChannels(List<LiveChannel> list) {
            channels.clear();
            channels.addAll(list);
            notifyDataSetChanged();
        }

        void setPlaying(int flatIndex) {
            this.playingFlatIndex = flatIndex;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel, parent, false);
            if (viewType == TYPE_HEADER) {
                return new HeaderHolder(v);
            }
            FocusScaler.attach(v);
            return new ChannelHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            LiveChannel c = channels.get(position);
            if (h instanceof HeaderHolder) {
                ((HeaderHolder) h).group.setText(c.groupName);
                ((HeaderHolder) h).group.setVisibility(View.VISIBLE);
                ((ChannelHolder) h).row.setVisibility(View.GONE);
            } else {
                ((ChannelHolder) h).row.setVisibility(View.VISIBLE);
                ((HeaderHolder) h).group.setVisibility(View.GONE);
                ((ChannelHolder) h).row.setText(c.number + "  " + c.name);
                ((ChannelHolder) h).row.setSelected(position == playingFlatIndex);
                final int idx = position;
                ((ChannelHolder) h).row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pick.onChannelPicked(idx);
                    }
                });
            }
        }

        @Override
        public int getItemViewType(int position) {
            LiveChannel c = channels.get(position);
            boolean firstOfGroup = position == 0
                    || !channels.get(position - 1).groupName.equals(c.groupName);
            return firstOfGroup ? TYPE_HEADER : TYPE_CHANNEL;
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }

        static final class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView group;

            HeaderHolder(View itemView) {
                super(itemView);
                group = itemView.findViewById(R.id.channel_group);
            }
        }

        static final class ChannelHolder extends RecyclerView.ViewHolder {
            final TextView row;

            ChannelHolder(View itemView) {
                super(itemView);
                row = itemView.findViewById(R.id.channel_row);
            }
        }
    }
}
