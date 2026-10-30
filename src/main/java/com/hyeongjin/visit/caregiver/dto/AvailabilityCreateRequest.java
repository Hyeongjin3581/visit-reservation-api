package com.hyeongjin.visit.caregiver.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

// 시작 < 종료, 30분 단위 검증은 엔티티 생성 시 TimeSlots 에서 처리
public record AvailabilityCreateRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
