package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestStatus;
import com.hyeongjin.visit.reservation.lock.ReservationLockStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 묶음 일괄 확정의 동시성: 겹치는 묶음끼리, 묶음과 단건끼리, 같은 묶음을 여러 보호사로
// 테스트 트랜잭션 없이 실제로 커밋·롤백하므로 "부분 저장 없음"을 DB 에서 확인할 수 있음
// mysqlTest 대상 (@Tag("concurrency")) → MySQL REPEATABLE READ 기본값에서도 READ COMMITTED 명시가 지켜지는지 확인
@Tag("concurrency")
@SpringBootTest
class GroupReservationConcurrencyTest extends ConcurrencyTestSupport {

    static final int GROUPS = 21;
    static final int SINGLES = 50;

    @Autowired private List<ReservationLockStrategy> strategyList;

    private ReservationLockStrategy strategy(String name) {
        Map<String, ReservationLockStrategy> byName = strategyList.stream()
                .collect(Collectors.toMap(ReservationLockStrategy::name, Function.identity()));
        return byName.get(name);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pessimistic", "pessimistic-caregiver", "optimistic"})
    void 서로_겹치는_묶음_21개를_동시에_확정하면_1개만_성공하고_부분_저장이_없다(String name) throws Exception {
        Caregiver caregiver = allWeekCaregiver("김보호");
        List<Long> groupIds = overlappingGroups(GROUPS);

        Result result = runConcurrently(groupIds, id -> strategy(name).confirmGroup(id, caregiver.getId()));
        print(name, "겹치는 묶음", result);

        assertThat(result.success()).isEqualTo(1);
        // 성공한 묶음의 날짜 2개만 저장 = 실패한 묶음은 한 날짜도 남지 않음
        assertThat(result.savedReservations()).isEqualTo(2);
        assertThat(result.overlappingPairs()).isZero();
    }

    // 묶음(3일)과 그 둘째 날에 겹치는 단건 50개를 같은 보호사로 동시에 확정
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pessimistic", "pessimistic-caregiver", "optimistic"})
    void 묶음과_겹치는_단건이_동시에_와도_겹침이_없고_묶음은_전부_아니면_전무(String name) throws Exception {
        Caregiver caregiver = allWeekCaregiver("김보호");
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        Long groupId = group(recipient,
                List.of(visitDate(), visitDate().plusDays(1), visitDate().plusDays(2)),
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < SINGLES; i++) {
            LocalTime start = LocalTime.of(9, 0).plusMinutes(30L * (i % 5));
            ids.add(visitRequestRepository.save(VisitRequest.create(
                    recipient, visitDate().plusDays(1), start, start.plusMinutes(150))).getId());
        }
        ids.add(-groupId);   // 음수는 묶음 확정으로 구분

        Result result = runConcurrently(ids, id -> id < 0
                ? strategy(name).confirmGroup(-id, caregiver.getId())
                : strategy(name).confirm(id, caregiver.getId()));
        print(name, "묶음 + 겹치는 단건", result);

        List<VisitRequestStatus> groupStatuses = visitRequestRepository.findAllWithRecipientByGroupId(groupId)
                .stream().map(VisitRequest::getStatus).toList();
        assertThat(result.overlappingPairs()).isZero();
        assertThat(result.success()).isEqualTo(1);
        if (groupStatuses.contains(VisitRequestStatus.ASSIGNED)) {
            // 묶음이 이김: 3일 모두 배정, 단건은 0건
            assertThat(groupStatuses).containsOnly(VisitRequestStatus.ASSIGNED);
            assertThat(result.savedReservations()).isEqualTo(3);
        } else {
            // 단건이 이김: 묶음은 한 날짜도 배정되지 않음
            assertThat(groupStatuses).containsOnly(VisitRequestStatus.REQUESTED);
            assertThat(result.savedReservations()).isEqualTo(1);
        }
    }

    // 보호사가 서로 달라 보호사 락으로는 안 막힘 → 하위 요청들의 @Version 이 막음
    @Test
    void 같은_묶음을_서로_다른_보호사_10명으로_동시에_확정해도_1명만_성공() throws Exception {
        Recipient recipient = recipientRepository.save(Recipient.create("박수급", null, 37.5665, 126.9780));
        Long groupId = group(recipient,
                List.of(visitDate(), visitDate().plusDays(1), visitDate().plusDays(2)),
                LocalTime.of(10, 0), LocalTime.of(12, 0));
        List<Long> caregiverIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            caregiverIds.add(allWeekCaregiver("보호사" + i).getId());
        }

        Result result = runConcurrently(caregiverIds,
                caregiverId -> strategy("pessimistic").confirmGroup(groupId, caregiverId));
        print("pessimistic", "같은 묶음 다른 보호사", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.savedReservations()).isEqualTo(3);
    }
}
