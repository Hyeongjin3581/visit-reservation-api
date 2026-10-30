package com.hyeongjin.visit.recipient.service;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.dto.RecipientCreateRequest;
import com.hyeongjin.visit.recipient.dto.RecipientResponse;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientService {

    private final RecipientRepository recipientRepository;

    @Transactional
    public RecipientResponse register(RecipientCreateRequest request) {
        Recipient recipient = Recipient.create(
                request.name(), request.preferredGender(), request.latitude(), request.longitude());
        return RecipientResponse.from(recipientRepository.save(recipient));
    }

    public RecipientResponse get(Long recipientId) {
        Recipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND));
        return RecipientResponse.from(recipient);
    }
}
