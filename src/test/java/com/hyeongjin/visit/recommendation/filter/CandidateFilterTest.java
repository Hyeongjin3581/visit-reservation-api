package com.hyeongjin.visit.recommendation.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.reservation.domain.Reservation;
import org.junit.jupiter.api.Test;

// 필터 단위 테스트: DB 없이 일정 묶음만 만들어서 확인
class CandidateFilterTest {

    // 2026-11-16 은 월요일
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private final WorkingHoursFilter workingHours = new WorkingHoursFilter();
    private final ScheduleConflictFilter scheduleConflict = new ScheduleConflictFilter();

    private final Caregiver caregiver = Caregiver.create("김보호", Gender.FEMALE, 37.5, 127.0);
    private final Recipient recipient = Recipient.create("박수급", null, 37.5, 127.0);

    @Test
    void 방문_시간이_근무_가능_시간_안에_있으면_통과() {
        VisitRequest request = request(10, 12);
        CaregiverSchedule schedule = schedule(List.of(availability(9, 18)), List.of());

        assertThat(workingHours.accept(schedule, request)).isTrue();
    }

    @Test
    void 방문_시간이_근무_가능_시간을_벗어나면_제외() {
        VisitRequest request = request(17, 19);
        CaregiverSchedule schedule = schedule(List.of(availability(9, 18)), List.of());

        assertThat(workingHours.accept(schedule, request)).isFalse();
    }

    @Test
    void 근무_가능_시간이_없으면_제외() {
        assertThat(workingHours.accept(schedule(List.of(), List.of()), request(10, 12))).isFalse();
    }

    @Test
    void 시작_시간이_달라도_겹치는_예약이_있으면_제외() {
        Reservation existing = Reservation.confirm(caregiver, request(11, 13));

        assertThat(scheduleConflict.accept(schedule(List.of(), List.of(existing)), request(10, 12))).isFalse();
    }

    @Test
    void 끝나는_시간에_딱_붙은_예약은_겹치지_않는다() {
        Reservation existing = Reservation.confirm(caregiver, request(12, 13));

        assertThat(scheduleConflict.accept(schedule(List.of(), List.of(existing)), request(10, 12))).isTrue();
    }

    @Test
    void 취소된_예약은_겹쳐도_통과() {
        Reservation canceled = Reservation.confirm(caregiver, request(10, 12));
        canceled.cancel();

        assertThat(scheduleConflict.accept(schedule(List.of(), List.of(canceled)), request(10, 12))).isTrue();
    }

    private VisitRequest request(int startHour, int endHour) {
        return VisitRequest.create(recipient, MONDAY, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    private CaregiverAvailability availability(int startHour, int endHour) {
        return CaregiverAvailability.create(caregiver, MONDAY.getDayOfWeek(),
                LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    private CaregiverSchedule schedule(List<CaregiverAvailability> availabilities, List<Reservation> reservations) {
        return new CaregiverSchedule(caregiver, availabilities, reservations);
    }
}
