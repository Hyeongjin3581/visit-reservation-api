package com.hyeongjin.visit.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

// 동시성 테스트 공통 준비: 데이터 만들기, 여러 스레드로 동시에 확정 요청 보내기, 결과 세기
// 테스트 트랜잭션(@Transactional)을 쓰면 스레드끼리 데이터가 안 보이므로, 실제로 커밋하고 매번 지움
abstract class ConcurrencyTestSupport {

    static final int REQUESTS = 100;
    static final int THREADS = 32;

    @Autowired Clock clock;
    @Autowired CaregiverRepository caregiverRepository;
    @Autowired CaregiverAvailabilityRepository availabilityRepository;
    @Autowired RecipientRepository recipientRepository;
    @Autowired VisitRequestRepository visitRequestRepository;
    @Autowired ReservationRepository reservationRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        visitRequestRepository.deleteAllInBatch();
        availabilityRepository.deleteAllInBatch();
        caregiverRepository.deleteAllInBatch();
        recipientRepository.deleteAllInBatch();
    }

    LocalDate visitDate() {
        return LocalDate.now(clock).plusDays(1);
    }

    Caregiver caregiver(String name) {
        Caregiver caregiver = caregiverRepository.save(Caregiver.create(name, Gender.FEMALE, 37.57, 126.98));
        availabilityRepository.save(CaregiverAvailability.create(
                caregiver, visitDate().getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(20, 0)));
        return caregiver;
    }

    // 시나리오 1: 100건 모두 10:00~12:00 (시작 시간까지 같음)
    List<Long> sameSlotRequests() {
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            ids.add(visitRequestRepository.save(VisitRequest.create(
                    recipient, visitDate(), LocalTime.of(10, 0), LocalTime.of(12, 0))).getId());
        }
        return ids;
    }

    // 시나리오 2: 시작 시간이 09:00~11:00 사이로 다르지만 모두 11:00~11:30 을 포함해서 서로 겹침 (길이 2시간 30분)
    List<Long> overlappingRequests() {
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            LocalTime start = LocalTime.of(9, 0).plusMinutes(30L * (i % 5));
            ids.add(visitRequestRepository.save(VisitRequest.create(
                    recipient, visitDate(), start, start.plusMinutes(150))).getId());
        }
        return ids;
    }

    // 작업들을 출발선(start)에 세워 두었다가 한꺼번에 출발시킴
    Result runConcurrently(List<Long> requestIds, Function<Long, Reservation> confirm) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requestIds.size());
        AtomicInteger success = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (Long requestId : requestIds) {
            pool.submit(() -> {
                try {
                    start.await();
                    confirm.apply(requestId);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failures.computeIfAbsent(e.getErrorCode().name(), k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdown();

        Map<String, Integer> failureCounts = new TreeMap<>();
        failures.forEach((k, v) -> failureCounts.put(k, v.get()));
        return new Result(success.get(), activeReservations(), overlappingPairs(), failureCounts, elapsedMs);
    }

    private long activeReservations() {
        return reservationRepository.findAll().stream().filter(Reservation::isActive).count();
    }

    // 같은 보호사의 살아 있는 예약 중 시간이 겹치는 쌍의 수 (0 이어야 중복 배정 없음)
    private long overlappingPairs() {
        List<Reservation> active = reservationRepository.findAll().stream().filter(Reservation::isActive).toList();
        long pairs = 0;
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                Reservation a = active.get(i);
                Reservation b = active.get(j);
                if (a.getCaregiver().getId().equals(b.getCaregiver().getId())
                        && a.getVisitDate().equals(b.getVisitDate())
                        && a.overlaps(b.getStartTime(), b.getEndTime())) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    void print(String strategy, String scenario, Result result) {
        System.out.printf("[동시성] 전략=%s 시나리오=%s 성공=%d 저장된예약=%d 겹친쌍=%d 소요=%dms 실패=%s%n",
                strategy, scenario, result.success(), result.savedReservations(), result.overlappingPairs(),
                result.elapsedMs(), result.failures());
    }

    record Result(int success, long savedReservations, long overlappingPairs,
                  Map<String, Integer> failures, long elapsedMs) {
    }
}
