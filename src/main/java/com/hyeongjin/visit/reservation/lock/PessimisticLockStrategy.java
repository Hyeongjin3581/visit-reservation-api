package com.hyeongjin.visit.reservation.lock;

import java.time.LocalDate;
import java.util.List;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ConfirmLock;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// B. (보호사, 날짜) 잠금 행을 SELECT ... FOR UPDATE 로 잠금
// 같은 보호사·같은 날짜 확정은 한 줄로 서서 하나씩 처리됨 → 앞사람이 커밋한 예약을 뒷사람이 겹침 검사에서 봄
// 겹침은 같은 날짜 안에서만 생기므로, 같은 보호사라도 다른 날짜 확정은 기다리지 않음
// (범위 축소 전의 "보호사 행 전체를 잠그는" 방식은 CaregiverRowLockStrategy 로 비교용으로 남겨 둠)
// 단점: 잠금을 기다리는 동안 DB 커넥션을 붙잡고 있음, 확정마다 잠금 행 확인 쿼리가 몇 번 늘어남
// 순서: 트랜잭션 밖에서 날짜 읽기·잠금 행 준비 → 확정 트랜잭션에서 잠금·재검증·저장
@Component
@RequiredArgsConstructor
public class PessimisticLockStrategy implements ReservationLockStrategy {

    private final ReservationWriter writer;
    private final VisitRequestRepository visitRequestRepository;
    private final CaregiverDayLocks dayLocks;

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        // 잠금 행을 고르려면 날짜가 필요해서 요청을 먼저 읽음 (트랜잭션 밖, 확정은 writer 안에서 다시 검증)
        LocalDate visitDate = visitRequestRepository.findById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND))
                .getVisitDate();
        dayLocks.prepare(caregiverId, List.of(visitDate));
        return writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_DAY);
    }

    // 묶음: 날짜마다 잠금 행을 준비하고, writer 가 날짜 오름차순으로 모두 잠근 뒤 한 트랜잭션으로 확정
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        List<LocalDate> dates = visitRequestRepository.findAllWithRecipientByGroupId(groupId).stream()
                .map(VisitRequest::getVisitDate)
                .toList();
        if (dates.isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
        }
        dayLocks.prepare(caregiverId, dates);
        return writer.confirmGroup(groupId, caregiverId, ConfirmLock.CAREGIVER_DAY);
    }
}
