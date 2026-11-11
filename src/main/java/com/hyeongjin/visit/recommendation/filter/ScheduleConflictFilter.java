package com.hyeongjin.visit.recommendation.filter;

import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.request.domain.VisitRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 같은 날 취소되지 않은 예약과 시간이 겹치면 제외
// 시작 시간이 달라도 겹치면 걸러냄 (예약 확정 시 유니크 제약이 못 막는 경우를 추천 단계에서는 막음)
@Component
@Order(2)
public class ScheduleConflictFilter implements CandidateFilter {

    @Override
    public boolean accept(CaregiverSchedule schedule, VisitRequest request) {
        return schedule.reservations().stream()
                .filter(reservation -> reservation.getVisitDate().equals(request.getVisitDate()))
                .filter(reservation -> reservation.isActive())
                .noneMatch(reservation -> reservation.overlaps(request.getStartTime(), request.getEndTime()));
    }
}
