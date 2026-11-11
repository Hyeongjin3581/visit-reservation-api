package com.hyeongjin.visit.request.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 같은 시간대로 여러 날짜를 한 번에 신청 (날짜별 시간대는 지원하지 않음)
public record VisitRequestGroupCreateRequest(
        @NotNull Long recipientId,
        // 목록 자체(1~14개)와 원소 하나하나(null 아님, 오늘 이후)를 따로 검증: 원소 제약은 타입 인자에 붙임
        @NotEmpty @Size(max = VisitRequestGroup.MAX_DATES)
        List<@NotNull @FutureOrPresent LocalDate> visitDates,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
