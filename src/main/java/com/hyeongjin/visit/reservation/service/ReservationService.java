package com.hyeongjin.visit.reservation.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.dto.GroupReservationResponse;
import com.hyeongjin.visit.reservation.dto.ReservationResponse;
import com.hyeongjin.visit.reservation.lock.ReservationLockStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 설정한 락 전략으로 예약을 확정
// 서비스 자체에는 @Transactional 을 붙이지 않음: 낙관적 락 재시도·Redis 락은 트랜잭션 바깥에서 감싸야 하기 때문
@Service
public class ReservationService {

    private final ReservationLockStrategy lockStrategy;
    private final ReservationWriter writer;

    public ReservationService(List<ReservationLockStrategy> strategies,
                              @Value("${reservation.lock.strategy:pessimistic-caregiver}") String strategyName,
                              ReservationWriter writer) {
        Map<String, ReservationLockStrategy> byName = strategies.stream()
                .collect(Collectors.toMap(ReservationLockStrategy::name, Function.identity()));
        this.lockStrategy = byName.get(strategyName);
        if (this.lockStrategy == null) {
            // 오타나 redisson 을 켜지 않고 고른 경우 → 앱이 뜰 때 바로 알 수 있게 실패
            throw new IllegalStateException("알 수 없는 락 전략: " + strategyName + " (사용 가능: " + byName.keySet() + ")");
        }
        this.writer = writer;
    }

    public ReservationResponse confirm(Long visitRequestId, Long caregiverId) {
        return ReservationResponse.from(lockStrategy.confirm(visitRequestId, caregiverId));
    }

    public ReservationResponse cancel(Long reservationId) {
        return ReservationResponse.from(writer.cancel(reservationId));
    }

    public GroupReservationResponse confirmGroup(Long groupId, Long caregiverId) {
        return GroupReservationResponse.of(groupId, caregiverId, lockStrategy.confirmGroup(groupId, caregiverId));
    }

    public GroupReservationResponse cancelGroup(Long groupId) {
        List<Reservation> canceled = writer.cancelGroup(groupId);
        return GroupReservationResponse.of(groupId, canceled.get(0).getCaregiver().getId(), canceled);
    }

    public String strategyName() {
        return lockStrategy.name();
    }
}
