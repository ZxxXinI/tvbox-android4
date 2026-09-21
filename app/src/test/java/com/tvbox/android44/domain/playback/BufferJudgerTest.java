package com.tvbox.android44.domain.playback;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 卡顿判断：注入假时钟，不 sleep；点播/直播双阈值、seek 冷却、暂停不判。 */
public class BufferJudgerTest {

    /** 可手动推进的假时钟。 */
    private static class FakeClock implements BufferJudger.Clock {
        long nowMs;

        @Override
        public long now() {
            return nowMs;
        }

        void advance(long ms) {
            nowMs += ms;
        }
    }

    @Test
    public void vodContinuous_over5s_verdict() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(4999);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(1);
        assertEquals(BufferJudger.Verdict.CONTINUOUS, judger.onBufferingChanged(true));
    }

    @Test
    public void vodFrequent_3shortBuffersInWindow() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        for (int i = 0; i < 2; i++) {
            assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
            clock.advance(1000);
            assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
            clock.advance(1000);
        }
        // 第 3 次短缓冲结束 → FREQUENT
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(1000);
        assertEquals(BufferJudger.Verdict.FREQUENT, judger.onBufferingChanged(false));
    }

    @Test
    public void vodCumulative_twoLongBuffers() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(4500);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
        clock.advance(500);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(4500);
        // 累计 9000ms ≥ 8000ms 且次数 2 < 3 → CUMULATIVE
        assertEquals(BufferJudger.Verdict.CUMULATIVE, judger.onBufferingChanged(false));
    }

    @Test
    public void shortBlip_under300ms_notRecorded() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        for (int i = 0; i < 3; i++) {
            judger.onBufferingChanged(true);
            clock.advance(200);
            assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
            clock.advance(100);
        }
        // 3 次 200ms 抖动（合计 600ms）都不应触发任何判定
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(300);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
    }

    @Test
    public void seekCooldown_noJudgment() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        judger.onSeekPerformed(); // 冷却至 t+3000
        for (int i = 0; i < 3; i++) {
            judger.onBufferingChanged(true);
            clock.advance(1000);
            assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
        }
        // 冷却期内的 3 次缓冲均未计入；冷却结束后重新计窗
        clock.advance(1000); // t=4000 > 3000
        judger.onBufferingChanged(true);
        clock.advance(1000);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
    }

    @Test
    public void paused_notJudged() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        judger.onPaused(true);
        judger.onBufferingChanged(true);
        clock.advance(6000);
        // 暂停中：连续缓冲也不判
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(1000);
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(false));
    }

    @Test
    public void liveThresholds_higherThanVod() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.LIVE, clock);

        judger.onBufferingChanged(true);
        clock.advance(5000); // VOD 已触发，LIVE 阈值 6s 未触发
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
        clock.advance(1000);
        assertEquals(BufferJudger.Verdict.CONTINUOUS, judger.onBufferingChanged(true));
    }

    @Test
    public void liveNoProgress_after4s() {
        FakeClock clock = new FakeClock();
        clock.nowMs = 1_000_000L; // 真实时钟不为 0；实现以 >0 判定“已记录”
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.LIVE, clock);

        assertEquals(BufferJudger.Verdict.NONE, judger.onPositionPoll(60000));
        clock.advance(2000);
        assertEquals(BufferJudger.Verdict.NONE, judger.onPositionPoll(60000));
        clock.advance(1999);
        assertEquals(BufferJudger.Verdict.NONE, judger.onPositionPoll(60000));
        clock.advance(1);
        assertEquals(BufferJudger.Verdict.NO_PROGRESS, judger.onPositionPoll(60000));
        // 位置恢复前进后回到 NONE
        clock.advance(1000);
        assertEquals(BufferJudger.Verdict.NONE, judger.onPositionPoll(61000));
    }

    @Test
    public void reset_clearsState() {
        FakeClock clock = new FakeClock();
        BufferJudger judger = new BufferJudger(BufferJudger.Mode.VOD, clock);

        judger.onBufferingChanged(true);
        clock.advance(6000);
        judger.reset();
        assertEquals(BufferJudger.Verdict.NONE, judger.onBufferingChanged(true));
    }
}
