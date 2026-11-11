package com.hyeongjin.visit.request.controller;

import java.net.URI;

import com.hyeongjin.visit.request.dto.VisitRequestGroupCreateRequest;
import com.hyeongjin.visit.request.dto.VisitRequestGroupResponse;
import com.hyeongjin.visit.request.service.VisitRequestGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 묶음 추천(GET /{id}/recommendations)은 RecommendationController,
// 일괄 확정·취소(POST·DELETE /{id}/reservations)는 ReservationController
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/visit-request-groups")
public class VisitRequestGroupController {

    private final VisitRequestGroupService groupService;

    // 묶음 신청 등록: 날짜마다 방문 요청을 만듦
    @PostMapping
    public ResponseEntity<VisitRequestGroupResponse> register(
            @Valid @RequestBody VisitRequestGroupCreateRequest request) {
        VisitRequestGroupResponse response = groupService.register(request);
        return ResponseEntity.created(URI.create("/api/visit-request-groups/" + response.id())).body(response);
    }

    // 묶음과 하위 요청 조회
    @GetMapping("/{groupId}")
    public VisitRequestGroupResponse get(@PathVariable Long groupId) {
        return groupService.get(groupId);
    }
}
