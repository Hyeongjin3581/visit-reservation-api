package com.hyeongjin.visit.request.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

public record VisitRequestCreateRequest(
        @NotNull Long recipientId,
        @NotNull @FutureOrPresent LocalDate visitDate,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
