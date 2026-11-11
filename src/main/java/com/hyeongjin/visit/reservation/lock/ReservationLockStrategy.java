package com.hyeongjin.visit.reservation.lock;

import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;

// 예약 확정 동시성 제어 방법. application.yml 의 reservation.lock.strategy 로 고름
//   unique      : A. DB 유니크 제약만
//   pessimistic : B. 보호사 행 비관적 락 (기본값)
//   optimistic  : C. 보호사 버전 낙관적 락 + 재시도
//   redisson    : D. Redis 분산 락
public interface ReservationLockStrategy {

    String name();

    Reservation confirm(Long visitRequestId, Long caregiverId);

    // 묶음의 모든 날짜를 같은 보호사로 한 번에 확정 (전부 아니면 전무)
    List<Reservation> confirmGroup(Long groupId, Long caregiverId);
}
