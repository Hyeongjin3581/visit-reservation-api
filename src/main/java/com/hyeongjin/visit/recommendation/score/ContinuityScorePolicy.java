package com.hyeongjin.visit.recommendation.score;

import com.hyeongjin.visit.recommendation.domain.Candidate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 연속 담당 점수 20점: 이 수급자를 방문한 적이 있으면 1회당 5점, 4회 이상 만점
// 온케어는 1회를 0점으로 쳤는데, 한 번이라도 가 본 보호사가 처음 가는 보호사보다 낫다고 보고 1회부터 점수를 줌
@Component
@Order(3)
public class ContinuityScorePolicy implements ScorePolicy {

    static final double MAX_SCORE = 20.0;
    static final double SCORE_PER_VISIT = 5.0;

    @Override
    public String name() {
        return "continuity";
    }

    @Override
    public double maxScore() {
        return MAX_SCORE;
    }

    @Override
    public double score(Candidate candidate) {
        return Math.min(MAX_SCORE, candidate.continuityCount() * SCORE_PER_VISIT);
    }
}
