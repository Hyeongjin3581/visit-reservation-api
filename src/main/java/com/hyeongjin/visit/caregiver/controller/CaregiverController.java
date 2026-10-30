package com.hyeongjin.visit.caregiver.controller;

import java.net.URI;

import com.hyeongjin.visit.caregiver.dto.AvailabilityCreateRequest;
import com.hyeongjin.visit.caregiver.dto.AvailabilityResponse;
import com.hyeongjin.visit.caregiver.dto.CaregiverCreateRequest;
import com.hyeongjin.visit.caregiver.dto.CaregiverResponse;
import com.hyeongjin.visit.caregiver.service.CaregiverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/caregivers")
public class CaregiverController {

    private final CaregiverService caregiverService;

    // 요양보호사 등록
    @PostMapping
    public ResponseEntity<CaregiverResponse> register(@Valid @RequestBody CaregiverCreateRequest request) {
        CaregiverResponse response = caregiverService.register(request);
        return ResponseEntity.created(URI.create("/api/caregivers/" + response.id())).body(response);
    }

    // 요양보호사 조회
    @GetMapping("/{caregiverId}")
    public CaregiverResponse get(@PathVariable Long caregiverId) {
        return caregiverService.get(caregiverId);
    }

    // 근무 가능 시간 등록
    @PostMapping("/{caregiverId}/availabilities")
    public ResponseEntity<AvailabilityResponse> addAvailability(@PathVariable Long caregiverId,
                                                                @Valid @RequestBody AvailabilityCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caregiverService.addAvailability(caregiverId, request));
    }
}
