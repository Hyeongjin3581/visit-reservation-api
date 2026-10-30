package com.hyeongjin.visit.caregiver.repository;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaregiverRepository extends JpaRepository<Caregiver, Long> {
}
