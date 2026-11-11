package com.hyeongjin.visit.recommendation.domain;

import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.reservation.domain.Reservation;

// 필터가 판단에 쓰는 보호사 한 명의 일정 묶음
// 서비스가 후보 전체 데이터를 한 번에 조회해서 채워 줌 → 필터는 DB 를 몰라도 되고, 단위 테스트가 쉬움
public record CaregiverSchedule(
        Caregiver caregiver,
        List<CaregiverAvailability> availabilities,   // 방문 요일의 근무 가능 시간
        List<Reservation> reservations                // 방문 날짜의 예약
) {
}
