package com.hyeongjin.visit.caregiver.domain;

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

// 요양보호사
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Caregiver extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaregiverStatus status;

    // 좌표는 등록 시점에 확정 (외부 지도 API 의존 제거)
    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    private Caregiver(String name, Gender gender, double latitude, double longitude) {
        this.name = name;
        this.gender = gender;
        this.status = CaregiverStatus.ACTIVE;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static Caregiver create(String name, Gender gender, double latitude, double longitude) {
        return new Caregiver(name, gender, latitude, longitude);
    }

    public void deactivate() {
        this.status = CaregiverStatus.INACTIVE;
    }

    public boolean isActive() {
        return status == CaregiverStatus.ACTIVE;
    }
}
