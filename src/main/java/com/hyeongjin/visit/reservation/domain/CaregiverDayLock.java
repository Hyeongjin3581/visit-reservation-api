package com.hyeongjin.visit.reservation.domain;

import java.time.LocalDate;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 방법 B 의 잠금 전용 행: (보호사, 날짜)마다 하나
// 이 행을 SELECT ... FOR UPDATE 로 잠가서 같은 보호사·같은 날짜 확정만 줄 세움 (다른 날짜끼리는 서로 기다리지 않음)
// 예약 행 대신 따로 두는 이유: 예약이 0건인 날짜도 잠글 수 있어야 함 (확정 트랜잭션은 READ COMMITTED 라 갭 락이 없음)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_caregiver_day_lock", columnNames = {"caregiver_id", "visit_date"}))
public class CaregiverDayLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 잠금 행은 트랜잭션 밖에서 id 만 알고 만들므로 FK 값을 직접 가짐
    @Column(name = "caregiver_id", nullable = false)
    private Long caregiverId;

    // FK 제약을 만들기 위한 읽기 전용 연관 (쓰기는 caregiverId 로만)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caregiver_id", insertable = false, updatable = false)
    private Caregiver caregiver;

    @Column(nullable = false)
    private LocalDate visitDate;

    private CaregiverDayLock(Long caregiverId, LocalDate visitDate) {
        this.caregiverId = caregiverId;
        this.visitDate = visitDate;
    }

    public static CaregiverDayLock of(Long caregiverId, LocalDate visitDate) {
        return new CaregiverDayLock(caregiverId, visitDate);
    }
}
