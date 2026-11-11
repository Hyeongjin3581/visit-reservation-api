package com.hyeongjin.visit.recommendation.controller;

import com.hyeongjin.visit.recommendation.dto.GroupRecommendationResponse;
import com.hyeongjin.visit.recommendation.dto.RecommendationResponse;
import com.hyeongjin.visit.recommendation.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    // 방문 요청에 맞는 요양보호사 상위 N명 추천 (기본 3명, 최대 10명)
    @GetMapping("/api/visit-requests/{visitRequestId}/recommendations")
    public RecommendationResponse recommend(@PathVariable Long visitRequestId,
                                            @RequestParam(defaultValue = "3") int size) {
        return recommendationService.recommend(visitRequestId, size);
    }

    // 묶음 신청의 모든 날짜에 갈 수 있는 요양보호사 상위 N명 추천
    @GetMapping("/api/visit-request-groups/{groupId}/recommendations")
    public GroupRecommendationResponse recommendGroup(@PathVariable Long groupId,
                                                      @RequestParam(defaultValue = "3") int size) {
        return recommendationService.recommendGroup(groupId, size);
    }
}
