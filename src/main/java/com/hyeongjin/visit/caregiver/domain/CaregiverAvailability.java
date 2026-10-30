package com.hyeongjin.visit.caregiver.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.hyeongjin.visit.common.entity.BaseTimeEntity;
import com.hyeongjin.visit.common.time.TimeSlots;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 요양보호사의 요일별 근무 가능 시간
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_availability_caregiver_day", columnList = "caregiver_id, day_of_week"))
public class CaregiverAvailability extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "caregiver_id")
    private Caregiver caregiver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private CaregiverAvailability(Caregiver caregiver, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.caregiver = caregiver;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public static CaregiverAvailability create(Caregiver caregiver, DayOfWeek dayOfWeek,
                                               LocalTime startTime, LocalTime endTime) {
        TimeSlots.validateRange(startTime, endTime);
        return new CaregiverAvailability(caregiver, dayOfWeek, startTime, endTime);
    }

    // 방문 시간이 이 근무 가능 시간 안에 완전히 들어오는지 (2주차 추천 필터에서 사용)
    public boolean covers(DayOfWeek day, LocalTime visitStart, LocalTime visitEnd) {
        return dayOfWeek == day && !visitStart.isBefore(startTime) && !visitEnd.isAfter(endTime);
    }
}
