package com.hyeongjin.visit.request.dto;

import java.time.LocalTime;
import java.util.List;

import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestGroup;

public record VisitRequestGroupResponse(
        Long id,
        Long recipientId,
        LocalTime startTime,
        LocalTime endTime,
        List<VisitRequestResponse> visitRequests
) {

    public static VisitRequestGroupResponse of(VisitRequestGroup group, List<VisitRequest> requests) {
        return new VisitRequestGroupResponse(
                group.getId(),
                group.getRecipient().getId(),
                group.getStartTime(),
                group.getEndTime(),
                requests.stream().map(VisitRequestResponse::from).toList());
    }
}
