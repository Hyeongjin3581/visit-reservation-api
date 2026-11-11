package com.hyeongjin.visit.reservation.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;

public record ReservationResponse(
        Long id,
        Long visitRequestId,
        Long caregiverId,
        LocalDate visitDate,
        LocalTime startTime,
        LocalTime endTime,
        ReservationStatus status
) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getVisitRequest().getId(),
                reservation.getCaregiver().getId(),
                reservation.getVisitDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getStatus());
    }
}
