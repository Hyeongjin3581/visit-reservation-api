package com.hyeongjin.visit.recommendation.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.domain.CaregiverStatus;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import com.hyeongjin.visit.common.geo.GeoDistance;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recommendation.domain.Candidate;
import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.recommendation.dto.RecommendationResponse;
import com.hyeongjin.visit.recommendation.filter.CandidateFilter;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import com.hyeongjin.visit.reservation.repository.CaregiverCount;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 추천 흐름: 요청 조회 → 1차 후보(DB) → 필터 → 점수 → 상위 N명
// 쿼리는 후보 수와 상관없이 최대 6번 (요청 1 + 보호사 1 + 근무 가능 시간 1 + 그날 예약 1 + 근무량 집계 1 + 연속 담당 집계 1)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    static final int MAX_SIZE = 10;
    static final int WORKLOAD_PERIOD_DAYS = 30;

    private final VisitRequestRepository visitRequestRepository;
    private final CaregiverRepository caregiverRepository;
    private final CaregiverAvailabilityRepository availabilityRepository;
    private final ReservationRepository reservationRepository;
    private final List<CandidateFilter> filters;
    private final RecommendationRanker ranker;
    private final Clock clock;

    public RecommendationResponse recommend(Long visitRequestId, int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_SIZE);
        }
        VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
        if (!request.isAssignable()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_NOT_ASSIGNABLE);
        }

        List<Caregiver> passed = filterCandidates(request);
        List<Candidate> candidates = toCandidates(request.getRecipient(), passed);
        return RecommendationResponse.of(request.getId(), ranker.rank(candidates, size));
    }

    // 필수 조건 통과자
    private List<Caregiver> filterCandidates(VisitRequest request) {
        List<Caregiver> caregivers = findActiveCaregivers(request.getRecipient());
        if (caregivers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = caregivers.stream().map(Caregiver::getId).toList();

        // 후보 전체의 근무 가능 시간·그날 예약을 한 번씩만 조회한 뒤 보호사별로 나눔
        Map<Long, List<CaregiverAvailability>> availabilities = availabilityRepository
                .findByCaregiverIdInAndDayOfWeek(ids, request.getVisitDate().getDayOfWeek()).stream()
                .collect(groupingBy(availability -> availability.getCaregiver().getId()));
        Map<Long, List<Reservation>> reservations = reservationRepository
                .findByCaregiverIdInAndVisitDateAndStatusNot(ids, request.getVisitDate(), ReservationStatus.CANCELED).stream()
                .collect(groupingBy(reservation -> reservation.getCaregiver().getId()));

        return caregivers.stream()
                .filter(caregiver -> {
                    CaregiverSchedule schedule = new CaregiverSchedule(
                            caregiver,
                            availabilities.getOrDefault(caregiver.getId(), List.of()),
                            reservations.getOrDefault(caregiver.getId(), List.of()));
                    return filters.stream().allMatch(filter -> filter.accept(schedule, request));
                })
                .toList();
    }

    // 근무 상태·선호 성별은 컬럼 비교라 DB 에서 바로 거름
    private List<Caregiver> findActiveCaregivers(Recipient recipient) {
        if (recipient.getPreferredGender() == null) {
            return caregiverRepository.findByStatus(CaregiverStatus.ACTIVE);
        }
        return caregiverRepository.findByStatusAndGender(CaregiverStatus.ACTIVE, recipient.getPreferredGender());
    }

    // 점수 계산에 필요한 값(거리, 근무량, 연속 담당)을 붙여 후보로 만듦
    private List<Candidate> toCandidates(Recipient recipient, List<Caregiver> caregivers) {
        if (caregivers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = caregivers.stream().map(Caregiver::getId).toList();

        // 오늘 포함 최근 30일
        LocalDate today = LocalDate.now(clock);
        Map<Long, Long> workCounts = toCountMap(reservationRepository.countByCaregiverAndPeriod(
                ids, ReservationStatus.COMPLETED, today.minusDays(WORKLOAD_PERIOD_DAYS - 1), today));
        Map<Long, Long> continuityCounts = toCountMap(reservationRepository.countByCaregiverAndRecipient(
                ids, ReservationStatus.COMPLETED, recipient.getId()));

        return caregivers.stream()
                .map(caregiver -> new Candidate(
                        caregiver,
                        GeoDistance.km(recipient.getLatitude(), recipient.getLongitude(),
                                caregiver.getLatitude(), caregiver.getLongitude()),
                        workCounts.getOrDefault(caregiver.getId(), 0L),
                        continuityCounts.getOrDefault(caregiver.getId(), 0L)))
                .toList();
    }

    private Map<Long, Long> toCountMap(List<CaregiverCount> counts) {
        return counts.stream().collect(toMap(CaregiverCount::caregiverId, CaregiverCount::count));
    }
}
