package com.sf.release.web.support;

import com.sf.release.common.Masker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DummyController.class)
class ErrorHandlingTest {
    @Autowired MockMvc mvc;

    @Test void state_not_allowed_returns_40001_with_payload() throws Exception {
        mvc.perform(get("/dummy/state")).andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40001))
           .andExpect(jsonPath("$.data.currentStatus").value("DONE"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void masker_hides_token_and_secret() {
        assertThat(Masker.mask("{\"token\":\"abc123\",\"name\":\"x\"}"))
            .contains("\"token\":\"***\"").contains("\"name\":\"x\"");
    }

    @Test void incoming_trace_id_is_reused_and_echoed() throws Exception {
        mvc.perform(get("/dummy/state").header("X-Trace-Id", "trace-from-gateway"))
           .andExpect(status().isOk())
           .andExpect(header().string("X-Trace-Id", "trace-from-gateway"))
           .andExpect(jsonPath("$.traceId").value("trace-from-gateway"));
    }

    @Test void missing_trace_id_is_generated_and_echoed() throws Exception {
        MvcResult result = mvc.perform(get("/dummy/state"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-Id"))
            .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(headerTraceId).isNotBlank();
        assertThat(result.getResponse().getContentAsString())
            .contains("\"traceId\":\"" + headerTraceId + "\"");
    }

    @Test void validation_error_returns_40000_with_field_details() throws Exception {
        mvc.perform(post("/dummy/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40000))
           .andExpect(jsonPath("$.data.name").isNotEmpty())
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void unexpected_error_returns_50000() throws Exception {
        mvc.perform(get("/dummy/boom").header("X-Trace-Id", "trace-boom"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.code").value(50000))
           .andExpect(header().string("X-Trace-Id", "trace-boom"))
           .andExpect(jsonPath("$.traceId").value("trace-boom"));
    }
}