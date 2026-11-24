package com.sf.release.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The HTTP seam of the audit page (audit design section 4): the paged answer with the action label,
 * the CSV download headers/BOM, the 40000 validations and the authentication rule.
 *
 * <p>Package {@code com.sf.release.audit}, not {@code ..web..}: the architecture rule forbids the web
 * package depending on domain/infra, and these tests need the real schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "audit-ctrl-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long subsystem;
    private long unit;

    @BeforeEach
    void setUp() {
        AuditFixtures.clean(jdbc);
        AuditFixtures.givenUser(jdbc, ADMIN, "ADMIN");
        subsystem = AuditFixtures.givenSubsystem(jdbc, "AUD-CTRL-SYS", ADMIN);
        unit = AuditFixtures.givenUnit(jdbc, subsystem, "audit-ctrl-unit", ADMIN);
        givenAudit("PUBLISH", "ORDER", "PO-CTRL-1", "c");
        givenAudit("APPROVE", "ORDER", "PO-CTRL-1", "c");
    }

    @Test
    void the_page_returns_the_rows_with_the_server_side_action_label() throws Exception {
        mvc.perform(get("/api/v1/audit").header(USER_HEADER, ADMIN)
                .param("objectType", "ORDER").param("objectId", "PO-CTRL-1")
                .param("page", "1").param("size", "1"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].action").value("APPROVE"))
            .andExpect(jsonPath("$.data.records[0].actionLabel").value("审批通过"))
            .andExpect(jsonPath("$.data.records[0].actor").value(ADMIN))
            .andExpect(jsonPath("$.data.records[0].objectType").value("ORDER"))
            .andExpect(jsonPath("$.data.records[0].objectId").value("PO-CTRL-1"))
            .andExpect(jsonPath("$.data.records[0].createdAt").isNotEmpty());
    }

    @Test
    void the_export_is_a_utf8_csv_download_with_a_bom() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audit/export").header(USER_HEADER, ADMIN))
            .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
            .startsWith("attachment;").contains(".csv");
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"动作名称\"").contains("\"审批通过\"")
            .contains("PO-CTRL-1");
    }

    @Test
    void an_invalid_filter_is_40000() throws Exception {
        mvc.perform(get("/api/v1/audit").header(USER_HEADER, ADMIN)
                .param("action", "NOT_AN_ACTION"))
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(get("/api/v1/audit").header(USER_HEADER, ADMIN)
                .param("objectType", "NOT_A_TYPE"))
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(get("/api/v1/audit").header(USER_HEADER, ADMIN)
                .param("from", "2026-11-25T10:00:00Z")
                .param("to", "2026-11-24T10:00:00Z"))
            .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void a_request_without_identity_is_forbidden() throws Exception {
        mvc.perform(get("/api/v1/audit"))
            .andExpect(jsonPath("$.code").value(40009));
    }

    private void givenAudit(String action, String objectType, String objectId, String summary) {
        jdbc.update("INSERT INTO audit_log (actor, action, object_type, object_id, object_label,"
                + " summary, reason, before_json, after_json, scope_unit_id, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)",
            ADMIN, action, objectType, objectId, objectId, summary, unit, Timestamp.from(
                Instant.now()));
    }
}