package com.hyeongjin.visit.reservation.lock;

import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ConfirmLock;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// B'. 보호사 행 전체를 SELECT ... FOR UPDATE 로 잠금 (범위 축소 전의 방법 B)
// 같은 보호사에 대한 확정은 날짜가 달라도 모두 한 줄로 섬
// 기본값(pessimistic)과 전후를 비교하려고 남겨 둠: LockScopeTest, k6 (RESERVATION_LOCK_STRATEGY=pessimistic-caregiver)
@Component
@RequiredArgsConstructor
public class CaregiverRowLockStrategy implements ReservationLockStrategy {

    private final ReservationWriter writer;

    @Override
    public String name() {
        return "pessimistic-caregiver";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        return writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_ROW);
    }

    // 보호사 행 락 하나로 묶음의 모든 날짜가 보호됨
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        return writer.confirmGroup(groupId, caregiverId, ConfirmLock.CAREGIVER_ROW);
    }
}
