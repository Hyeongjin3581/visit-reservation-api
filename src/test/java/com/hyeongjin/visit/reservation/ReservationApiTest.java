package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.hyeongjin.visit.request.domain.VisitRequestStatus;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

// 예약 확정·취소 API (단일 요청 기준 규칙 확인, 동시성은 ReservationConcurrencyTest)
@SpringBootTest
@Transactional
class ReservationApiTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Clock clock;
    @Autowired private CaregiverRepository caregiverRepository;
    @Autowired private CaregiverAvailabilityRepository availabilityRepository;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;

    private MockMvc mockMvc;
    private LocalDate visitDate;
    private Recipient recipient;
    private Caregiver caregiver;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        visitDate = LocalDate.now(clock).plusDays(1);
        recipient = recipientRepository.save(Recipient.create("박수급", Gender.FEMALE, 37.5665, 126.9780));
        caregiver = caregiverRepository.save(Caregiver.create("김보호", Gender.FEMALE, 37.57, 126.98));
        availabilityRepository.save(CaregiverAvailability.create(
                caregiver, visitDate.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(18, 0)));
    }

    @Test
    void 예약을_확정하면_201과_요청이_ASSIGNED로_바뀐다() throws Exception {
        VisitRequest request = request(10, 12);

        confirm(request, caregiver)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.caregiverId").value(caregiver.getId()));

        assertThat(request.getStatus()).isEqualTo(VisitRequestStatus.ASSIGNED);
    }

    @Test
    void 시작_시간이_달라도_겹치면_409() throws Exception {
        confirm(request(10, 12), caregiver).andExpect(status().isCreated());

        confirm(request(11, 13), caregiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
    }

    @Test
    void 이미_배정된_요청을_다시_확정하면_409() throws Exception {
        VisitRequest request = request(10, 12);
        confirm(request, caregiver).andExpect(status().isCreated());

        Caregiver another = caregiverRepository.save(Caregiver.create("이보호", Gender.FEMALE, 37.57, 126.98));
        confirm(request, another)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_ASSIGNABLE"));
    }

    @Test
    void 근무_가능_시간_밖이면_409() throws Exception {
        confirm(request(17, 19), caregiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_AVAILABLE"));
    }

    @Test
    void 선호_성별이_다르면_409() throws Exception {
        Caregiver male = caregiverRepository.save(Caregiver.create("박보호", Gender.MALE, 37.57, 126.98));
        availabilityRepository.save(CaregiverAvailability.create(
                male, visitDate.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(18, 0)));

        confirm(request(10, 12), male)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_AVAILABLE"));
    }

    @Test
    void 취소하면_같은_슬롯에_다시_예약할_수_있다() throws Exception {
        VisitRequest first = request(10, 12);
        String json = confirm(first, caregiver).andReturn().getResponse().getContentAsString();
        long reservationId = ((Number) JsonPath.read(json, "$.id")).longValue();

        mockMvc.perform(delete("/api/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(first.getStatus()).isEqualTo(VisitRequestStatus.REQUESTED);

        // 1주차의 한계였던 부분: 취소된 예약이 유니크 제약 슬롯을 계속 차지하던 문제
        confirm(request(10, 12), caregiver).andExpect(status().isCreated());
    }

    @Test
    void 이미_취소된_예약을_다시_취소하면_409() throws Exception {
        String json = confirm(request(10, 12), caregiver).andReturn().getResponse().getContentAsString();
        long reservationId = ((Number) JsonPath.read(json, "$.id")).longValue();
        mockMvc.perform(delete("/api/reservations/{id}", reservationId)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/reservations/{id}", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_CANCELED"));
    }

    @Test
    void 없는_보호사나_요청이면_404() throws Exception {
        mockMvc.perform(post("/api/visit-requests/{id}/reservations", request(10, 12).getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caregiverId\":9999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_FOUND"));
        mockMvc.perform(post("/api/visit-requests/{id}/reservations", 9999)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caregiverId\":%d}".formatted(caregiver.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_FOUND"));
    }

    private ResultActions confirm(VisitRequest request, Caregiver target) throws Exception {
        return mockMvc.perform(post("/api/visit-requests/{id}/reservations", request.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caregiverId\":%d}".formatted(target.getId())));
    }

    private VisitRequest request(int startHour, int endHour) {
        return visitRequestRepository.save(
                VisitRequest.create(recipient, visitDate, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
    }
}
