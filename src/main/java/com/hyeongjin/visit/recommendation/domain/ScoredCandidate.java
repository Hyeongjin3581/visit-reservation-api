package com.hyeongjin.visit.recommendation.domain;

import java.util.Map;

// 점수 계산이 끝난 후보 (scores: 항목 이름 → 점수)
public record ScoredCandidate(Candidate candidate, Map<String, Double> scores, double totalScore) {
}
