package com.x;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RankingService {
    private final Map<Long, Integer> scores = new HashMap<>();

    public void addScore(Long memberId, int score) {
        scores.merge(memberId, score, Integer::sum);
    }

    public int scoreOf(Long memberId) {
        return scores.getOrDefault(memberId, 0);
    }
}
