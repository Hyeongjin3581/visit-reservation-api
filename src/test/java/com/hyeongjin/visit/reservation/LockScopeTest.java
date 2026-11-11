package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import com.hyeongjin.visit.reservation.lock.CaregiverDayLocks;
import com.hyeongjin.visit.reservation.lock.ReservationLockStrategy;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 락 범위 비교: 같은 보호사의 "다른 날짜" 확정이 기다리는지
// 한 스레드가 락을 쥔 채 커밋하지 않고 멈춰 있는 동안 다른 스레드가 확정 → 끝나면 안 기다림, 시간 초과면 기다림
// 시간을 재서 비교하지 않으므로 H2·MySQL 어디서나 결과가 같음 (mysqlTest 대상)
@Tag("concurrency")
@SpringBootTest
class LockScopeTest extends ConcurrencyTestSupport {

    // 기다리는지 판정: 이 시간 안에 안 끝나면 "기다림". H2 LOCK_TIMEOUT(10초)보다 짧아야 함
    private static final long BLOCKED_JUDGE_MS = 1_000;
    // 안 기다리는지 판정: 이 시간 안에 끝나면 "안 기다림"
    private static final long FREE_JUDGE_MS = 3_000;

    @Autowired private List<ReservationLockStrategy> strategyList;
    @Autowired private CaregiverDayLocks dayLocks;
    @Autowired private CaregiverDayLockRepository dayLockRepository;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private Caregiver caregiver;
    private LocalDate day1;
    private VisitRequest day1Morning;
    private VisitRequest day1Afternoon;
    private VisitRequest day2Morning;

    @BeforeEach
    void setUp() {
        caregiver = allWeekCaregiver("김보호");
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        day1 = visitDate();
        day1Morning = request(recipient, day1, 10);
        day1Afternoon = request(recipient, day1, 14);   // 같은 날이지만 시간은 안 겹침 → 락만 없으면 성공해야 함
        day2Morning = request(recipient, day1.plusDays(1), 10);
    }

    @AfterEach
    void tearDown() {
        release.countDown();   // 테스트가 중간에 실패해도 붙잡은 락이 다음 테스트로 새지 않게
        pool.shutdownNow();
    }

    @Test
    void 날짜_락은_같은_보호사의_다른_날짜를_막지_않고_같은_날짜만_줄_세운다() throws Exception {
        dayLocks.prepare(caregiver.getId(), List.of(day1));
        Future<?> holder = hold(() -> dayLockRepository.findForUpdate(caregiver.getId(), day1).orElseThrow());

        // 다른 날짜: 바로 끝남
        Future<Reservation> otherDay = confirm("pessimistic", day2Morning);
        assertThat(otherDay.get(FREE_JUDGE_MS, TimeUnit.MILLISECONDS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // 같은 날짜: 락이 풀릴 때까지 기다림 → 풀면 성공
        Future<Reservation> sameDay = confirm("pessimistic", day1Afternoon);
        assertBlocked(sameDay);
        release.countDown();
        holder.get(FREE_JUDGE_MS, TimeUnit.MILLISECONDS);
        assertThat(sameDay.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // 범위 축소 전 방식(보호사 행 락)과의 대비: 다른 날짜도 기다림
    @Test
    void 보호사_행_락은_다른_날짜_확정도_기다리게_한다() throws Exception {
        Future<?> holder = hold(() -> em.find(Caregiver.class, caregiver.getId(), LockModeType.PESSIMISTIC_WRITE));

        Future<Reservation> otherDay = confirm("pessimistic-caregiver", day2Morning);
        assertBlocked(otherDay);
        release.countDown();
        holder.get(FREE_JUDGE_MS, TimeUnit.MILLISECONDS);
        assertThat(otherDay.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // 붙잡은 락이 정말 확정을 막았던 것인지: 락 대상 외에는 같은 조건인 day1 오전도 바로 성공
        assertThat(confirm("pessimistic-caregiver", day1Morning).get(FREE_JUDGE_MS, TimeUnit.MILLISECONDS)
                .getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // 트랜잭션 안에서 락을 잡고, release 가 풀릴 때까지 커밋하지 않음
    private Future<?> hold(Runnable lock) throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            lock.run();
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).as("락을 잡았는지").isTrue();
        return holder;
    }

    private Future<Reservation> confirm(String strategyName, VisitRequest request) {
        ReservationLockStrategy strategy = strategy(strategyName);
        return pool.submit(() -> strategy.confirm(request.getId(), caregiver.getId()));
    }

    private void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(BLOCKED_JUDGE_MS, TimeUnit.MILLISECONDS))
                .as("락을 기다려야 함")
                .isInstanceOf(TimeoutException.class);
    }

    private ReservationLockStrategy strategy(String name) {
        Map<String, ReservationLockStrategy> byName = strategyList.stream()
                .collect(Collectors.toMap(ReservationLockStrategy::name, Function.identity()));
        return byName.get(name);
    }

    private VisitRequest request(Recipient recipient, LocalDate date, int startHour) {
        return visitRequestRepository.save(VisitRequest.create(
                recipient, date, LocalTime.of(startHour, 0), LocalTime.of(startHour + 2, 0)));
    }
}
