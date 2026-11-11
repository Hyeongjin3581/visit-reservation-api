package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.reservation.lock.ReservationLockStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 같은 보호사에게 동시에 100건의 확정 요청 → 방법 A·B·C 비교 (D 는 RedissonConcurrencyTest)
// 결과는 [동시성] 줄로 출력해서 README 표에 옮김
@Tag("concurrency")
@SpringBootTest
class ReservationConcurrencyTest extends ConcurrencyTestSupport {

    @Autowired private List<ReservationLockStrategy> strategyList;

    private ReservationLockStrategy strategy(String name) {
        Map<String, ReservationLockStrategy> byName = strategyList.stream()
                .collect(Collectors.toMap(ReservationLockStrategy::name, Function.identity()));
        return byName.get(name);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"unique", "pessimistic", "optimistic"})
    void 같은_시작_시간_100건은_모든_방법에서_1건만_성공(String name) throws Exception {
        Caregiver caregiver = caregiver("김보호");
        List<Long> requestIds = sameSlotRequests();

        Result result = runConcurrently(requestIds, id -> strategy(name).confirm(id, caregiver.getId()));
        print(name, "같은 시작 시간", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.savedReservations()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pessimistic", "optimistic"})
    void 시작_시간이_다른_겹침_100건도_락을_쓰면_1건만_성공(String name) throws Exception {
        Caregiver caregiver = caregiver("김보호");
        List<Long> requestIds = overlappingRequests();

        Result result = runConcurrently(requestIds, id -> strategy(name).confirm(id, caregiver.getId()));
        print(name, "시작 시간 다른 겹침", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.overlappingPairs()).isZero();
    }

    // 방법 A 의 한계 재현: 유니크 제약은 시작 시간이 같을 때만 막음
    // 시작 시간이 5종류라 최대 5건까지 들어갈 수 있고, 몇 건이 들어갈지는 스레드 실행 순서에 따라 달라짐
    @Test
    void 유니크_제약만으로는_시작_시간이_다른_겹침을_못_막는다() throws Exception {
        Caregiver caregiver = caregiver("김보호");
        List<Long> requestIds = overlappingRequests();

        Result result = runConcurrently(requestIds, id -> strategy("unique").confirm(id, caregiver.getId()));
        print("unique", "시작 시간 다른 겹침", result);

        assertThat(result.success()).isBetween(1, 5);
    }

    // 온케어 assignCareworker 의 두 번째 빈틈: 같은 요청을 서로 다른 보호사로 동시에 배정
    // 보호사가 달라서 보호사 행 락으로는 서로 안 막힘 → VisitRequest 의 @Version 이 막음
    @Test
    void 같은_요청을_서로_다른_보호사_10명으로_동시에_배정해도_1건만_성공() throws Exception {
        List<Long> requestIds = sameSlotRequests().subList(0, 1);
        Long requestId = requestIds.get(0);
        List<Long> caregiverIds = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            caregiverIds.add(caregiver("보호사" + i).getId());
        }

        Result result = runConcurrently(caregiverIds,
                caregiverId -> strategy("pessimistic").confirm(requestId, caregiverId));
        print("pessimistic", "같은 요청 다른 보호사", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.savedReservations()).isEqualTo(1);
    }
}
