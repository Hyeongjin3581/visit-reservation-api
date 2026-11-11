package com.hyeongjin.visit.recommendation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.common.geo.GeoDistance;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recommendation.domain.Candidate;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import jakarta.persistence.EntityManager;

// 온케어 AutoAssignService · CareWorkerRecommendationService 의 조회 방식을 이 프로젝트 엔티티로 옮긴 것
// 측정 비교용으로만 쓰고 운영 코드에는 넣지 않음
//   1) 보호사 전체 findAll() → 메모리에서 상태·성별 필터
//   2) 보호사마다 근무 가능 시간 조회 (canWork)
//   3) 보호사마다 그날 근무 기록 조회 (isBusy)
//   4) 근무량은 온케어도 GROUP BY 한 번 (이미 개선돼 있던 부분)
//   5) 연속 담당: 보호사마다 근무 기록 전체 조회 + 기록마다 방문 요청 지연 로딩 (getContinuityCountMap)
// 온케어의 탈퇴 여부 확인(activeUserCheck)도 보호사마다 쿼리가 나가지만, 이 프로젝트엔 회원 테이블이 없어 뺐음
class OncareStyleCandidateLoader {

    private final EntityManager em;

    OncareStyleCandidateLoader(EntityManager em) {
        this.em = em;
    }

    List<Candidate> load(Long visitRequestId, LocalDate today) {
        VisitRequest request = em.find(VisitRequest.class, visitRequestId);
        Recipient recipient = request.getRecipient();   // 지연 로딩

        List<Caregiver> passed = new ArrayList<>();
        for (Caregiver caregiver : em.createQuery("select c from Caregiver c", Caregiver.class).getResultList()) {
            if (!caregiver.isActive()) continue;
            if (!recipient.accepts(caregiver.getGender())) continue;
            if (!canWork(caregiver, request)) continue;
            if (isBusy(caregiver, request)) continue;
            passed.add(caregiver);
        }

        Map<Long, Long> workCounts = countRecentWork(passed, today);

        List<Candidate> candidates = new ArrayList<>();
        for (Caregiver caregiver : passed) {
            candidates.add(new Candidate(
                    caregiver,
                    GeoDistance.km(recipient.getLatitude(), recipient.getLongitude(),
                            caregiver.getLatitude(), caregiver.getLongitude()),
                    workCounts.getOrDefault(caregiver.getId(), 0L),
                    countContinuity(caregiver, recipient)));
        }
        return candidates;
    }

    private boolean canWork(Caregiver caregiver, VisitRequest request) {
        List<CaregiverAvailability> availabilities = em.createQuery(
                        "select a from CaregiverAvailability a where a.caregiver.id = :id and a.dayOfWeek = :day",
                        CaregiverAvailability.class)
                .setParameter("id", caregiver.getId())
                .setParameter("day", request.getVisitDate().getDayOfWeek())
                .getResultList();
        for (CaregiverAvailability availability : availabilities) {
            if (availability.covers(request.getVisitDate().getDayOfWeek(), request.getStartTime(), request.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBusy(Caregiver caregiver, VisitRequest request) {
        List<Reservation> sameDay = em.createQuery(
                        "select r from Reservation r where r.caregiver.id = :id and r.visitDate = :date", Reservation.class)
                .setParameter("id", caregiver.getId())
                .setParameter("date", request.getVisitDate())
                .getResultList();
        for (Reservation reservation : sameDay) {
            if (reservation.isActive() && reservation.overlaps(request.getStartTime(), request.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private Map<Long, Long> countRecentWork(List<Caregiver> caregivers, LocalDate today) {
        Map<Long, Long> result = new HashMap<>();
        if (caregivers.isEmpty()) {
            return result;
        }
        List<Object[]> rows = em.createQuery("""
                        select r.caregiver.id, count(r) from Reservation r
                        where r.caregiver in :caregivers and r.status = :status
                          and r.visitDate between :from and :to
                        group by r.caregiver.id
                        """, Object[].class)
                .setParameter("caregivers", caregivers)
                .setParameter("status", ReservationStatus.COMPLETED)
                .setParameter("from", today.minusDays(29))
                .setParameter("to", today)
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], (Long) row[1]);
        }
        return result;
    }

    private long countContinuity(Caregiver caregiver, Recipient recipient) {
        List<Reservation> all = em.createQuery("select r from Reservation r where r.caregiver.id = :id", Reservation.class)
                .setParameter("id", caregiver.getId())
                .getResultList();
        long count = 0;
        for (Reservation reservation : all) {
            if (reservation.getStatus() != ReservationStatus.COMPLETED) continue;
            // 여기서 방문 요청을 지연 로딩 → 완료 기록 수만큼 쿼리
            if (recipient.getId().equals(reservation.getVisitRequest().getRecipient().getId())) {
                count++;
            }
        }
        return count;
    }
}
