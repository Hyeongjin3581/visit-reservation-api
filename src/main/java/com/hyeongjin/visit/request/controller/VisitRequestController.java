package com.hyeongjin.visit.request.controller;

import java.net.URI;

import com.hyeongjin.visit.request.dto.VisitRequestCreateRequest;
import com.hyeongjin.visit.request.dto.VisitRequestResponse;
import com.hyeongjin.visit.request.service.VisitRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 2주차: GET /{id}/recommendations, 3주차: POST /{id}/reservations 추가 예정
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/visit-requests")
public class VisitRequestController {

    private final VisitRequestService visitRequestService;

    // 방문 요청 등록
    @PostMapping
    public ResponseEntity<VisitRequestResponse> register(@Valid @RequestBody VisitRequestCreateRequest request) {
        VisitRequestResponse response = visitRequestService.register(request);
        return ResponseEntity.created(URI.create("/api/visit-requests/" + response.id())).body(response);
    }

    // 방문 요청 조회
    @GetMapping("/{visitRequestId}")
    public VisitRequestResponse get(@PathVariable Long visitRequestId) {
        return visitRequestService.get(visitRequestId);
    }
}
