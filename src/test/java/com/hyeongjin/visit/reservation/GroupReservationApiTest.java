package com.hyeongjin.visit.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
import com.hyeongjin.visit.request.domain.VisitRequestStatus;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.domain.ReservationStatus;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
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

// 묶음 일괄 확정·취소 API (규칙 확인. 실제 커밋·롤백과 동시성은 GroupReservationConcurrencyTest)
@SpringBootTest
@Transactional
class GroupReservationApiTest {

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
    private Recipient recipient;
    private Recipient otherRecipient;
    private Caregiver caregiver;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        day1 = LocalDate.now(clock).plusDays(1);
        recipient = recipientRepository.save(Recipient.create("박수급", Gender.FEMALE, 37.5665, 126.9780));
        otherRecipient = recipientRepository.save(Recipient.create("최수급", null, 37.5665, 126.9780));
        // 첫째·둘째 날 요일에만 근무 (셋째 날 요일은 근무 시간 없음)
        caregiver = caregiver("김보호", day1, day1.plusDays(1));
    }

    @Test
    void 일괄_확정하면_모든_날짜가_같은_보호사로_확정된다() throws Exception {
        List<VisitRequest> requests = group(day1, day1.plusDays(1));

        confirm(requests, caregiver)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caregiverId").value(caregiver.getId()))
                .andExpect(jsonPath("$.reservations.length()").value(2))
                .andExpect(jsonPath("$.reservations[0].visitDate").value(day1.toString()))
                .andExpect(jsonPath("$.reservations[1].status").value("CONFIRMED"));

        assertThat(requests).extracting(VisitRequest::getStatus).containsOnly(VisitRequestStatus.ASSIGNED);
    }

    @Test
    void 한_날짜라도_겹치면_409이고_어느_날짜도_배정되지_않는다() throws Exception {
        List<VisitRequest> requests = group(day1, day1.plusDays(1));
        reservation(caregiver, day1.plusDays(1), 11, 13);   // 둘째 날 10~12 와 겹침

        confirm(requests, caregiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));

        assertThat(requests).extracting(VisitRequest::getStatus).containsOnly(VisitRequestStatus.REQUESTED);
        assertThat(reservationRepository.findAll())
                .noneMatch(r -> r.getVisitRequest().getGroup() != null);
    }

    @Test
    void 근무_시간_밖인_날짜가_하나라도_있으면_409() throws Exception {
        List<VisitRequest> requests = group(day1, day1.plusDays(1), day1.plusDays(2));

        confirm(requests, caregiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_AVAILABLE"));
        assertThat(requests).extracting(VisitRequest::getStatus).containsOnly(VisitRequestStatus.REQUESTED);
    }

    @Test
    void 하위_요청_하나가_이미_배정됐으면_409() throws Exception {
        List<VisitRequest> requests = group(day1, day1.plusDays(1));
        // 단건 API 로 하루만 먼저 배정
        mockMvc.perform(post("/api/visit-requests/{id}/reservations", requests.get(0).getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverId\":%d}".formatted(caregiver.getId())))
                .andExpect(status().isCreated());

        confirm(requests, caregiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_NOT_ASSIGNABLE"));
    }

    @Test
    void 묶음을_취소하면_모든_예약이_취소되고_다시_확정할_수_있다() throws Exception {
        List<VisitRequest> requests = group(day1, day1.plusDays(1));
        confirm(requests, caregiver).andExpect(status().isCreated());

        cancel(requests)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(2))
                .andExpect(jsonPath("$.reservations[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CANCELED"))));
        assertThat(requests).extracting(VisitRequest::getStatus).containsOnly(VisitRequestStatus.REQUESTED);

        // 취소된 예약은 유니크 슬롯을 차지하지 않으므로 같은 보호사로 다시 확정 가능
        confirm(requests, caregiver).andExpect(status().isCreated());
    }

    @Test
    void 취소할_예약이_없으면_404() throws Exception {
        List<VisitRequest> requests = group(day1);

        cancel(requests)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void 없는_묶음이나_보호사면_404() throws Exception {
        mockMvc.perform(post("/api/visit-request-groups/{id}/reservations", 9999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverId\":%d}".formatted(caregiver.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_GROUP_NOT_FOUND"));

        List<VisitRequest> requests = group(day1);
        mockMvc.perform(post("/api/visit-request-groups/{id}/reservations", requests.get(0).getGroup().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverId\":9999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_FOUND"));
    }

    private ResultActions confirm(List<VisitRequest> requests, Caregiver target) throws Exception {
        return mockMvc.perform(post("/api/visit-request-groups/{id}/reservations", requests.get(0).getGroup().getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caregiverId\":%d}".formatted(target.getId())));
    }

    private ResultActions cancel(List<VisitRequest> requests) throws Exception {
        return mockMvc.perform(delete("/api/visit-request-groups/{id}/reservations",
                requests.get(0).getGroup().getId()));
    }

    // 10~12시 묶음
    private List<VisitRequest> group(LocalDate... dates) {
        VisitRequestGroup group = groupRepository.save(
                VisitRequestGroup.create(recipient, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        List<VisitRequest> requests = new ArrayList<>();
        for (LocalDate date : dates) {
            requests.add(visitRequestRepository.save(VisitRequest.createInGroup(group, date)));
        }
        return requests;
    }

    private Caregiver caregiver(String name, LocalDate... workDates) {
        Caregiver saved = caregiverRepository.save(Caregiver.create(name, Gender.FEMALE, 37.57, 126.98));
        for (LocalDate date : workDates) {
            availabilityRepository.save(CaregiverAvailability.create(
                    saved, date.getDayOfWeek(), LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }
        return saved;
    }

    private void reservation(Caregiver target, LocalDate date, int startHour, int endHour) {
        VisitRequest other = visitRequestRepository.save(VisitRequest.create(
                otherRecipient, date, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)));
        Reservation saved = reservationRepository.save(Reservation.confirm(target, other));
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
}
