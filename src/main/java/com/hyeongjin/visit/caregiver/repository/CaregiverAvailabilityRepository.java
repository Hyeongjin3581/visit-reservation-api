package com.hyeongjin.visit.caregiver.repository;

import java.util.List;

import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverAvailabilityRepository extends JpaRepository<CaregiverAvailability, Long> {

    List<CaregiverAvailability> findByCaregiverId(Long caregiverId);
}
