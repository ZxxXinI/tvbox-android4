package com.tvbox.android44.domain.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.tvbox.android44.domain.model.LineHealth;
import com.tvbox.android44.domain.model.PlayEpisode;
import com.tvbox.android44.domain.model.PlaySource;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自动换线策略：跳过当前/已试/缺集线路；近期成功优先；冷却线路避让。 */
public class AutoSwitchPolicyTest {

    private static PlaySource source(String lineId, int episodeCount) {
        PlaySource s = new PlaySource(lineId, "线路-" + lineId, "源");
        for (int i = 0; i < Math.max(1, episodeCount); i++) {
            s.episodes.add(new PlayEpisode(i, "第" + (i + 1) + "集", "http://a/" + i + ".mp4"));
        }
        return s;
    }

    @Test
    public void skipsCurrentAndTried() {
        List<PlaySource> sources = Arrays.asList(source("s1", 1), source("s2", 1), source("s3", 1));
        PlaySource next = AutoSwitchPolicy.pickNext(sources, "s1",
                Collections.singletonList("s1"), null, 0L, 0);
        assertEquals("s2", next.lineId);

        assertNull(AutoSwitchPolicy.pickNext(sources, "s1",
                Arrays.asList("s1", "s2", "s3"), null, 0L, 0));
    }

    @Test
    public void skipsSourceMissingEpisode() {
        List<PlaySource> sources = Arrays.asList(source("s1", 1), source("s2", 10));
        // 请求第 5 集：s1 只有 1 集，只能选 s2
        PlaySource next = AutoSwitchPolicy.pickNext(sources, "s1",
                Collections.singletonList("s1"), null, 0L, 5);
        assertEquals("s2", next.lineId);
    }

    @Test
    public void recentSuccessPreferred_overCooldown() {
        final long now = 1_000_000L;
        List<PlaySource> sources = Arrays.asList(source("s1", 1), source("s2", 1), source("s3", 1));

        Map<String, LineHealth> health = new HashMap<String, LineHealth>();
        LineHealth cooled = new LineHealth("s2");
        cooled.cooldownUntil = now + 1000; // 冷却中
        health.put("s2", cooled);
        LineHealth recent = new LineHealth("s3");
        recent.lastSuccessAt = now - 1000; // 30 分钟内成功过
        health.put("s3", recent);

        PlaySource next = AutoSwitchPolicy.pickNext(sources, "s1",
                Collections.singletonList("s1"), new MapLookup(health), now, 0);
        assertEquals("s3", next.lineId);
    }

    @Test
    public void allOthersInCooldown_stillReturnsCandidate() {
        // 冷却只是降分，不直接禁用：没有更好候选时仍可返回
        final long now = 1_000_000L;
        List<PlaySource> sources = Arrays.asList(source("s1", 1), source("s2", 1));
        Map<String, LineHealth> health = new HashMap<String, LineHealth>();
        LineHealth cooled = new LineHealth("s2");
        cooled.cooldownUntil = now + 1000;
        health.put("s2", cooled);

        PlaySource next = AutoSwitchPolicy.pickNext(sources, "s1",
                Collections.singletonList("s1"), new MapLookup(health), now, 0);
        assertEquals("s2", next.lineId);
    }

    @Test
    public void emptyCandidates_null() {
        assertNull(AutoSwitchPolicy.pickNext(new ArrayList<PlaySource>(), "s1",
                new ArrayList<String>(), null, 0L, 0));
    }

    @Test
    public void score_recentSuccessBeatsCooling() {
        long now = 1_000_000L;
        LineHealth success = new LineHealth("a");
        success.lastSuccessAt = now - 1000;
        LineHealth cooling = new LineHealth("b");
        cooling.cooldownUntil = now + 1000;

        assertEquals(true, AutoSwitchPolicy.score(success, now)
                > AutoSwitchPolicy.score(cooling, now));
        assertEquals(0.0, AutoSwitchPolicy.score(null, now), 1e-9);
    }

    private static final class MapLookup implements AutoSwitchPolicy.HealthLookup {
        private final Map<String, LineHealth> map;

        MapLookup(Map<String, LineHealth> map) {
            this.map = map;
        }

        @Override
        public LineHealth lookup(String lineId) {
            return map.get(lineId);
        }
    }
}
