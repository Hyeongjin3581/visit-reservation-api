package com.hyeongjin.visit.reservation.lock;

import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// B. 보호사 행을 SELECT ... FOR UPDATE 로 잠금
// 같은 보호사에 대한 확정은 한 줄로 서서 하나씩 처리됨 → 앞사람이 커밋한 예약을 뒷사람이 겹침 검사에서 봄
// 단점: 잠금을 기다리는 동안 DB 커넥션을 붙잡고 있음, 보호사 단위로 잠가서 다른 날짜 예약끼리도 기다림
@Component
@RequiredArgsConstructor
public class PessimisticLockStrategy implements ReservationLockStrategy {

    private final ReservationWriter writer;

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        return writer.confirm(visitRequestId, caregiverId, LockModeType.PESSIMISTIC_WRITE);
    }

    // 보호사 행 락 하나로 묶음의 모든 날짜가 보호됨 (락을 쥐는 시간은 날짜 수에 비례, 최대 14일)
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        return writer.confirmGroup(groupId, caregiverId, LockModeType.PESSIMISTIC_WRITE);
    }
}
