package com.hyeongjin.visit.reservation.service;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;

// 확정 트랜잭션 안에서 (보호사, 날짜) 잠금 행이 없을 때 던짐
// → 트랜잭션이 롤백되고, 전략이 트랜잭션 밖에서 행을 만든 뒤 한 번 다시 시도함 (사용자에게 나가는 에러가 아님)
@Getter
public class MissingDayLockException extends RuntimeException {

    private final Long caregiverId;
    // 확정하려던 날짜 전체 (묶음이면 모두) → 한 번에 준비해서 재시도를 1번으로 끝냄
    private final List<LocalDate> dates;

    public MissingDayLockException(Long caregiverId, List<LocalDate> dates) {
        super("잠금 행 없음: caregiver=" + caregiverId + ", dates=" + dates);
        this.caregiverId = caregiverId;
        this.dates = dates;
    }
}
