package com.hyeongjin.visit.reservation.lock;

import java.util.List;
import java.util.function.Supplier;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ConfirmLock;
import com.hyeongjin.visit.reservation.service.MissingDayLockException;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// B. (보호사, 날짜) 잠금 행을 SELECT ... FOR UPDATE 로 잠금
// 같은 보호사·같은 날짜 확정은 한 줄로 서서 하나씩 처리됨 → 앞사람이 커밋한 예약을 뒷사람이 겹침 검사에서 봄
// 겹침은 같은 날짜 안에서만 생기므로, 같은 보호사라도 다른 날짜 확정은 기다리지 않음
// (범위 축소 전의 "보호사 행 전체를 잠그는" 방식은 CaregiverRowLockStrategy 로 비교용으로 남겨 둠)
// 단점: 잠금을 기다리는 동안 DB 커넥션을 붙잡고 있음, 처음 확정하는 (보호사, 날짜)는 한 번 다시 시도함
@Component
@RequiredArgsConstructor
public class PessimisticLockStrategy implements ReservationLockStrategy {

    private final ReservationWriter writer;
    private final CaregiverDayLocks dayLocks;

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        return withDayLocks(() -> writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_DAY));
    }

    // 묶음: writer 가 날짜 오름차순으로 모두 잠근 뒤 한 트랜잭션으로 확정
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        return withDayLocks(() -> writer.confirmGroup(groupId, caregiverId, ConfirmLock.CAREGIVER_DAY));
    }

    // 평소엔 바로 확정 (트랜잭션 1개, 그 밖의 조회 없음)
    // 처음 확정하는 (보호사, 날짜)라 잠금 행이 없을 때만: 롤백 → 트랜잭션 밖에서 행 만들기 → 한 번 다시 시도
    // (확정마다 미리 날짜를 읽고 행 존재를 확인하던 조회 2번을 없앰. k6 에서 이 준비 비용 때문에 보호사 행 락보다 느렸음)
    private <T> T withDayLocks(Supplier<T> confirm) {
        try {
            return confirm.get();
        } catch (MissingDayLockException e) {
            dayLocks.prepare(e.getCaregiverId(), e.getDates());
            try {
                return confirm.get();
            } catch (MissingDayLockException again) {
                throw new IllegalStateException("잠금 행을 만들지 못했습니다", again);
            }
        }
    }
}
