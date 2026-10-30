package com.hyeongjin.visit.request.domain;

import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.common.entity.BaseTimeEntity;
import com.hyeongjin.visit.common.time.TimeSlots;
import com.hyeongjin.visit.recipient.domain.Recipient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 수급자의 방문 요청 (언제 와 주세요)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    @Column(nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisitRequestStatus status;

    private VisitRequest(Recipient recipient, LocalDate visitDate, LocalTime startTime, LocalTime endTime) {
        this.recipient = recipient;
        this.visitDate = visitDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = VisitRequestStatus.REQUESTED;
    }

    public static VisitRequest create(Recipient recipient, LocalDate visitDate, LocalTime startTime, LocalTime endTime) {
        TimeSlots.validateRange(startTime, endTime);
        return new VisitRequest(recipient, visitDate, startTime, endTime);
    }

    public boolean isAssignable() {
        return status == VisitRequestStatus.REQUESTED;
    }

    // 3주차 예약 확정에서 사용
    public void assign() {
        this.status = VisitRequestStatus.ASSIGNED;
    }

    public void cancel() {
        this.status = VisitRequestStatus.CANCELED;
    }
}
