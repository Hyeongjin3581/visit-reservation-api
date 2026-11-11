package com.hyeongjin.visit.recommendation.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.hyeongjin.visit.recommendation.domain.ScoredCandidate;
import com.hyeongjin.visit.recommendation.dto.GroupRecommendationResponse;
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

// 추천 흐름 (단건·묶음 공통): 요청 조회 → 1차 후보(DB) → 필터 → 점수 → 상위 N명
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
        validateSize(size);
        VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
        requireAssignable(List.of(request));
        // 단건은 크기 1인 목록으로 묶음과 같은 경로를 탐
        return RecommendationResponse.of(request.getId(), rank(List.of(request), request.getRecipient(), size));
    }

    // 묶음: 모든 날짜에 갈 수 있는 보호사만 추천. 쿼리는 날짜 수와 상관없이 6번 (요청 조회가 묶음 하위 요청 조회로 바뀔 뿐)
    public GroupRecommendationResponse recommendGroup(Long groupId, int size) {
        validateSize(size);
        List<VisitRequest> requests = visitRequestRepository.findAllWithRecipientByGroupId(groupId);
        if (requests.isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
        }
        // 하나라도 이미 배정·취소됐으면 "묶음 전체를 한 보호사에게"가 성립하지 않음
        requireAssignable(requests);
        List<LocalDate> dates = requests.stream().map(VisitRequest::getVisitDate).toList();
        return GroupRecommendationResponse.of(groupId, dates, rank(requests, requests.get(0).getRecipient(), size));
    }

    private List<ScoredCandidate> rank(List<VisitRequest> requests, Recipient recipient, int size) {
        List<Caregiver> passed = filterCandidates(requests, recipient);
        return ranker.rank(toCandidates(recipient, passed), size);
    }

    private void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_SIZE);
        }
    }

    private void requireAssignable(List<VisitRequest> requests) {
        if (!requests.stream().allMatch(VisitRequest::isAssignable)) {
            throw new BusinessException(ErrorCode.VISIT_REQUEST_NOT_ASSIGNABLE);
        }
    }

    // 필수 조건 통과자: 모든 요청(날짜)에 대해 필터를 통과해야 함
    private List<Caregiver> filterCandidates(List<VisitRequest> requests, Recipient recipient) {
        List<Caregiver> caregivers = findActiveCaregivers(recipient);
        if (caregivers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = caregivers.stream().map(Caregiver::getId).toList();
        Set<DayOfWeek> days = requests.stream().map(request -> request.getVisitDate().getDayOfWeek()).collect(toSet());
        Set<LocalDate> dates = requests.stream().map(VisitRequest::getVisitDate).collect(toSet());

        // 후보 전체의 근무 가능 시간·해당 날짜 예약을 한 번씩만 조회한 뒤 보호사별로 나눔 (요일·날짜가 몇 개든 IN 조회 1번씩)
        Map<Long, List<CaregiverAvailability>> availabilities = availabilityRepository
                .findByCaregiverIdInAndDayOfWeekIn(ids, days).stream()
                .collect(groupingBy(availability -> availability.getCaregiver().getId()));
        Map<Long, List<Reservation>> reservations = reservationRepository
                .findByCaregiverIdInAndVisitDateInAndStatusNot(ids, dates, ReservationStatus.CANCELED).stream()
                .collect(groupingBy(reservation -> reservation.getCaregiver().getId()));

        return caregivers.stream()
                .filter(caregiver -> {
                    CaregiverSchedule schedule = new CaregiverSchedule(
                            caregiver,
                            availabilities.getOrDefault(caregiver.getId(), List.of()),
                            reservations.getOrDefault(caregiver.getId(), List.of()));
                    // 필터들은 요청의 요일·날짜로 먼저 거르므로, 여러 날짜 데이터가 섞인 schedule 을 그대로 넘겨도 됨
                    return requests.stream().allMatch(request ->
                            filters.stream().allMatch(filter -> filter.accept(schedule, request)));
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
