package com.hyeongjin.visit.recommendation;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

// 추천 API 통합 테스트: 필터로 빠져야 할 사람은 빠지고, 남은 사람은 점수 순으로 나오는지
@SpringBootTest
@Transactional
class RecommendationApiTest {

    // 서울시청 근처. 위도 0.009도 ≈ 1km
    private static final double BASE_LAT = 37.5665;
    private static final double BASE_LNG = 126.9780;

    @Autowired private WebApplicationContext context;
    @Autowired private Clock clock;
    @Autowired private CaregiverRepository caregiverRepository;
    @Autowired private CaregiverAvailabilityRepository availabilityRepository;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;
    @Autowired private ReservationRepository reservationRepository;

    private MockMvc mockMvc;
    private LocalDate visitDate;
    private Recipient recipient;
    private Recipient otherRecipient;
    private VisitRequest request;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        visitDate = LocalDate.now(clock).plusDays(1);
        recipient = recipientRepository.save(Recipient.create("박수급", Gender.FEMALE, BASE_LAT, BASE_LNG));
        otherRecipient = recipientRepository.save(Recipient.create("최수급", null, BASE_LAT, BASE_LNG));
        request = visitRequestRepository.save(VisitRequest.create(recipient, visitDate, time(10), time(12)));
    }

    @Test
    void 조건을_통과한_보호사만_점수_순으로_추천한다() throws Exception {
        // 통과 대상
        Caregiver regular = caregiver("단골", Gender.FEMALE, 5.0);   // 이 수급자를 4번 방문 → 연속 담당 만점
        Caregiver near = caregiver("가까움", Gender.FEMALE, 1.0);
        Caregiver far = caregiver("멂", Gender.FEMALE, 10.0);
        Caregiver canceledOnly = caregiver("취소만있음", Gender.FEMALE, 15.0);
        // 제외 대상
        Caregiver male = caregiver("남성", Gender.MALE, 0.5);
        Caregiver inactive = caregiver("휴직", Gender.FEMALE, 0.5);
        Caregiver busy = caregiver("일정겹침", Gender.FEMALE, 0.5);
        Caregiver afternoonOnly = caregiver("오후만", Gender.FEMALE, 0.5, 13, 18);

        for (int daysAgo = 1; daysAgo <= 4; daysAgo++) {
            completedVisit(regular, recipient, LocalDate.now(clock).minusDays(daysAgo));
        }
        inactive.deactivate();
        reservation(busy, visitDate, 11, 13);                       // 10~12 와 겹침
        reservation(canceledOnly, visitDate, 10, 12).cancel();      // 취소된 예약은 시간을 차지하지 않음

        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitRequestId").value(request.getId()))
                .andExpect(jsonPath("$.candidates[*].name").value(contains("단골", "가까움", "멂", "취소만있음")))
                .andExpect(jsonPath("$.candidates[0].continuityCount").value(4))
                .andExpect(jsonPath("$.candidates[0].recentWorkCount").value(4))
                .andExpect(jsonPath("$.candidates[0].scores.continuity").value(20.0))
                .andExpect(jsonPath("$.candidates[1].scores.workload").value(30.0));
    }

    @Test
    void size를_주지_않으면_상위_3명() throws Exception {
        for (int i = 1; i <= 5; i++) {
            caregiver("보호사" + i, Gender.FEMALE, i);
        }

        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].name").value(contains("보호사1", "보호사2", "보호사3")));
    }

    @Test
    void 조건에_맞는_보호사가_없으면_빈_목록() throws Exception {
        caregiver("남성", Gender.MALE, 1.0);

        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty());
    }

    @Test
    void 없는_방문_요청이면_404() throws Exception {
        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_FOUND"));
    }

    @Test
    void 이미_배정된_요청이면_409() throws Exception {
        request.assign();

        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_ASSIGNABLE"));
    }

    @Test
    void 추천_인원이_범위를_벗어나면_400() throws Exception {
        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_SIZE"));
        mockMvc.perform(get("/api/visit-requests/{id}/recommendations", request.getId()).param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private Caregiver caregiver(String name, Gender gender, double km) {
        return caregiver(name, gender, km, 9, 18);
    }

    // 수급자에게서 북쪽으로 km 만큼 떨어진 곳에 사는 보호사 + 방문 요일 근무 가능 시간
    private Caregiver caregiver(String name, Gender gender, double km, int workStart, int workEnd) {
        Caregiver caregiver = caregiverRepository.save(
                Caregiver.create(name, gender, BASE_LAT + km * 0.009, BASE_LNG));
        availabilityRepository.save(CaregiverAvailability.create(
                caregiver, visitDate.getDayOfWeek(), time(workStart), time(workEnd)));
        return caregiver;
    }

    private Reservation reservation(Caregiver caregiver, LocalDate date, int startHour, int endHour) {
        VisitRequest other = visitRequestRepository.save(
                VisitRequest.create(otherRecipient, date, time(startHour), time(endHour)));
        return reservationRepository.save(Reservation.confirm(caregiver, other));
    }

    private void completedVisit(Caregiver caregiver, Recipient target, LocalDate date) {
        VisitRequest past = visitRequestRepository.save(VisitRequest.create(target, date, time(10), time(12)));
        reservationRepository.save(Reservation.confirm(caregiver, past)).complete();
    }

    private LocalTime time(int hour) {
        return LocalTime.of(hour, 0);
    }
}
