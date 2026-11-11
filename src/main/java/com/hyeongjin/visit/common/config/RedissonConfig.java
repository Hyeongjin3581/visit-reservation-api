package com.hyeongjin.visit.common.config;

import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.lock.RedissonLockStrategy;
import com.hyeongjin.visit.reservation.service.ReservationWriter;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Redis 는 방법 D 비교용이라 기본으로는 켜지 않음 → Redis 없이도 앱과 테스트가 뜸
// reservation.redis.enabled=true 일 때만 Redis 클라이언트와 방법 D 를 등록
@Configuration
@ConditionalOnProperty(name = "reservation.redis.enabled", havingValue = "true")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${reservation.redis.address:redis://127.0.0.1:6379}") String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }

    @Bean
    public RedissonLockStrategy redissonLockStrategy(RedissonClient redissonClient, ReservationWriter writer,
                                                     VisitRequestRepository visitRequestRepository) {
        return new RedissonLockStrategy(redissonClient, writer, visitRequestRepository);
    }
}
