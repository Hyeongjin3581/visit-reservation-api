package com.hyeongjin.visit.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// 메인 클래스에 두지 않고 분리 → 웹 계층만 띄우는 테스트에서 JPA 설정이 끌려오지 않음
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
