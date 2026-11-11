package com.hyeongjin.visit.recommendation.score;

import com.hyeongjin.visit.recommendation.domain.Candidate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 거리 점수 50점: 0km 만점, 25km 이상 0점, 그 사이는 직선으로 감소
@Component
@Order(1)
public class DistanceScorePolicy implements ScorePolicy {

    static final double MAX_SCORE = 50.0;
    static final double ZERO_SCORE_KM = 25.0;

    @Override
    public String name() {
        return "distance";
    }

    @Override
    public double maxScore() {
        return MAX_SCORE;
    }

    @Override
    public double score(Candidate candidate) {
        double remainingRatio = Math.max(0.0, 1.0 - candidate.distanceKm() / ZERO_SCORE_KM);
        return MAX_SCORE * remainingRatio;
    }
}
