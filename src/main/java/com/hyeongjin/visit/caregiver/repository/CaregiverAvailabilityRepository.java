package com.hyeongjin.visit.caregiver.repository;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverAvailabilityRepository extends JpaRepository<CaregiverAvailability, Long> {

    List<CaregiverAvailability> findByCaregiverId(Long caregiverId);

    // 후보 전체의 해당 요일들 근무 가능 시간을 한 번에 조회 (추천 필터·확정 재검증용)
    // 단건은 요일 1개, 묶음은 묶음의 요일 전체 → 요일이 몇 개든 쿼리 1번 (idx_availability_caregiver_day 사용)
    List<CaregiverAvailability> findByCaregiverIdInAndDayOfWeekIn(Collection<Long> caregiverIds,
                                                                  Collection<DayOfWeek> daysOfWeek);
}
