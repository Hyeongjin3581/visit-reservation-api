package com.hyeongjin.visit.caregiver.repository;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverAvailabilityRepository extends JpaRepository<CaregiverAvailability, Long> {

    List<CaregiverAvailability> findByCaregiverId(Long caregiverId);

    // 후보 전체의 해당 요일 근무 가능 시간을 한 번에 조회 (추천 필터용)
    List<CaregiverAvailability> findByCaregiverIdInAndDayOfWeek(Collection<Long> caregiverIds, DayOfWeek dayOfWeek);
}
