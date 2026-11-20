package com.sf.release.web.support;

import com.sf.release.common.Masker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

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

    @Test void masker_masks_full_quoted_values_for_every_key() {
        for (String key : List.of("token", "secret", "password", "authorization")) {
            String masked = Masker.mask("{\"" + key + "\":\"a,b&c'd\\\"e\",\"name\":\"x\"}");
            assertThat(masked)
                .contains("\"" + key + "\":\"***\"")
                .contains("\"name\":\"x\"")
                .doesNotContain("a,b")
                .doesNotContain("c'd");
        }
    }

    @Test void masker_masks_unquoted_values() {
        assertThat(Masker.mask("password=p@ss&token=t0k3n"))
            .contains("password=***")
            .contains("token=***")
            .doesNotContain("p@ss")
            .doesNotContain("t0k3n");
    }

    @Test void masker_returns_null_for_null() {
        assertThat(Masker.mask(null)).isNull();
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

    @Test void request_param_validation_returns_40000_with_field_detail() throws Exception {
        mvc.perform(get("/dummy/param").param("name", ""))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40000))
           .andExpect(jsonPath("$.data.name").value("name required"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void constraint_violation_returns_40000_with_field_detail() throws Exception {
        mvc.perform(get("/dummy/constraint").param("name", ""))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40000))
           .andExpect(jsonPath("$.data.name").value("name required"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void unreadable_body_returns_40000() throws Exception {
        mvc.perform(post("/dummy/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40000))
           .andExpect(jsonPath("$.data.body").isNotEmpty())
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test void client_error_log_is_masked(CapturedOutput output) throws Exception {
        mvc.perform(get("/dummy/read-error"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40000));

        assertThat(output.getAll())
            .contains("HttpMessageNotReadableException")
            .doesNotContain("super-secret");
    }

    @Test void unknown_path_keeps_404() throws Exception {
        mvc.perform(get("/dummy/unknown"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(40400))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void unsupported_method_keeps_405() throws Exception {
        mvc.perform(post("/dummy/state"))
           .andExpect(status().isMethodNotAllowed())
           .andExpect(jsonPath("$.code").value(40500))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void unsupported_media_type_keeps_415() throws Exception {
        mvc.perform(post("/dummy/validate")
                .contentType(MediaType.TEXT_PLAIN)
                .content("x"))
           .andExpect(status().isUnsupportedMediaType())
           .andExpect(jsonPath("$.code").value(41500))
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