package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.reservation.lock.RedissonLockStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 방법 D: Redis 분산 락. Redis 가 127.0.0.1:6379 에 떠 있을 때만 실행 (CI 는 Redis 컨테이너 사용)
@Tag("concurrency")
@EnabledIf("redisAvailable")
@SpringBootTest(properties = "reservation.redis.enabled=true")
class RedissonConcurrencyTest extends ConcurrencyTestSupport {

    @Autowired private RedissonLockStrategy strategy;

    static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void 같은_시작_시간_100건은_1건만_성공() throws Exception {
        Caregiver caregiver = caregiver("김보호");
        List<Long> requestIds = sameSlotRequests();

        Result result = runConcurrently(requestIds, id -> strategy.confirm(id, caregiver.getId()));
        print("redisson", "같은 시작 시간", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.savedReservations()).isEqualTo(1);
    }

    @Test
    void 시작_시간이_다른_겹침_100건도_1건만_성공() throws Exception {
        Caregiver caregiver = caregiver("김보호");
        List<Long> requestIds = overlappingRequests();

        Result result = runConcurrently(requestIds, id -> strategy.confirm(id, caregiver.getId()));
        print("redisson", "시작 시간 다른 겹침", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.overlappingPairs()).isZero();
    }

    // 묶음: 날짜별 락을 날짜 오름차순으로 모두 잡음 → 날짜 조합이 서로 달라도 데드락 없이 1개만 성공
    @Test
    void 서로_겹치는_묶음_21개도_1개만_성공하고_부분_저장이_없다() throws Exception {
        Caregiver caregiver = allWeekCaregiver("김보호");
        List<Long> groupIds = overlappingGroups(21);

        Result result = runConcurrently(groupIds, id -> strategy.confirmGroup(id, caregiver.getId()));
        print("redisson", "겹치는 묶음", result);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.savedReservations()).isEqualTo(2);
        assertThat(result.overlappingPairs()).isZero();
    }
}
