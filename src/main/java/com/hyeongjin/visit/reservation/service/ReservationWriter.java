package com.hyeongjin.visit.reservation.service;

import static java.util.stream.Collectors.toSet;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.recommendation.filter.ScheduleConflictFilter;
import com.hyeongjin.visit.recommendation.filter.WorkingHoursFilter;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 예약 확정·취소를 트랜잭션 하나로 처리하는 곳
// 락 전략이 모두 이 confirm / confirmGroup 을 호출하고, 차이는 "트랜잭션 안에서 무엇을 잠그느냐(ConfirmLock)"와 "트랜잭션 바깥에서 무엇을 하느냐"뿐
@Component
@RequiredArgsConstructor
public class ReservationWriter {

    private final EntityManager em;
    private final VisitRequestRepository visitRequestRepository;
    private final CaregiverAvailabilityRepository availabilityRepository;
    private final ReservationRepository reservationRepository;
    private final CaregiverDayLockRepository dayLockRepository;
    private final WorkingHoursFilter workingHoursFilter;
    private final ScheduleConflictFilter scheduleConflictFilter;

    // READ COMMITTED 를 명시한 이유 (CI 의 MySQL 테스트에서 발견):
    // MySQL 기본(REPEATABLE READ)은 트랜잭션의 첫 SELECT 시점에 읽기 스냅샷을 고정함.
    // 아래에서 방문 요청을 먼저 읽고 나서 보호사 락을 기다리면, 락을 얻은 뒤에도 스냅샷이 옛날 것이라
    // 앞사람이 방금 커밋한 예약을 겹침 검사에서 못 봄 → 비관적 락을 써도 겹친 예약이 들어감.
    // READ COMMITTED 는 SELECT 마다 최신 커밋을 보므로 락을 얻은 뒤의 조회가 앞사람의 예약을 봄.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation confirm(Long visitRequestId, Long caregiverId, ConfirmLock lock) {
        VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
        return confirmAll(List.of(request), caregiverId, lock).get(0);
    }

    // 묶음 일괄 확정: 트랜잭션 하나 → 한 날짜라도 실패하면 전부 롤백 (부분 배정이 남지 않음)
    // READ COMMITTED 를 단건과 똑같이 명시 (빠뜨리면 MySQL 에서 위의 스냅샷 문제가 묶음 경로로 다시 생김)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Reservation> confirmGroup(Long groupId, Long caregiverId, ConfirmLock lock) {
        List<VisitRequest> requests = visitRequestRepository.findAllWithRecipientByGroupId(groupId);
        if (requests.isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
        }
        return confirmAll(requests, caregiverId, lock);
    }

    // 단건·묶음 공통 확정 로직
    // @Transactional 을 붙이지 않음: 같은 클래스 안에서 부르면 프록시를 거치지 않아 어차피 적용되지 않고,
    // 호출한 confirm / confirmGroup 의 READ COMMITTED 트랜잭션 안에서 실행됨
    private List<Reservation> confirmAll(List<VisitRequest> requests, Long caregiverId, ConfirmLock lock) {
        if (!requests.stream().allMatch(VisitRequest::isAssignable)) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_NOT_ASSIGNABLE);
        }

        // 보호사 행: CAREGIVER_ROW 는 SELECT ... FOR UPDATE, CAREGIVER_VERSION 은 커밋 때 version + 1, 나머지는 일반 조회
        // 날짜가 몇 개든 보호사 행 락은 한 번
        Caregiver caregiver = em.find(Caregiver.class, caregiverId, switch (lock) {
            case CAREGIVER_ROW -> LockModeType.PESSIMISTIC_WRITE;
            case CAREGIVER_VERSION -> LockModeType.OPTIMISTIC_FORCE_INCREMENT;
            case NONE, CAREGIVER_DAY -> LockModeType.NONE;
        });
        if (caregiver == null) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_FOUND);
        }
        if (lock == ConfirmLock.CAREGIVER_DAY) {
            lockDays(caregiverId, requests);
        }

        // 추천 때 통과했어도 확정 직전에 다시 확인 (온케어는 고른 날짜를 다시 검증하지 않았음)
        // 묶음의 하위 요청은 모두 같은 수급자
        // CAREGIVER_DAY 는 보호사 행을 잠그지 않으므로 이 상태 확인은 확정 중 상태 변경과 직렬화되지 않음
        // (지금은 보호사 상태를 바꾸는 API 가 없음. 생기면 다시 볼 것)
        Recipient recipient = requests.get(0).getRecipient();
        if (!caregiver.isActive() || !recipient.accepts(caregiver.getGender())) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
        }
        CaregiverSchedule schedule = loadSchedule(caregiver, requests);
        for (VisitRequest request : requests) {
            if (!workingHoursFilter.accept(schedule, request)) {
                throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
            }
            // 락이 없으면(방법 A) 여기서 "겹치는 예약 없음"을 본 두 요청이 동시에 아래로 내려갈 수 있음
            if (!scheduleConflictFilter.accept(schedule, request)) {
                throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
            }
        }

        List<Reservation> reservations = requests.stream()
                .map(request -> Reservation.confirm(caregiver, request))
                .toList();
        requests.forEach(VisitRequest::assign);
        try {
            // 같은 시작 시간이면 여기서 유니크 제약 위반 → 마지막 안전장치
            return reservationRepository.saveAllAndFlush(reservations);
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

    // 묶음 일괄 취소: 묶음의 살아 있는 예약을 모두 취소하고 하위 요청을 다시 배정 대기로
    // 단건 취소와 같이 락 없음. 같은 예약을 동시에 바꾸면 Reservation.@Version 이 막음
    @Transactional
    public List<Reservation> cancelGroup(Long groupId) {
        List<Reservation> active = reservationRepository.findActiveByGroupId(groupId, ReservationStatus.CANCELED);
        if (active.isEmpty()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        active.forEach(reservation -> {
            reservation.cancel();
            reservation.getVisitRequest().unassign();
        });
        return active;
    }

    // (보호사, 날짜) 잠금 행을 날짜 오름차순으로 한 행씩 잠금
    // 모든 트랜잭션이 같은 순서로 잡으므로, 날짜 조합이 다른 묶음끼리 서로 기다리는 데드락이 생기지 않음
    private void lockDays(Long caregiverId, List<VisitRequest> requests) {
        requests.stream().map(VisitRequest::getVisitDate).sorted().distinct().forEach(date ->
                dayLockRepository.findForUpdate(caregiverId, date).orElseThrow(() ->
                        // 잠글 행이 없으면 락 없이 진행하게 되므로 조용히 넘어가지 않음
                        new IllegalStateException("잠금 행이 없습니다. CaregiverDayLocks.prepare 를 먼저 호출해야 합니다")));
    }

    // 요일·날짜 목록으로 한 번씩 조회 (단건이면 목록 크기 1)
    private CaregiverSchedule loadSchedule(Caregiver caregiver, List<VisitRequest> requests) {
        Set<DayOfWeek> days = requests.stream().map(request -> request.getVisitDate().getDayOfWeek()).collect(toSet());
        Set<LocalDate> dates = requests.stream().map(VisitRequest::getVisitDate).collect(toSet());
        List<CaregiverAvailability> availabilities = availabilityRepository.findByCaregiverIdInAndDayOfWeekIn(
                List.of(caregiver.getId()), days);
        List<Reservation> existing = reservationRepository.findByCaregiverIdInAndVisitDateInAndStatusNot(
                List.of(caregiver.getId()), dates, ReservationStatus.CANCELED);
        return new CaregiverSchedule(caregiver, availabilities, existing);
    }
}
