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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnDefault;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 수급자의 방문 요청 (언제 와 주세요)
@Entity
@Table(indexes = @Index(name = "idx_visit_request_group", columnList = "group_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    // 묶음 신청의 하위 요청이면 묶음, 단건 신청이면 null
    // nullable 인 이유: 기존 로컬 DB 에 행이 있고, ddl-auto: update 는 NOT NULL 컬럼에 값을 채워 주지 않음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private VisitRequestGroup group;

    @Column(nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisitRequestStatus status;

    // 같은 요청을 두 관리자가 서로 다른 보호사로 동시에 배정하는 경우를 막음 (온케어 assignCareworker 의 빈틈)
    // 둘 다 REQUESTED 를 보고 assign() 해도, 늦게 커밋하는 쪽은 버전 불일치로 실패
    @Version
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long version;

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

    // 묶음의 날짜 하나에 해당하는 요청. 수급자·시간대는 묶음 것을 그대로 씀
    public static VisitRequest createInGroup(VisitRequestGroup group, LocalDate visitDate) {
        VisitRequest request = create(group.getRecipient(), visitDate, group.getStartTime(), group.getEndTime());
        request.group = group;
        return request;
    }

    public boolean isAssignable() {
        return status == VisitRequestStatus.REQUESTED;
    }

    public void assign() {
        this.status = VisitRequestStatus.ASSIGNED;
    }

    // 배정된 예약이 취소되면 다시 배정을 기다리는 상태로
    public void unassign() {
        this.status = VisitRequestStatus.REQUESTED;
    }

    public void cancel() {
        this.status = VisitRequestStatus.CANCELED;
    }
}
