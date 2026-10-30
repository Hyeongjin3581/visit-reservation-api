package com.hyeongjin.visit.recipient.controller;

import java.net.URI;

import com.hyeongjin.visit.recipient.dto.RecipientCreateRequest;
import com.hyeongjin.visit.recipient.dto.RecipientResponse;
import com.hyeongjin.visit.recipient.service.RecipientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recipients")
public class RecipientController {

    private final RecipientService recipientService;

    // 수급자 등록
    @PostMapping
    public ResponseEntity<RecipientResponse> register(@Valid @RequestBody RecipientCreateRequest request) {
        RecipientResponse response = recipientService.register(request);
        return ResponseEntity.created(URI.create("/api/recipients/" + response.id())).body(response);
    }

    // 수급자 조회
    @GetMapping("/{recipientId}")
    public RecipientResponse get(@PathVariable Long recipientId) {
        return recipientService.get(recipientId);
    }
}
