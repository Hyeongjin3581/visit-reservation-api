package com.hyeongjin.visit.reservation.controller;

import java.net.URI;

import com.hyeongjin.visit.reservation.dto.ReservationCreateRequest;
import com.hyeongjin.visit.reservation.dto.ReservationResponse;
import com.hyeongjin.visit.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    // 예약 확정: 추천받은 보호사 중 한 명을 골라 배정
    @PostMapping("/api/visit-requests/{visitRequestId}/reservations")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable Long visitRequestId,
                                                       @Valid @RequestBody ReservationCreateRequest request) {
        ReservationResponse response = reservationService.confirm(visitRequestId, request.caregiverId());
        return ResponseEntity.created(URI.create("/api/reservations/" + response.id())).body(response);
    }

    // 예약 취소: 행은 지우지 않고 상태만 CANCELED, 방문 요청은 다시 배정 대기로
    @DeleteMapping("/api/reservations/{reservationId}")
    public ReservationResponse cancel(@PathVariable Long reservationId) {
        return reservationService.cancel(reservationId);
    }
}
