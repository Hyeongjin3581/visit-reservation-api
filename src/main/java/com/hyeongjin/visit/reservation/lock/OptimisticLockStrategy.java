package com.hyeongjin.visit.reservation.lock;

import java.util.List;
import java.util.function.Supplier;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

// C. 보호사 version 을 확정할 때마다 올림 (OPTIMISTIC_FORCE_INCREMENT)
// 동시에 들어온 두 확정 중 늦게 커밋하는 쪽은 version 이 달라 실패 → 처음부터 다시 시도하면 겹침 검사에서 걸림
// 재시도는 트랜잭션 바깥에서 해야 함 (실패한 트랜잭션은 롤백 전용이 되므로 새 트랜잭션으로 다시 시작)
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimisticLockStrategy implements ReservationLockStrategy {

    static final int MAX_ATTEMPTS = 5;

    private final ReservationWriter writer;

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        return retry(() -> writer.confirm(visitRequestId, caregiverId, LockModeType.OPTIMISTIC_FORCE_INCREMENT));
    }

    // 묶음이 충돌하면 묶음 전체를 새 트랜잭션으로 다시 시도 (앞 시도는 통째로 롤백됐으므로 부분 저장 없음)
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        return retry(() -> writer.confirmGroup(groupId, caregiverId, LockModeType.OPTIMISTIC_FORCE_INCREMENT));
    }

    private <T> T retry(Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (ConcurrencyFailureException e) {
                log.debug("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_ATTEMPTS);
            }
        }
        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
    }
}
