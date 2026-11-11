package com.hyeongjin.visit.reservation.service;

// 확정 트랜잭션 안에서 무엇을 잠그는가 (락 전략마다 다름)
public enum ConfirmLock {
    // A. 유니크 제약만 / D. Redis 락 (트랜잭션 밖에서 이미 잠금)
    NONE,
    // B'. 보호사 행 SELECT ... FOR UPDATE (pessimistic-caregiver, 범위 축소 전의 B)
    CAREGIVER_ROW,
    // C. 보호사 version + 1 (OPTIMISTIC_FORCE_INCREMENT)
    CAREGIVER_VERSION,
    // B. (보호사, 날짜) 잠금 행 SELECT ... FOR UPDATE → 같은 보호사라도 다른 날짜는 기다리지 않음
    CAREGIVER_DAY
}
