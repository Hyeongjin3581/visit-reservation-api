package com.hyeongjin.visit.request.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestStatus;

public record VisitRequestResponse(
        Long id,
        Long recipientId,
        LocalDate visitDate,
        LocalTime startTime,
        LocalTime endTime,
        VisitRequestStatus status
) {

    public static VisitRequestResponse from(VisitRequest visitRequest) {
        return new VisitRequestResponse(
                visitRequest.getId(),
                visitRequest.getRecipient().getId(),
                visitRequest.getVisitDate(),
                visitRequest.getStartTime(),
                visitRequest.getEndTime(),
                visitRequest.getStatus());
    }
}
