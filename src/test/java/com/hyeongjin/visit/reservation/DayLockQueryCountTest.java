package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.reservation.lock.ReservationLockStrategy;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 날짜 락(B)의 평소 확정 비용: 잠금 행이 이미 있으면 보호사 행 락(B')보다 FOR UPDATE 한 문장만 많아야 함
// (예전에는 확정마다 트랜잭션 밖에서 날짜 조회·잠금 행 확인을 해서 2문장 + 트랜잭션 2개가 더 들었음)
// 실제로 커밋해야 잠금 행이 남으므로 테스트 트랜잭션 없이 실행하고 매번 지움
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class DayLockQueryCountTest extends ConcurrencyTestSupport {

    @Autowired private List<ReservationLockStrategy> strategyList;
    @Autowired private EntityManagerFactory emf;

    @Test
    void 잠금_행이_있으면_확정_쿼리는_보호사_행_락보다_한_문장만_많다() {
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));

        long dayLock = secondConfirmStatements("pessimistic", allWeekCaregiver("날짜락"), recipient);
        long rowLock = secondConfirmStatements("pessimistic-caregiver", allWeekCaregiver("보호사락"), recipient);

        System.out.printf("[측정] 평소 확정 SQL 수: 날짜 락 %d, 보호사 행 락 %d%n", dayLock, rowLock);
        assertThat(dayLock).isEqualTo(rowLock + 1);
    }

    // 같은 보호사·같은 날짜로 두 번 확정하고, 두 번째(잠금 행이 이미 있는 평소 경로)의 SQL 수를 셈
    private long secondConfirmStatements(String strategyName, Caregiver caregiver, Recipient recipient) {
        ReservationLockStrategy strategy = strategy(strategyName);
        VisitRequest morning = request(recipient, 10);
        VisitRequest afternoon = request(recipient, 14);   // 같은 날, 안 겹침

        strategy.confirm(morning.getId(), caregiver.getId());   // 처음 날짜: 잠금 행 준비(B)까지 포함

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        strategy.confirm(afternoon.getId(), caregiver.getId());
        return stats.getPrepareStatementCount();
    }

    private VisitRequest request(Recipient recipient, int startHour) {
        return visitRequestRepository.save(VisitRequest.create(
                recipient, visitDate(), LocalTime.of(startHour, 0), LocalTime.of(startHour + 2, 0)));
    }

    private ReservationLockStrategy strategy(String name) {
        Map<String, ReservationLockStrategy> byName = strategyList.stream()
                .collect(Collectors.toMap(ReservationLockStrategy::name, Function.identity()));
        return byName.get(name);
    }
}
