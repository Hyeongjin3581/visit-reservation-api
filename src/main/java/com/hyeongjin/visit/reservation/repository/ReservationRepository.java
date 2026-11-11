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
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    long countByCaregiverIdAndVisitDateAndStartTime(Long caregiverId, LocalDate visitDate, LocalTime startTime);

    // 후보들의 해당 날짜들 예약 (일정 겹침 필터·확정 재검증용)
    // 단건은 날짜 1개, 묶음은 묶음의 날짜 전체 → 날짜가 몇 개든 쿼리 1번 (idx_reservation_caregiver_date 사용)
    List<Reservation> findByCaregiverIdInAndVisitDateInAndStatusNot(
            Collection<Long> caregiverIds, Collection<LocalDate> visitDates, ReservationStatus excludedStatus);

    // 묶음의 살아 있는 예약 (묶음 취소용). unassign 때 지연 로딩이 없도록 요청을 함께 읽음
    @Query("""
            select r from Reservation r join fetch r.visitRequest v
            where v.group.id = :groupId and r.status <> :canceled
            """)
    List<Reservation> findActiveByGroupId(@Param("groupId") Long groupId,
                                          @Param("canceled") ReservationStatus canceled);

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
