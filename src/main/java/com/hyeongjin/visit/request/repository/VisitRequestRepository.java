package com.hyeongjin.visit.request.repository;

import com.hyeongjin.visit.request.domain.VisitRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRequestRepository extends JpaRepository<VisitRequest, Long> {
}
