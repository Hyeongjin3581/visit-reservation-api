package com.hyeongjin.visit.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// "오늘"을 서비스 안에서 LocalDate.now() 로 직접 구하지 않고 Clock 으로 주입받음
// → 테스트에서 날짜를 고정할 수 있고, 서버 시간대와 상관없이 한국 날짜 기준으로 계산
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
