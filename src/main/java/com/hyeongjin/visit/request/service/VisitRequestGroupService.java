package com.hyeongjin.visit.request.service;

import java.util.HashSet;
import java.util.List;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import com.hyeongjin.visit.request.dto.VisitRequestGroupCreateRequest;
import com.hyeongjin.visit.request.dto.VisitRequestGroupResponse;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 묶음 신청: 묶음 1개 + 날짜마다 방문 요청 1개
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitRequestGroupService {

    private final VisitRequestGroupRepository groupRepository;
    private final VisitRequestRepository visitRequestRepository;
    private final RecipientRepository recipientRepository;

    // 쿼리: 수급자 1 + 묶음 INSERT 1 + 요청 INSERT K (IDENTITY 라 배치 INSERT 안 됨, K ≤ 14)
    @Transactional
    public VisitRequestGroupResponse register(VisitRequestGroupCreateRequest request) {
        Recipient recipient = recipientRepository.findById(request.recipientId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND));
        // 같은 날짜가 두 번 오면 같은 보호사에게 스스로 겹치는 예약을 만들게 되므로 등록 단계에서 막음
        if (new HashSet<>(request.visitDates()).size() != request.visitDates().size()) {
            throw new BusinessException(ErrorCode.DUPLICATE_VISIT_DATE);
        }
        VisitRequestGroup group = groupRepository.save(
                VisitRequestGroup.create(recipient, request.startTime(), request.endTime()));
        List<VisitRequest> requests = request.visitDates().stream()
                .sorted()
                .map(date -> VisitRequest.createInGroup(group, date))
                .toList();
        return VisitRequestGroupResponse.of(group, visitRequestRepository.saveAll(requests));
    }

    public VisitRequestGroupResponse get(Long groupId) {
        VisitRequestGroup group = groupRepository.findWithRecipientById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND));
        return VisitRequestGroupResponse.of(group, visitRequestRepository.findAllWithRecipientByGroupId(groupId));
    }
}
