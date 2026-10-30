package com.hyeongjin.visit.reservation.repository;

import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

// 2주차: 근무량·연속 담당 집계 쿼리, 3주차: 락 조회 메서드 추가 예정
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByCaregiverIdAndVisitDateAndStartTime(Long caregiverId, LocalDate visitDate, LocalTime startTime);
}
