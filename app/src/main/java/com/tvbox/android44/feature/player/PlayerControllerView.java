package com.tvbox.android44.feature.player;

import android.os.Handler;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;

/**
 * 点播播放器控制层：显示/自动隐藏（5 秒）、进度、倍速、上下集、换线、退出。
 */
public class PlayerControllerView {

    public interface Listener {
        void onTogglePlay();

        void onPrevEpisode();

        void onNextEpisode();

        void onCycleSpeed();

        void onChangeLine();

        void onExit();

        void onSeekTo(int progressPercent);
    }

    private final View root;
    private final TextView title;
    private final TextView sub;
    private final TextView position;
    private final TextView duration;
    private final SeekBar seek;
    private final TextView toggle;
    private final TextView speed;
    private final Listener listener;
    private final Handler main = new Handler();
    private boolean shown;
    private boolean dragging;
    private boolean playing;
    private boolean liveMode;

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            if (shown && playing && !dragging) {
                hide();
            }
        }
    };

    public PlayerControllerView(View rootView, Listener listener) {
        this.root = rootView;
        this.listener = listener;
        title = rootView.findViewById(R.id.player_title);
        sub = rootView.findViewById(R.id.player_sub);
        position = rootView.findViewById(R.id.player_position);
        duration = rootView.findViewById(R.id.player_duration);
        seek = rootView.findViewById(R.id.player_seek);
        toggle = rootView.findViewById(R.id.player_toggle);
        speed = rootView.findViewById(R.id.player_speed);
        View prev = rootView.findViewById(R.id.player_prev);
        View next = rootView.findViewById(R.id.player_next);
        View changeLine = rootView.findViewById(R.id.player_change_line);
        View exit = rootView.findViewById(R.id.player_exit);

        prev.setOnClickListener(v -> listener.onPrevEpisode());
        toggle.setOnClickListener(v -> listener.onTogglePlay());
        next.setOnClickListener(v -> listener.onNextEpisode());
        speed.setOnClickListener(v -> listener.onCycleSpeed());
        changeLine.setOnClickListener(v -> listener.onChangeLine());
        exit.setOnClickListener(v -> listener.onExit());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && dragging) {
                    updateTimeFromProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                listener.onSeekTo(bar.getProgress());
                scheduleHide();
            }
        });
    }

    public void setTitles(String mainTitle, String subTitle) {
        title.setText(mainTitle);
        sub.setText(subTitle == null ? "" : subTitle);
    }

    public void setLiveMode(boolean liveMode) {
        this.liveMode = liveMode;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        toggle.setText(playing ? "暂停" : "播放");
        if (shown) {
            scheduleHide();
        }
    }

    public void setSpeedLabel(float speedValue) {
        speed.setText(liveMode ? "直播" : String.format(java.util.Locale.US, "倍速 %.2fx", speedValue));
    }

    public void updateTime(long positionMs, long durationMs) {
        if (!dragging) {
            if (durationMs > 0) {
                seek.setMax(1000);
                seek.setProgress((int) (positionMs * 1000 / durationMs));
            }
            position.setText(formatTime(positionMs));
            duration.setText(durationMs > 0 ? formatTime(durationMs) : "--:--");
        }
    }

    private void updateTimeFromProgress(int progress) {
        long dur = parseDuration();
        position.setText(formatTime(dur * progress / 1000));
    }

    private long parseDuration() {
        String d = duration.getText().toString();
        String[] parts = d.split(":");
        try {
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2])) * 1000L;
            }
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    public void show() {
        shown = true;
        root.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    public void hide() {
        shown = false;
        root.setVisibility(View.GONE);
        main.removeCallbacks(hideRunnable);
    }

    public boolean isShown() {
        return shown;
    }

    /** 弹窗/控制层打开时把焦点放入控制层。 */
    public void focusPlayToggle() {
        toggle.requestFocus();
    }

    public void scheduleHide() {
        main.removeCallbacks(hideRunnable);
        main.postDelayed(hideRunnable, AppConstants.CONTROLLER_HIDE_DELAY_MS);
    }

    public void destroy() {
        main.removeCallbacks(hideRunnable);
    }

    public static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
}
