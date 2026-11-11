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
}
