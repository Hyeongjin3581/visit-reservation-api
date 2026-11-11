package com.hyeongjin.visit.reservation.repository;

// GROUP BY 집계 결과 한 줄 (보호사 id, 횟수)
public record CaregiverCount(Long caregiverId, Long count) {
}
