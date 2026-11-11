package com.hyeongjin.visit.recommendation.score;

import com.hyeongjin.visit.recommendation.domain.Candidate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 근무량 점수 30점: 최근 30일 완료 방문이 적을수록 높음, 20회 이상 0점
// 일이 한 사람에게 몰리지 않게 하려는 항목
@Component
@Order(2)
public class WorkloadScorePolicy implements ScorePolicy {

    static final double MAX_SCORE = 30.0;
    static final double ZERO_SCORE_COUNT = 20.0;

    @Override
    public String name() {
        return "workload";
    }

    @Override
    public double maxScore() {
        return MAX_SCORE;
    }

    @Override
    public double score(Candidate candidate) {
        double remainingRatio = Math.max(0.0, 1.0 - candidate.recentWorkCount() / ZERO_SCORE_COUNT);
        return MAX_SCORE * remainingRatio;
    }
}
