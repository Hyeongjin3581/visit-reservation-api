package com.hyeongjin.visit;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

// 1주차 등록 API 통합 테스트 (H2, 테스트마다 롤백)
@SpringBootTest
@Transactional
class RegistrationApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 요양보호사를_등록하면_201과_ACTIVE_상태를_돌려준다() throws Exception {
        mockMvc.perform(post("/api/caregivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"김보호","gender":"FEMALE","latitude":37.5665,"longitude":126.9780}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 위도가_범위를_벗어나면_400과_INVALID_INPUT() throws Exception {
        mockMvc.perform(post("/api/caregivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"김보호","gender":"FEMALE","latitude":120.0,"longitude":126.9780}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 근무_가능_시간을_등록한다() throws Exception {
        long caregiverId = createCaregiver();

        mockMvc.perform(post("/api/caregivers/{id}/availabilities", caregiverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"MONDAY","startTime":"09:00","endTime":"18:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caregiverId").value(caregiverId));
    }

    @Test
    void 근무_시간이_30분_단위가_아니면_400과_INVALID_TIME_SLOT() throws Exception {
        long caregiverId = createCaregiver();

        mockMvc.perform(post("/api/caregivers/{id}/availabilities", caregiverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"MONDAY","startTime":"09:10","endTime":"18:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_SLOT"));
    }

    @Test
    void 없는_요양보호사에게_근무_시간을_등록하면_404() throws Exception {
        mockMvc.perform(post("/api/caregivers/{id}/availabilities", 9999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"MONDAY","startTime":"09:00","endTime":"18:00"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAREGIVER_NOT_FOUND"));
    }

    @Test
    void 수급자와_방문_요청을_등록하고_조회한다() throws Exception {
        String recipientJson = mockMvc.perform(post("/api/recipients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"박수급","preferredGender":"FEMALE","latitude":37.5,"longitude":127.0}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long recipientId = ((Number) JsonPath.read(recipientJson, "$.id")).longValue();

        String visitDate = LocalDate.now().plusDays(1).toString();
        String requestJson = mockMvc.perform(post("/api/visit-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientId":%d,"visitDate":"%s","startTime":"10:00","endTime":"12:00"}
                                """.formatted(recipientId, visitDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long visitRequestId = ((Number) JsonPath.read(requestJson, "$.id")).longValue();

        mockMvc.perform(get("/api/visit-requests/{id}", visitRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value(recipientId))
                .andExpect(jsonPath("$.visitDate").value(visitDate));
    }

    @Test
    void 지난_날짜로_방문_요청하면_400() throws Exception {
        mockMvc.perform(post("/api/visit-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientId":1,"visitDate":"2020-01-01","startTime":"10:00","endTime":"12:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private long createCaregiver() throws Exception {
        String json = mockMvc.perform(post("/api/caregivers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"이보호","gender":"MALE","latitude":37.55,"longitude":126.99}
                                """))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
