package com.hyeongjin.visit.reservation.controller;

import java.net.URI;

import com.hyeongjin.visit.reservation.dto.GroupReservationResponse;
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

    // 묶음 일괄 확정: 고른 보호사로 묶음의 모든 날짜를 확정 (한 날짜라도 안 되면 전부 실패)
    @PostMapping("/api/visit-request-groups/{groupId}/reservations")
    public ResponseEntity<GroupReservationResponse> confirmGroup(@PathVariable Long groupId,
                                                                 @Valid @RequestBody ReservationCreateRequest request) {
        GroupReservationResponse response = reservationService.confirmGroup(groupId, request.caregiverId());
        return ResponseEntity.created(URI.create("/api/visit-request-groups/" + groupId)).body(response);
    }

    // 묶음 일괄 취소: 묶음의 살아 있는 예약을 모두 취소
    @DeleteMapping("/api/visit-request-groups/{groupId}/reservations")
    public GroupReservationResponse cancelGroup(@PathVariable Long groupId) {
        return reservationService.cancelGroup(groupId);
    }
}
