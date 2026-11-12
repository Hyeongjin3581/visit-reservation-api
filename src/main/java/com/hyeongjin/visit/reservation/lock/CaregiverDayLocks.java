package com.hyeongjin.visit.reservation.lock;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.reservation.domain.CaregiverDayLock;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 방법 B 의 (보호사, 날짜) 잠금 행을 확정 전에 준비
// SELECT ... FOR UPDATE 는 "있는 행"만 잠그므로, 처음 예약되는 날짜는 행부터 만들어 둬야 함
@Slf4j
@Component
@RequiredArgsConstructor
public class CaregiverDayLocks {

    private final CaregiverDayLockRepository dayLockRepository;
    private final CaregiverRepository caregiverRepository;

    // 확정 트랜잭션 "밖"에서 호출: 확정 중 잠금 행이 없어 롤백된 뒤 (처음 확정하는 날짜에서만)
    // 새 트랜잭션(REQUIRES_NEW)을 열지 않음: 트랜잭션 안에서 새 트랜잭션으로 만들면 요청 하나가 커넥션을 2개 쥐고,
    // 동시 요청이 커넥션 풀보다 많으면 모두가 두 번째 커넥션을 기다리며 멈춤 (풀 고갈)
    // 이미 트랜잭션이 있으면 그 트랜잭션에 합류해서 만듦 (커넥션을 새로 잡지 않음)
    //   운영 경로(ReservationService)는 트랜잭션이 없으므로, 합류는 @Transactional 테스트에서만 일어남
    //   합류한 상태에서 유니크 위반이 나면 바깥 트랜잭션이 롤백 전용이 되므로 동시 요청이 있는 곳에서는 쓰지 말 것
    public void prepare(Long caregiverId, Collection<LocalDate> dates) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.debug("잠금 행 준비가 기존 트랜잭션에 합류함 (테스트 트랜잭션 등)");
        }
        Set<LocalDate> missing = new TreeSet<>(dates);
        missing.removeAll(dayLockRepository.findExistingDates(caregiverId, dates));   // 대부분 여기서 끝 (SELECT 1번)
        // 없는 보호사면 만들지 않고 넘김 → 확정 트랜잭션이 CAREGIVER_NOT_FOUND 로 응답
        // (FK 위반에 기대면, 트랜잭션에 합류한 경우 실패한 엔티티가 세션에 남아 이후 flush 가 깨짐)
        if (missing.isEmpty() || !caregiverRepository.existsById(caregiverId)) {
            return;
        }
        for (LocalDate date : missing) {
            try {
                // 트랜잭션 밖이면 saveAndFlush 자체 트랜잭션으로 바로 커밋 → 커넥션을 잠깐만 씀
                dayLockRepository.saveAndFlush(CaregiverDayLock.of(caregiverId, date));
            } catch (DataIntegrityViolationException e) {
                // 다른 요청이 같은 행을 먼저 만듦(유니크 위반) → 잠글 행이 생겼으니 목적 달성
            }
        }
    }
}
