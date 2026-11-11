package com.hyeongjin.visit.recommendation.dto;

import java.time.LocalDate;
import java.util.List;

import com.hyeongjin.visit.recommendation.domain.ScoredCandidate;
import com.hyeongjin.visit.recommendation.dto.RecommendationResponse.CandidateResponse;

// 묶음 추천: 후보는 묶음의 모든 날짜에 갈 수 있는 보호사만
public record GroupRecommendationResponse(Long groupId, List<LocalDate> visitDates, List<CandidateResponse> candidates) {

    public static GroupRecommendationResponse of(Long groupId, List<LocalDate> visitDates, List<ScoredCandidate> ranked) {
        return new GroupRecommendationResponse(groupId, visitDates, ranked.stream().map(CandidateResponse::from).toList());
    }
}
