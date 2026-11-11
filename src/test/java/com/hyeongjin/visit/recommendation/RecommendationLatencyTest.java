package com.hyeongjin.visit.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.recommendation.service.RecommendationRanker;
import com.hyeongjin.visit.recommendation.service.RecommendationService;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// 보호사 1,000명 데이터로 추천 한 번의 응답 시간(서비스 호출 기준) p50·p95 측정
// 오래 걸려서 기본 테스트에서는 빠짐. 실행: ./gradlew mysqlTest (MySQL) 또는 ./gradlew test -Pperf (H2, 참고용)
// HTTP 왕복은 포함하지 않음 (k6 부하 테스트는 따로)
@Tag("perf")
@SpringBootTest
class RecommendationLatencyTest {

    static final int CAREGIVERS = 1_000;
    static final int WARM_UP = 5;
    static final int ONCARE_RUNS = 20;     // 한 번에 수천 쿼리라 적게
    static final int IMPROVED_RUNS = 100;

    @Autowired private RecommendationService recommendationService;
    @Autowired private RecommendationRanker ranker;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @Autowired private CaregiverRepository caregiverRepository;
    @Autowired private CaregiverAvailabilityRepository availabilityRepository;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;
    @Autowired private VisitRequestGroupRepository visitRequestGroupRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private CaregiverDayLockRepository caregiverDayLockRepository;
    @PersistenceContext private EntityManager em;

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        visitRequestRepository.deleteAllInBatch();
        // visit_request.group_id 가 묶음을 가리키므로 요청을 지운 다음에 지움
        visitRequestGroupRepository.deleteAllInBatch();
        availabilityRepository.deleteAllInBatch();
        // caregiver_day_lock.caregiver_id 가 보호사를 가리키므로 보호사보다 먼저 지움
        caregiverDayLockRepository.deleteAllInBatch();
        caregiverRepository.deleteAllInBatch();
        recipientRepository.deleteAllInBatch();
    }

    @Test
    void 보호사_1000명_추천_응답_시간() {
        LocalDate today = LocalDate.now(clock);
        Long requestId = seed(today);

        long[] oncare = measure(ONCARE_RUNS, () -> transactionTemplate.execute(status -> {
            em.clear();
            return ranker.rank(new OncareStyleCandidateLoader(em).load(requestId, today), 3).size();
        }));
        long[] improved = measure(IMPROVED_RUNS, () -> recommendationService.recommend(requestId, 3).candidates().size());

        System.out.printf("[응답시간] 보호사 %d명: 온케어 방식 p50=%dms p95=%dms → 집계 쿼리 방식 p50=%dms p95=%dms%n",
                CAREGIVERS, percentile(oncare, 50), percentile(oncare, 95),
                percentile(improved, 50), percentile(improved, 95));
        assertThat(percentile(improved, 95)).isLessThan(percentile(oncare, 95));
    }

    private long[] measure(int runs, Supplier<Integer> call) {
        for (int i = 0; i < WARM_UP; i++) {
            call.get();
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            long begin = System.nanoTime();
            assertThat(call.get()).isEqualTo(3);
            times.add((System.nanoTime() - begin) / 1_000_000);
        }
        Collections.sort(times);
        return times.stream().mapToLong(Long::longValue).toArray();
    }

    private long percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    // 보호사 1,000명 (모두 필터 통과), 보호사마다 완료 방문 2건
    private Long seed(LocalDate today) {
        Recipient target = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        Recipient other = recipientRepository.save(Recipient.create("최수급", null, 37.5665, 126.9780));
        LocalDate visitDate = today.plusDays(1);
        Long requestId = visitRequestRepository.save(
                VisitRequest.create(target, visitDate, LocalTime.of(10, 0), LocalTime.of(12, 0))).getId();

        List<Caregiver> caregivers = new ArrayList<>();
        List<CaregiverAvailability> availabilities = new ArrayList<>();
        for (int i = 0; i < CAREGIVERS; i++) {
            Caregiver caregiver = Caregiver.create("보호사" + i, i % 2 == 0 ? Gender.FEMALE : Gender.MALE,
                    37.4 + (i % 40) * 0.008, 126.8 + (i / 40) * 0.012);   // 서울 근처 약 35km × 35km 에 흩뿌림
            caregivers.add(caregiver);
            availabilities.add(CaregiverAvailability.create(caregiver, visitDate.getDayOfWeek(),
                    LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }
        caregiverRepository.saveAll(caregivers);
        availabilityRepository.saveAll(availabilities);

        List<VisitRequest> pastRequests = new ArrayList<>();
        List<Reservation> history = new ArrayList<>();
        for (int i = 0; i < CAREGIVERS; i++) {
            Caregiver caregiver = caregivers.get(i);
            addCompleted(pastRequests, history, caregiver, i % 2 == 0 ? target : other, today.minusDays(i % 25 + 1));
            addCompleted(pastRequests, history, caregiver, target, today.minusDays(40));
        }
        visitRequestRepository.saveAll(pastRequests);
        reservationRepository.saveAll(history);
        return requestId;
    }

    private void addCompleted(List<VisitRequest> requests, List<Reservation> reservations,
                              Caregiver caregiver, Recipient recipient, LocalDate date) {
        VisitRequest past = VisitRequest.create(recipient, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Reservation reservation = Reservation.confirm(caregiver, past);
        reservation.complete();
        requests.add(past);
        reservations.add(reservation);
    }
}
