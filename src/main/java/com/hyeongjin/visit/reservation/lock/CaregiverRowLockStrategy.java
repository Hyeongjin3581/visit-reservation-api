package com.hyeongjin.visit.reservation.lock;

import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ConfirmLock;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// B'. 보호사 행 전체를 SELECT ... FOR UPDATE 로 잠금 (기본값, 범위 축소 전의 방법 B)
// 같은 보호사에 대한 확정은 날짜가 달라도 모두 한 줄로 섬
// 날짜 락(pessimistic)보다 경합이 낮은 k6 부하에서 약 30% 빨라서 기본값으로 씀 (README "k6 부하 테스트")
// 락 범위 차이는 LockScopeTest 가 보여 줌: 이 방식은 같은 보호사의 다른 날짜 확정도 기다리게 함
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
