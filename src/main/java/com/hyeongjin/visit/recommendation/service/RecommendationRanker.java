package com.hyeongjin.visit.recommendation.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hyeongjin.visit.recommendation.domain.Candidate;
import com.hyeongjin.visit.recommendation.domain.ScoredCandidate;
import com.hyeongjin.visit.recommendation.score.ScorePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 후보에 점수를 매기고 순위를 정함. DB 를 쓰지 않음
// 조회 방식(N+1 이냐 집계 쿼리냐)과 분리해 두어서, 조회 방식만 바꿔도 순위 결과가 같은지 비교할 수 있음
@Component
@RequiredArgsConstructor
public class RecommendationRanker {

    // 총점 높은 순 → 동점이면 가까운 순 → 근무 적은 순 → id 작은 순
    // 마지막 기준까지 두어서 같은 데이터면 항상 같은 순서가 나옴
    // (온케어는 이중 for 문으로 직접 자리를 바꾸는 정렬이었음)
    static final Comparator<ScoredCandidate> RANKING = Comparator
            .comparingDouble(ScoredCandidate::totalScore).reversed()
            .thenComparingDouble(scored -> scored.candidate().distanceKm())
            .thenComparingLong(scored -> scored.candidate().recentWorkCount())
            .thenComparingLong(scored -> scored.candidate().caregiver().getId());

    // @Order 순서대로 주입됨 (거리 → 근무량 → 연속 담당)
    private final List<ScorePolicy> scorePolicies;

    public List<ScoredCandidate> rank(List<Candidate> candidates, int size) {
        return candidates.stream()
                .map(this::score)
                .sorted(RANKING)
                .limit(size)
                .toList();
    }

    private ScoredCandidate score(Candidate candidate) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double total = 0.0;
        for (ScorePolicy policy : scorePolicies) {
            double score = policy.score(candidate);
            scores.put(policy.name(), score);
            total += score;
        }
        return new ScoredCandidate(candidate, scores, total);
    }
}
