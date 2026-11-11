package com.hyeongjin.visit.recommendation.filter;

import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.request.domain.VisitRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 방문 시간 전체가 근무 가능 시간 안에 들어와야 함
@Component
@Order(1)
public class WorkingHoursFilter implements CandidateFilter {

    @Override
    public boolean accept(CaregiverSchedule schedule, VisitRequest request) {
        return schedule.availabilities().stream()
                .anyMatch(availability -> availability.covers(
                        request.getVisitDate().getDayOfWeek(), request.getStartTime(), request.getEndTime()));
    }
}
