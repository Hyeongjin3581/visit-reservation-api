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

// 확정된 배정. 3주차에 동시성 제어 방법 A~D 를 비교하는 핵심 엔티티
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        // 방법 A: 같은 보호사 · 같은 날짜 · 같은 시작 시간 예약은 DB 에서 하나만 허용
        // TODO(3주차): 취소된 예약도 슬롯을 차지함 → 취소 후 재예약 처리 방법 결정 (README 한계 항목)
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_caregiver_slot",
                columnNames = {"caregiver_id", "visit_date", "start_time"}),
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

    // 방법 C: 낙관적 락
    @Version
    private Long version;

    private Reservation(Caregiver caregiver, VisitRequest visitRequest) {
        this.caregiver = caregiver;
        this.visitRequest = visitRequest;
        this.visitDate = visitRequest.getVisitDate();
        this.startTime = visitRequest.getStartTime();
        this.endTime = visitRequest.getEndTime();
        this.status = ReservationStatus.CONFIRMED;
    }

    public static Reservation confirm(Caregiver caregiver, VisitRequest visitRequest) {
        return new Reservation(caregiver, visitRequest);
    }

    public void complete() {
        this.status = ReservationStatus.COMPLETED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELED;
    }
}
