package com.hyeongjin.visit.common.time;

import java.time.LocalTime;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;

// 시간 규칙 검증 모음
// 예약 중복을 슬롯(30분) 단위 유니크 제약으로 막기 위해, 모든 시간은 30분 단위로만 받음
public final class TimeSlots {

    public static final int SLOT_MINUTES = 30;

    private TimeSlots() {
    }

    public static void validateRange(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
        if (!isOnSlot(startTime) || !isOnSlot(endTime)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_SLOT);
        }
    }

    private static boolean isOnSlot(LocalTime time) {
        return time.getMinute() % SLOT_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }
}
