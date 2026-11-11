package com.hyeongjin.visit.reservation.domain;

import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.common.entity.BaseTimeEntity;
import com.hyeongjin.visit.request.domain.VisitRequest;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 확정된 배정
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        // 방법 A: 같은 보호사 · 같은 날짜 · 같은 시작 시간의 "살아 있는" 예약은 DB 에서 하나만 허용
        // 취소하면 activeSlot 을 NULL 로 바꿈 → 유니크 인덱스는 NULL 끼리 중복으로 보지 않으므로 같은 슬롯에 다시 예약 가능
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_active_slot",
                columnNames = {"caregiver_id", "visit_date", "start_time", "active_slot"}),
        indexes = {
                @Index(name = "idx_reservation_caregiver_date", columnList = "caregiver_id, visit_date"),
                @Index(name = "idx_reservation_visit_request", columnList = "visit_request_id")
        })
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "caregiver_id")
    private Caregiver caregiver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_request_id")
    private VisitRequest visitRequest;

    // 요청에서 복사해 둠 → 일정 중복 확인 시 요청 테이블 조인 없이 조회
    @Column(nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    // 유니크 제약 전용 칸: 살아 있는 예약은 TRUE, 취소된 예약은 NULL
    @Column(name = "active_slot")
    private Boolean activeSlot;

    // 같은 예약을 동시에 바꾸는 경우(취소와 완료가 동시에 들어옴 등)를 막음
    // 새 예약끼리의 충돌은 이 칸으로 못 막음 → 방법 C 는 Caregiver 의 version 을 사용
    @Version
    private Long version;

    private Reservation(Caregiver caregiver, VisitRequest visitRequest) {
        this.caregiver = caregiver;
        this.visitRequest = visitRequest;
        this.visitDate = visitRequest.getVisitDate();
        this.startTime = visitRequest.getStartTime();
        this.endTime = visitRequest.getEndTime();
        this.status = ReservationStatus.CONFIRMED;
        this.activeSlot = Boolean.TRUE;
    }

    public static Reservation confirm(Caregiver caregiver, VisitRequest visitRequest) {
        return new Reservation(caregiver, visitRequest);
    }

    public void complete() {
        this.status = ReservationStatus.COMPLETED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELED;
        this.activeSlot = null;
    }

    public boolean isCanceled() {
        return status == ReservationStatus.CANCELED;
    }

    // 취소되지 않은 예약만 보호사의 시간을 차지함
    public boolean isActive() {
        return !isCanceled();
    }

    // 시간대가 조금이라도 겹치는지 (10:00~12:00 과 12:00~13:00 은 안 겹침)
    public boolean overlaps(LocalTime otherStart, LocalTime otherEnd) {
        return startTime.isBefore(otherEnd) && endTime.isAfter(otherStart);
    }
}
