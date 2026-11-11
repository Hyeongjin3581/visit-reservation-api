package com.hyeongjin.visit.recommendation.domain;

import com.hyeongjin.visit.caregiver.domain.Caregiver;

// 필터를 통과한 후보 + 점수 계산에 필요한 값
public record Candidate(
        Caregiver caregiver,
        double distanceKm,       // 수급자와의 거리
        long recentWorkCount,    // 최근 30일 완료 방문 횟수
        long continuityCount     // 이 수급자를 완료 방문한 횟수
) {
}
