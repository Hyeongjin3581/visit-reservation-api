package com.hyeongjin.visit.reservation.dto;

import java.util.Comparator;
import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;

// 묶음 일괄 확정·취소 결과: 날짜별 예약 목록 (날짜순)
public record GroupReservationResponse(Long groupId, Long caregiverId, List<ReservationResponse> reservations) {

    public static GroupReservationResponse of(Long groupId, Long caregiverId, List<Reservation> reservations) {
        return new GroupReservationResponse(groupId, caregiverId, reservations.stream()
                .map(ReservationResponse::from)
                .sorted(Comparator.comparing(ReservationResponse::visitDate))
                .toList());
    }
}
