package com.hyeongjin.visit.reservation.lock;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// A. 락 없이 DB 유니크 제약에만 맡김
// 같은 시작 시간은 막지만, 10:00~12:00 과 10:30~12:30 처럼 시작 시간이 다르면 둘 다 들어갈 수 있음
@Component
@RequiredArgsConstructor
public class UniqueConstraintStrategy implements ReservationLockStrategy {

    private final ReservationWriter writer;

    @Override
    public String name() {
        return "unique";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        return writer.confirm(visitRequestId, caregiverId, LockModeType.NONE);
    }
}
