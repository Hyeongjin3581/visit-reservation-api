package com.hyeongjin.visit.reservation.lock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.service.ConfirmLock;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

// D. Redis 분산 락 (Redisson). 키: lock:caregiver:{보호사 id}:{방문 날짜}
// 서버가 여러 대여도 같은 Redis 를 보므로 동작. DB 락과 달리 날짜 단위로 잠가서 다른 날짜 예약은 기다리지 않음
// 순서가 중요: 락 획득 → 트랜잭션 시작·커밋 → 락 해제
//   (트랜잭션 안에서 락을 풀면, 커밋 전에 다음 요청이 들어와 아직 안 보이는 예약을 놓칠 수 있음)
// 빈 등록은 RedissonConfig 에서 (reservation.redis.enabled=true 일 때만)
@RequiredArgsConstructor
public class RedissonLockStrategy implements ReservationLockStrategy {

    static final long WAIT_SECONDS = 5;

    private final RedissonClient redissonClient;
    private final ReservationWriter writer;
    private final VisitRequestRepository visitRequestRepository;

    @Override
    public String name() {
        return "redisson";
    }

    @Override
    public Reservation confirm(Long visitRequestId, Long caregiverId) {
        // 락 키에 날짜가 필요해서 요청을 먼저 읽음 (트랜잭션 밖, 확정은 writer 안에서 다시 검증)
        var visitDate = visitRequestRepository.findById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND))
                .getVisitDate();
        RLock lock = redissonClient.getLock(lockKey(caregiverId, visitDate));

        boolean acquired;
        try {
            // leaseTime 을 주지 않으면 Redisson 워치독이 작업 중에 만료 시간을 계속 연장함 (기본 30초)
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RESERVATION_BUSY);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.RESERVATION_BUSY);
        }
        try {
            return writer.confirm(visitRequestId, caregiverId, ConfirmLock.NONE);
        } finally {
            lock.unlock();
        }
    }

    // 묶음: 날짜마다 락 키가 다르므로 날짜별 락을 모두 잡은 뒤 한 트랜잭션으로 확정
    @Override
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
        // 락 키에 날짜가 필요해서 먼저 읽음 (트랜잭션 밖, 확정은 writer 안에서 다시 검증)
        List<LocalDate> dates = visitRequestRepository.findAllWithRecipientByGroupId(groupId).stream()
                .map(VisitRequest::getVisitDate)
                .sorted()      // 모든 요청이 같은 순서(날짜 오름차순)로 잡아야 서로 기다리는 데드락이 안 생김
                .distinct()
                .toList();
        if (dates.isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
        }
        List<RLock> acquired = new ArrayList<>();
        try {
            for (LocalDate date : dates) {
                RLock lock = redissonClient.getLock(lockKey(caregiverId, date));
                if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    // 하나라도 못 잡으면 finally 에서 이미 잡은 것을 모두 풂
                    throw new BusinessException(ErrorCode.RESERVATION_BUSY);
                }
                acquired.add(lock);
            }
            return writer.confirmGroup(groupId, caregiverId, ConfirmLock.NONE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RESERVATION_BUSY);
        } finally {
            // 커밋이 끝난 뒤에 해제 (단건과 같은 원칙), 잡은 역순으로
            Collections.reverse(acquired);
            acquired.forEach(RLock::unlock);
        }
    }

    private static String lockKey(Long caregiverId, LocalDate visitDate) {
        return "lock:caregiver:" + caregiverId + ":" + visitDate;
    }
}
