package com.tvbox.android44.domain.playback;

import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.domain.model.LineHealth;
import com.tvbox.android44.domain.model.PlaySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动换线决策：
 * 候选线路优先近期成功、避开冷却失败线路、轻度参考长期成功率；
 * 不循环回刚失败线路（会话尝试集合）。
 */
public class AutoSwitchPolicy {

    /**
     * 从线路列表选出下一条候选线路。
     *
     * @param currentLineId  当前失败线路
     * @param triedLineIds   本次会话已尝试线路（含 current）
     * @param now            当前时间
     * @return 候选线路；没有可用线路返回 null
     */
    public static PlaySource pickNext(List<PlaySource> sources, String currentLineId,
                                      List<String> triedLineIds,
                                      HealthLookup health, long now, int episodeIndex) {
        List<PlaySource> candidates = new ArrayList<PlaySource>();
        for (PlaySource s : sources) {
            if (s.lineId.equals(currentLineId)) {
                continue;
            }
            if (triedLineIds.contains(s.lineId)) {
                continue;
            }
            if (episodeIndex >= 0 && episodeIndex >= s.episodes.size()) {
                continue;
            }
            candidates.add(s);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        PlaySource best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (PlaySource s : candidates) {
            LineHealth h = health == null ? null : health.lookup(s.lineId);
            double score = score(h, now);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** 评分：近期成功 +1，冷却期 -10，成功率轻度加权。 */
    static double score(LineHealth h, long now) {
        if (h == null) {
            return 0;
        }
        double score = 0;
        if (h.cooldownUntil > now) {
            score -= 10;
        }
        if (h.lastSuccessAt > 0 && now - h.lastSuccessAt < 30 * 60 * 1000L) {
            score += 1;
        }
        double rate = h.successRate();
        if (rate >= 0) {
            score += rate * 0.5;
        }
        return score;
    }

    public interface HealthLookup {
        LineHealth lookup(String lineId);
    }
}
