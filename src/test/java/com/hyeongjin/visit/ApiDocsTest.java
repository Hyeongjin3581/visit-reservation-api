package com.hyeongjin.visit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Swagger(springdoc) 가 API 명세를 만들어 내는지 확인
@SpringBootTest
class ApiDocsTest {

    @Autowired private WebApplicationContext context;

    @Test
    void API_명세에_추천과_예약_경로가_들어있다() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/visit-requests/{visitRequestId}/recommendations")))
                .andExpect(content().string(containsString("/api/visit-requests/{visitRequestId}/reservations")));
    }
}
