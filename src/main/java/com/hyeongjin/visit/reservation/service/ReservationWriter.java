package com.hyeongjin.visit.reservation.service;

import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.recommendation.filter.ScheduleConflictFilter;
import com.hyeongjin.visit.recommendation.filter.WorkingHoursFilter;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 예약 확정·취소를 트랜잭션 하나로 처리하는 곳
// 락 전략 4개가 모두 이 confirm 을 호출하고, 차이는 "보호사 행을 어떤 락 모드로 읽느냐"와 "트랜잭션 바깥에서 무엇을 하느냐"뿐
@Component
@RequiredArgsConstructor
public class ReservationWriter {

    private final EntityManager em;
    private final VisitRequestRepository visitRequestRepository;
    private final CaregiverAvailabilityRepository availabilityRepository;
    private final ReservationRepository reservationRepository;
    private final WorkingHoursFilter workingHoursFilter;
    private final ScheduleConflictFilter scheduleConflictFilter;

    // READ COMMITTED 를 명시한 이유 (CI 의 MySQL 테스트에서 발견):
    // MySQL 기본(REPEATABLE READ)은 트랜잭션의 첫 SELECT 시점에 읽기 스냅샷을 고정함.
    // 아래에서 방문 요청을 먼저 읽고 나서 보호사 락을 기다리면, 락을 얻은 뒤에도 스냅샷이 옛날 것이라
    // 앞사람이 방금 커밋한 예약을 겹침 검사에서 못 봄 → 비관적 락을 써도 겹친 예약이 들어감.
    // READ COMMITTED 는 SELECT 마다 최신 커밋을 보므로 락을 얻은 뒤의 조회가 앞사람의 예약을 봄.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation confirm(Long visitRequestId, Long caregiverId, LockModeType caregiverLock) {
        VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
        if (!request.isAssignable()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_NOT_ASSIGNABLE);
        }

        // NONE: 일반 조회 / PESSIMISTIC_WRITE: SELECT ... FOR UPDATE / OPTIMISTIC_FORCE_INCREMENT: 커밋 때 version + 1
        Caregiver caregiver = em.find(Caregiver.class, caregiverId, caregiverLock);
        if (caregiver == null) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_FOUND);
        }

        // 추천 때 통과했어도 확정 직전에 다시 확인 (온케어는 고른 날짜를 다시 검증하지 않았음)
        if (!caregiver.isActive() || !request.getRecipient().accepts(caregiver.getGender())) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
        }
        CaregiverSchedule schedule = loadSchedule(caregiver, request);
        if (!workingHoursFilter.accept(schedule, request)) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
        }
        // 락이 없으면(방법 A) 여기서 "겹치는 예약 없음"을 본 두 요청이 동시에 아래로 내려갈 수 있음
        if (!scheduleConflictFilter.accept(schedule, request)) {
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
        }

        Reservation reservation = Reservation.confirm(caregiver, request);
        request.assign();
        try {
            // 같은 시작 시간이면 여기서 유니크 제약 위반 → 마지막 안전장치
            return reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
        }
    }

    @Transactional
    public Reservation cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.isCanceled()) {
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CANCELED);
        }
        reservation.cancel();
        reservation.getVisitRequest().unassign();
        return reservation;
    }

    private CaregiverSchedule loadSchedule(Caregiver caregiver, VisitRequest request) {
        List<CaregiverAvailability> availabilities = availabilityRepository.findByCaregiverIdInAndDayOfWeek(
                List.of(caregiver.getId()), request.getVisitDate().getDayOfWeek());
        List<Reservation> sameDay = reservationRepository.findByCaregiverIdInAndVisitDateAndStatusNot(
                List.of(caregiver.getId()), request.getVisitDate(), ReservationStatus.CANCELED);
        return new CaregiverSchedule(caregiver, availabilities, sameDay);
    }
}
