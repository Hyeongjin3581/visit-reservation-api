package com.hyeongjin.visit.recipient.domain;

import com.hyeongjin.visit.common.entity.BaseTimeEntity;
import com.hyeongjin.visit.common.entity.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 수급자 (방문 서비스를 받는 사람)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recipient extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    // null 이면 성별 상관없음
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender preferredGender;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    private Recipient(String name, Gender preferredGender, double latitude, double longitude) {
        this.name = name;
        this.preferredGender = preferredGender;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static Recipient create(String name, Gender preferredGender, double latitude, double longitude) {
        return new Recipient(name, preferredGender, latitude, longitude);
    }

    public boolean accepts(Gender caregiverGender) {
        return preferredGender == null || preferredGender == caregiverGender;
    }
}
