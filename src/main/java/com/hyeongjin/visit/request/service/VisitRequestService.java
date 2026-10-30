package com.hyeongjin.visit.request.service;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.dto.VisitRequestCreateRequest;
import com.hyeongjin.visit.request.dto.VisitRequestResponse;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitRequestService {

    private final VisitRequestRepository visitRequestRepository;
    private final RecipientRepository recipientRepository;

    @Transactional
    public VisitRequestResponse register(VisitRequestCreateRequest request) {
        Recipient recipient = recipientRepository.findById(request.recipientId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND));
        VisitRequest visitRequest = VisitRequest.create(
                recipient, request.visitDate(), request.startTime(), request.endTime());
        return VisitRequestResponse.from(visitRequestRepository.save(visitRequest));
    }

    public VisitRequestResponse get(Long visitRequestId) {
        VisitRequest visitRequest = visitRequestRepository.findById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
        return VisitRequestResponse.from(visitRequest);
    }
}
