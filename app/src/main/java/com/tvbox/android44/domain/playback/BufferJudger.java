package com.tvbox.android44.domain.playback;

import com.tvbox.android44.common.AppConstants;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 卡顿判断（可注入时钟，禁止依赖真实 sleep 测试）：
 * 点播：连续缓冲>5s；60s 内短缓冲≥3 次；60s 内累计>8s。
 * 直播：连续>6s；60s 内≥3 次；60s 内累计>12s；位置停滞 4s。
 * seek 后 3 秒冷却与暂停期间不判断。
 */
public class BufferJudger {

    public enum Mode {VOD, LIVE}

    public enum Verdict {
        NONE, CONTINUOUS, FREQUENT, CUMULATIVE, NO_PROGRESS
    }

    /** 可注入时钟。 */
    public interface Clock {
        long now();
    }

    private final Clock clock;
    private final Mode mode;
    /** 最近短缓冲窗口（毫秒时长），按时间组织。 */
    private final Deque<long[]> recentBuffers = new ArrayDeque<long[]>(); // {start, duration}
    private long bufferStartAt = -1;
    private boolean buffering;
    private boolean paused;
    private long seekCooldownUntil;
    private long lastPosition = -1;
    private long lastPositionChangedAt = -1;

    public BufferJudger(Mode mode, Clock clock) {
        this.mode = mode;
        this.clock = clock;
    }

    public void reset() {
        recentBuffers.clear();
        bufferStartAt = -1;
        buffering = false;
        seekCooldownUntil = 0;
        lastPosition = -1;
        lastPositionChangedAt = -1;
    }

    public void onPaused(boolean paused) {
        this.paused = paused;
    }

    public void onSeekPerformed() {
        seekCooldownUntil = clock.now() + AppConstants.SEEK_COOLDOWN_MS;
    }

    /** 缓冲状态变化；返回是否触发判定。 */
    public Verdict onBufferingChanged(boolean nowBuffering) {
        long now = clock.now();
        if (nowBuffering) {
            if (!buffering) {
                buffering = true;
                bufferStartAt = now;
            }
            // 连续缓冲判定
            if (bufferStartAt >= 0 && now - bufferStartAt >= continuousThreshold()) {
                if (inJudgingWindow(now)) {
                    recordShortBuffer(bufferStartAt, continuousThreshold());
                    bufferStartAt = now; // 重新计窗，避免重复触发
                    return Verdict.CONTINUOUS;
                }
            }
            return Verdict.NONE;
        }
        if (buffering) {
            buffering = false;
            if (bufferStartAt >= 0) {
                long duration = now - bufferStartAt;
                bufferStartAt = -1;
                if (duration > 300 && inJudgingWindow(now)) {
                    recordShortBuffer(now - duration, duration);
                    Verdict v = evaluateWindow(now);
                    if (v != Verdict.NONE) {
                        return v;
                    }
                }
            }
        }
        return Verdict.NONE;
    }

    /** 播放位置推进轮询（直播用）；正常前进返回 NONE。 */
    public Verdict onPositionPoll(long positionMs) {
        long now = clock.now();
        if (paused || buffering || positionMs < 0) {
            lastPosition = positionMs;
            lastPositionChangedAt = now;
            return Verdict.NONE;
        }
        if (positionMs != lastPosition) {
            lastPosition = positionMs;
            lastPositionChangedAt = now;
            return Verdict.NONE;
        }
        if (lastPositionChangedAt > 0 && now - lastPositionChangedAt >= AppConstants.LIVE_NO_PROGRESS_MS) {
            lastPositionChangedAt = now;
            return Verdict.NO_PROGRESS;
        }
        return Verdict.NONE;
    }

    private boolean inJudgingWindow(long now) {
        return !paused && now >= seekCooldownUntil;
    }

    private long continuousThreshold() {
        return mode == Mode.VOD
                ? AppConstants.VOD_CONTINUOUS_BUFFER_MS
                : AppConstants.LIVE_CONTINUOUS_BUFFER_MS;
    }

    private int frequentCount() {
        return mode == Mode.VOD
                ? AppConstants.VOD_FREQUENT_BUFFER_COUNT
                : AppConstants.LIVE_FREQUENT_BUFFER_COUNT;
    }

    private long cumulativeThreshold() {
        return mode == Mode.VOD
                ? AppConstants.VOD_CUMULATIVE_BUFFER_MS
                : AppConstants.LIVE_CUMULATIVE_BUFFER_MS;
    }

    private void recordShortBuffer(long start, long duration) {
        long windowStart = clock.now() - AppConstants.VOD_FREQUENT_WINDOW_MS;
        // 清理窗口外记录
        while (!recentBuffers.isEmpty() && recentBuffers.peekFirst()[0] < windowStart) {
            recentBuffers.pollFirst();
        }
        recentBuffers.addLast(new long[]{start, duration});
    }

    private Verdict evaluateWindow(long now) {
        int count = recentBuffers.size();
        if (count >= frequentCount()) {
            recentBuffers.clear();
            return Verdict.FREQUENT;
        }
        long sum = 0;
        for (long[] b : recentBuffers) {
            sum += b[1];
        }
        if (sum >= cumulativeThreshold()) {
            recentBuffers.clear();
            return Verdict.CUMULATIVE;
        }
        return Verdict.NONE;
    }
}
