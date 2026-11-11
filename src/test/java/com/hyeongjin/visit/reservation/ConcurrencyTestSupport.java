package com.hyeongjin.visit.reservation;

import java.time.Clock;
import java.time.DayOfWeek;
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
import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
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
    @Autowired VisitRequestGroupRepository visitRequestGroupRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired CaregiverDayLockRepository caregiverDayLockRepository;

    @BeforeEach
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

    // 묶음 시나리오용: 모든 요일 08~20시 근무
    Caregiver allWeekCaregiver(String name) {
        Caregiver caregiver = caregiverRepository.save(Caregiver.create(name, Gender.FEMALE, 37.57, 126.98));
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(CaregiverAvailability.create(
                    caregiver, day, LocalTime.of(8, 0), LocalTime.of(20, 0)));
        }
        return caregiver;
    }

    // 묶음 시나리오 1: 날짜 3개(D1·D2·D3) 중 2개씩 고른 묶음 n개. 어느 두 묶음이든 날짜 하나 이상을 공유하고,
    // 시작 시간이 09:00~11:00 으로 달라도 모두 11:00~11:30 을 포함해서 서로 겹침 → 전체에서 1개 묶음만 성공해야 함
    List<Long> overlappingGroups(int count) {
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        LocalDate d1 = visitDate();
        List<List<LocalDate>> pairs = List.of(
                List.of(d1, d1.plusDays(1)),
                List.of(d1.plusDays(2), d1.plusDays(1)),   // 등록 순서를 섞어도 락은 날짜 오름차순으로 잡음
                List.of(d1.plusDays(2), d1));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalTime start = LocalTime.of(9, 0).plusMinutes(30L * (i % 5));
            ids.add(group(recipient, pairs.get(i % pairs.size()), start, start.plusMinutes(150)));
        }
        return ids;
    }

    Long group(Recipient recipient, List<LocalDate> dates, LocalTime start, LocalTime end) {
        VisitRequestGroup group = visitRequestGroupRepository.save(VisitRequestGroup.create(recipient, start, end));
        for (LocalDate date : dates) {
            visitRequestRepository.save(VisitRequest.createInGroup(group, date));
        }
        return group.getId();
    }

    // 작업들을 출발선(start)에 세워 두었다가 한꺼번에 출발시킴 (단건이면 Reservation, 묶음이면 List<Reservation> 을 돌려줌)
    Result runConcurrently(List<Long> requestIds, Function<Long, ?> confirm) throws InterruptedException {
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
