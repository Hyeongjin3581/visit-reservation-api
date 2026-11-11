package com.hyeongjin.visit.caregiver.repository;

import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverStatus;
import com.hyeongjin.visit.common.entity.Gender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverRepository extends JpaRepository<Caregiver, Long> {

    // 추천 1차 후보: 근무 상태·성별처럼 컬럼 하나로 판단되는 조건은 DB 에서 거름
    // (온케어는 findAll() 로 전체를 가져와 메모리에서 거름)
    List<Caregiver> findByStatus(CaregiverStatus status);

    List<Caregiver> findByStatusAndGender(CaregiverStatus status, Gender gender);
}
