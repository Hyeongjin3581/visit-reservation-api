package com.hyeongjin.visit.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
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

// 묶음 신청 등록·조회와 입력 검증
@SpringBootTest
@Transactional
class VisitRequestGroupApiTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Clock clock;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;

    private MockMvc mockMvc;
    private LocalDate tomorrow;
    private Recipient recipient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        tomorrow = LocalDate.now(clock).plusDays(1);
        recipient = recipientRepository.save(Recipient.create("박수급", Gender.FEMALE, 37.5665, 126.9780));
    }

    @Test
    void 묶음을_등록하면_날짜마다_요청이_날짜순으로_생긴다() throws Exception {
        // 일부러 순서를 섞어 보냄
        String json = register(List.of(tomorrow.plusDays(7), tomorrow, tomorrow.plusDays(2)), "10:00", "12:00")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.recipientId").value(recipient.getId()))
                .andExpect(jsonPath("$.visitRequests.length()").value(3))
                .andExpect(jsonPath("$.visitRequests[0].visitDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.visitRequests[2].visitDate").value(tomorrow.plusDays(7).toString()))
                .andExpect(jsonPath("$.visitRequests[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("REQUESTED"))))
                .andReturn().getResponse().getContentAsString();
        long groupId = ((Number) JsonPath.read(json, "$.id")).longValue();

        List<VisitRequest> saved = visitRequestRepository.findAllWithRecipientByGroupId(groupId);
        assertThat(saved).hasSize(3)
                .allSatisfy(request -> assertThat(request.getGroup().getId()).isEqualTo(groupId));

        mockMvc.perform(get("/api/visit-request-groups/{id}", groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime").value("10:00:00"))
                .andExpect(jsonPath("$.visitRequests.length()").value(3));
    }

    @Test
    void 같은_날짜가_두_번_오면_400과_DUPLICATE_VISIT_DATE() throws Exception {
        register(List.of(tomorrow, tomorrow), "10:00", "12:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VISIT_DATE"));
    }

    @Test
    void 날짜가_없거나_14개를_넘으면_400() throws Exception {
        register(List.of(), "10:00", "12:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        List<LocalDate> fifteen = IntStream.range(0, 15).mapToObj(tomorrow::plusDays).toList();
        register(fifteen, "10:00", "12:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 지난_날짜가_하나라도_있으면_400() throws Exception {
        register(List.of(tomorrow, tomorrow.minusDays(3)), "10:00", "12:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 시간이_30분_단위가_아니면_400() throws Exception {
        register(List.of(tomorrow), "10:10", "12:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_SLOT"));
    }

    @Test
    void 없는_수급자나_묶음이면_404() throws Exception {
        mockMvc.perform(post("/api/visit-request-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9999L, List.of(tomorrow), "10:00", "12:00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
        mockMvc.perform(get("/api/visit-request-groups/{id}", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VISIT_REQUEST_GROUP_NOT_FOUND"));
    }

    private ResultActions register(List<LocalDate> dates, String start, String end) throws Exception {
        return mockMvc.perform(post("/api/visit-request-groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(recipient.getId(), dates, start, end)));
    }

    private String body(Long recipientId, List<LocalDate> dates, String start, String end) {
        String dateArray = dates.stream().map(d -> "\"" + d + "\"").collect(Collectors.joining(",", "[", "]"));
        return """
                {"recipientId":%d,"visitDates":%s,"startTime":"%s","endTime":"%s"}
                """.formatted(recipientId, dateArray, start, end);
    }
}
