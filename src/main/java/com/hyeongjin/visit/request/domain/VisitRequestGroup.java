package com.hyeongjin.visit.request.domain;

import java.time.LocalTime;

import com.hyeongjin.visit.common.entity.BaseTimeEntity;
import com.hyeongjin.visit.common.time.TimeSlots;
import com.hyeongjin.visit.recipient.domain.Recipient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 여러 날짜를 같은 시간대로 한 번에 신청한 묶음. 날짜별 요청(VisitRequest)이 이 묶음을 가리킴
// 묶음 자체의 상태 컬럼은 두지 않음: 하위 요청 상태와 어긋날 수 있으므로 하위 요청 상태로만 판단
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequestGroup extends BaseTimeEntity {

    // 2주 단위 정기 방문 기준. 비관적 락을 쥐고 있는 시간의 상한도 이 값으로 정해짐
    public static final int MAX_DATES = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private VisitRequestGroup(Recipient recipient, LocalTime startTime, LocalTime endTime) {
        this.recipient = recipient;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public static VisitRequestGroup create(Recipient recipient, LocalTime startTime, LocalTime endTime) {
        TimeSlots.validateRange(startTime, endTime);
        return new VisitRequestGroup(recipient, startTime, endTime);
    }
}
