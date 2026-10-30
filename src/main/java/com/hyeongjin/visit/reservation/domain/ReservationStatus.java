package com.hyeongjin.visit.reservation.domain;

public enum ReservationStatus {
    CONFIRMED,  // 예약 확정
    COMPLETED,  // 방문 완료 → 근무량·연속 담당 점수 계산에 사용
    CANCELED    // 취소 (소프트 삭제)
}
