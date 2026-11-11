package com.hyeongjin.visit.recommendation.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hyeongjin.visit.recommendation.domain.Candidate;
import com.hyeongjin.visit.recommendation.domain.ScoredCandidate;

public record RecommendationResponse(Long visitRequestId, List<CandidateResponse> candidates) {

    public static RecommendationResponse of(Long visitRequestId, List<ScoredCandidate> ranked) {
        return new RecommendationResponse(visitRequestId, ranked.stream().map(CandidateResponse::from).toList());
    }

    // 점수는 계산할 때는 그대로 두고, 응답할 때만 소수 첫째 자리로 반올림
    public record CandidateResponse(
            Long caregiverId,
            String name,
            double distanceKm,
            long recentWorkCount,
            long continuityCount,
            Map<String, Double> scores,
            double totalScore
    ) {

        static CandidateResponse from(ScoredCandidate scored) {
            Candidate candidate = scored.candidate();
            Map<String, Double> rounded = new LinkedHashMap<>();
            scored.scores().forEach((name, score) -> rounded.put(name, round1(score)));
            return new CandidateResponse(
                    candidate.caregiver().getId(),
                    candidate.caregiver().getName(),
                    round1(candidate.distanceKm()),
                    candidate.recentWorkCount(),
                    candidate.continuityCount(),
                    rounded,
                    round1(scored.totalScore()));
        }

        private static double round1(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }
}
