package com.hyeongjin.visit.recommendation;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
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

// 묶음 추천: 모든 날짜에 갈 수 있는 보호사만 남는지
@SpringBootTest
@Transactional
class GroupRecommendationApiTest {

    private static final double BASE_LAT = 37.5665;
    private static final double BASE_LNG = 126.9780;

    @Autowired private WebApplicationContext context;
    @Autowired private Clock clock;
    @Autowired private CaregiverRepository caregiverRepository;
    @Autowired private CaregiverAvailabilityRepository availabilityRepository;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestGroupRepository groupRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;
    @Autowired private ReservationRepository reservationRepository;

    private MockMvc mockMvc;
    private LocalDate day1;
    private LocalDate day2;
    private Recipient recipient;
    private Recipient otherRecipient;
    private VisitRequestGroup group;
    private List<VisitRequest> requests;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        day1 = LocalDate.now(clock).plusDays(1);
        day2 = day1.plusDays(1);   // 요일이 다름
        recipient = recipientRepository.save(Recipient.create("박수급", Gender.FEMALE, BASE_LAT, BASE_LNG));
        otherRecipient = recipientRepository.save(Recipient.create("최수급", null, BASE_LAT, BASE_LNG));
        group = groupRepository.save(VisitRequestGroup.create(recipient, time(10), time(12)));
        requests = visitRequestRepository.saveAll(List.of(
                VisitRequest.createInGroup(group, day1), VisitRequest.createInGroup(group, day2)));
    }

    @Test
    void 모든_날짜에_가능한_보호사만_점수_순으로_추천한다() throws Exception {
        caregiver("가까움", 1.0, day1, day2);
        caregiver("멂", 5.0, day1, day2);
        caregiver("하루만근무", 0.5, day1);                          // 둘째 날 요일에 근무 시간 없음
        Caregiver busy = caregiver("둘째날겹침", 0.5, day1, day2);
        reservation(busy, day2, 11, 13);                             // 둘째 날 10~12 와 겹침

        mockMvc.perform(get("/api/visit-request-groups/{id}/recommendations", group.getId()).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(group.getId()))
                .andExpect(jsonPath("$.visitDates").value(contains(day1.toString(), day2.toString())))
                .andExpect(jsonPath("$.candidates[*].name").value(contains("가까움", "멂")));
    }

    @Test
    void 하위_요청_하나라도_배정됐으면_409() throws Exception {
        requests.get(1).assign();

        mockMvc.perform(get("/api/visit-request-groups/{id}/recommendations", group.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_ASSIGNABLE"));
    }

    @Test
    void 없는_묶음이면_404() throws Exception {
        mockMvc.perform(get("/api/visit-request-groups/{id}/recommendations", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_GROUP_NOT_FOUND"));
    }

    // 수급자에게서 북쪽으로 km 만큼 떨어진 보호사 + 주어진 날짜들의 요일에 9~18시 근무
    private Caregiver caregiver(String name, double km, LocalDate... workDates) {
        Caregiver caregiver = caregiverRepository.save(
                Caregiver.create(name, Gender.FEMALE, BASE_LAT + km * 0.009, BASE_LNG));
        for (LocalDate date : workDates) {
            availabilityRepository.save(CaregiverAvailability.create(
                    caregiver, date.getDayOfWeek(), time(9), time(18)));
        }
        return caregiver;
    }

    private void reservation(Caregiver caregiver, LocalDate date, int startHour, int endHour) {
        VisitRequest other = visitRequestRepository.save(
                VisitRequest.create(otherRecipient, date, time(startHour), time(endHour)));
        reservationRepository.save(Reservation.confirm(caregiver, other));
    }

    private LocalTime time(int hour) {
        return LocalTime.of(hour, 0);
    }
}
