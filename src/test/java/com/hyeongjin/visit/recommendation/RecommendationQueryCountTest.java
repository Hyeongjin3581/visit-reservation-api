package com.hyeongjin.visit.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recommendation.domain.ScoredCandidate;
import com.hyeongjin.visit.recommendation.dto.RecommendationResponse;
import com.hyeongjin.visit.recommendation.service.RecommendationRanker;
import com.hyeongjin.visit.recommendation.service.RecommendationService;
import com.hyeongjin.visit.recommendation.dto.GroupRecommendationResponse;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import com.hyeongjin.visit.reservation.domain.Reservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 추천 한 번에 나가는 SQL 수 측정: 온케어 방식 vs 집계 쿼리 방식
// 결과는 테스트 출력(build/test-results)에 [측정] 줄로 남기고 README 측정 표에 옮김
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class RecommendationQueryCountTest {

    @Autowired private RecommendationService recommendationService;
    @Autowired private RecommendationRanker ranker;
    @Autowired private EntityManagerFactory emf;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager em;

    @ParameterizedTest(name = "후보 {0}명")
    @ValueSource(ints = {10, 100})
    void 집계_쿼리_방식은_후보_수와_상관없이_쿼리_수가_일정하다(int caregiverCount) {
        LocalDate today = LocalDate.now(clock);
        Long visitRequestId = seed(caregiverCount, today);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        // 개선 전: 온케어 방식
        em.clear();
        stats.clear();
        List<ScoredCandidate> before = ranker.rank(new OncareStyleCandidateLoader(em).load(visitRequestId, today), 10);
        long beforeQueries = stats.getPrepareStatementCount();

        // 개선 후: RecommendationService
        em.clear();
        stats.clear();
        RecommendationResponse after = recommendationService.recommend(visitRequestId, 10);
        long afterQueries = stats.getPrepareStatementCount();

        System.out.printf("[측정] 후보 %d명: 온케어 방식 %d회 → 집계 쿼리 방식 %d회%n",
                caregiverCount, beforeQueries, afterQueries);

        // 조회 방식만 바뀌었으니 추천 결과는 같아야 함
        assertThat(after.candidates()).extracting(RecommendationResponse.CandidateResponse::caregiverId)
                .containsExactlyElementsOf(before.stream().map(s -> s.candidate().caregiver().getId()).toList());
        assertThat(afterQueries).isLessThanOrEqualTo(6);
        assertThat(beforeQueries).isGreaterThan(caregiverCount * 3L);
    }

    // 묶음 추천: 날짜가 늘어도 요일·날짜를 IN 으로 한 번에 읽으므로 쿼리 수가 같아야 함 (온케어는 날짜 수 × 2N)
    @ParameterizedTest(name = "날짜 {0}개")
    @ValueSource(ints = {1, 7, 14})
    void 묶음_추천은_날짜_수와_상관없이_쿼리_수가_일정하다(int dateCount) {
        LocalDate today = LocalDate.now(clock);
        int caregiverCount = 30;
        Recipient target = Recipient.create("박수급", null, 37.5665, 126.9780);
        em.persist(target);
        VisitRequestGroup group = VisitRequestGroup.create(target, LocalTime.of(10, 0), LocalTime.of(12, 0));
        em.persist(group);
        for (int d = 1; d <= dateCount; d++) {
            em.persist(VisitRequest.createInGroup(group, today.plusDays(d)));
        }
        for (int i = 0; i < caregiverCount; i++) {
            Caregiver caregiver = Caregiver.create("보호사" + i, Gender.FEMALE, 37.5665 + i * 0.002, 126.9780);
            em.persist(caregiver);
            for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
                em.persist(CaregiverAvailability.create(caregiver, day, LocalTime.of(9, 0), LocalTime.of(18, 0)));
            }
            completedVisit(caregiver, target, today.minusDays(i % 25 + 1));
        }
        em.flush();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();

        em.clear();
        stats.clear();
        GroupRecommendationResponse response = recommendationService.recommendGroup(group.getId(), 10);
        long queries = stats.getPrepareStatementCount();

        System.out.printf("[측정] 묶음 날짜 %d개, 후보 %d명: %d회%n", dateCount, caregiverCount, queries);
        assertThat(response.visitDates()).hasSize(dateCount);
        assertThat(response.candidates()).hasSize(10);
        assertThat(queries).isLessThanOrEqualTo(6);
    }

    // 보호사 n명이 모두 필터를 통과하도록 만들고, 보호사마다 완료 방문 2건씩 넣음
    private Long seed(int n, LocalDate today) {
        Recipient target = Recipient.create("박수급", null, 37.5665, 126.9780);
        Recipient other = Recipient.create("최수급", null, 37.5665, 126.9780);
        em.persist(target);
        em.persist(other);

        LocalDate visitDate = today.plusDays(1);
        VisitRequest request = VisitRequest.create(target, visitDate, LocalTime.of(10, 0), LocalTime.of(12, 0));
        em.persist(request);

        for (int i = 0; i < n; i++) {
            Caregiver caregiver = Caregiver.create("보호사" + i, i % 2 == 0 ? Gender.FEMALE : Gender.MALE,
                    37.5665 + (i % 50) * 0.002, 126.9780 + (i / 50) * 0.002);
            em.persist(caregiver);
            em.persist(CaregiverAvailability.create(caregiver, visitDate.getDayOfWeek(),
                    LocalTime.of(9, 0), LocalTime.of(18, 0)));

            // 최근 방문 1건(짝수 번은 이 수급자) + 40일 전 방문 1건(근무량 집계 기간 밖)
            completedVisit(caregiver, i % 2 == 0 ? target : other, today.minusDays(i % 25 + 1));
            completedVisit(caregiver, target, today.minusDays(40));
        }
        em.flush();
        return request.getId();
    }

    private void completedVisit(Caregiver caregiver, Recipient recipient, LocalDate date) {
        VisitRequest past = VisitRequest.create(recipient, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        em.persist(past);
        Reservation reservation = Reservation.confirm(caregiver, past);
        reservation.complete();
        em.persist(reservation);
    }
}
