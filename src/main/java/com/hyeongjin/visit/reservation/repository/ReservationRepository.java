package com.hyeongjin.visit.reservation.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 추천에서 쓰는 조회는 모두 "후보 전체를 한 번에" 가져옴 (보호사마다 따로 조회하면 N+1)
// 3주차: 락 조회 메서드 추가 예정
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByCaregiverIdAndVisitDateAndStartTime(Long caregiverId, LocalDate visitDate, LocalTime startTime);

    // 후보들의 그날 예약 (일정 겹침 필터용)
    List<Reservation> findByCaregiverIdInAndVisitDateAndStatusNot(
            Collection<Long> caregiverIds, LocalDate visitDate, ReservationStatus excludedStatus);

    // 후보별 기간 내 완료 방문 횟수 (근무량 점수용)
    @Query("""
            select new com.hyeongjin.visit.reservation.repository.CaregiverCount(r.caregiver.id, count(r))
            from Reservation r
            where r.caregiver.id in :caregiverIds
              and r.status = :status
              and r.visitDate between :from and :to
            group by r.caregiver.id
            """)
    List<CaregiverCount> countByCaregiverAndPeriod(@Param("caregiverIds") Collection<Long> caregiverIds,
                                                   @Param("status") ReservationStatus status,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);

    // 후보별 이 수급자 완료 방문 횟수 (연속 담당 점수용)
    @Query("""
            select new com.hyeongjin.visit.reservation.repository.CaregiverCount(r.caregiver.id, count(r))
            from Reservation r
            where r.caregiver.id in :caregiverIds
              and r.status = :status
              and r.visitRequest.recipient.id = :recipientId
            group by r.caregiver.id
            """)
    List<CaregiverCount> countByCaregiverAndRecipient(@Param("caregiverIds") Collection<Long> caregiverIds,
                                                      @Param("status") ReservationStatus status,
                                                      @Param("recipientId") Long recipientId);
}
