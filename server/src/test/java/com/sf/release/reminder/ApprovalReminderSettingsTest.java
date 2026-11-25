package com.sf.release.reminder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.application.settings.SettingsService.ApprovalSla;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The P6-E configuration contract (design §2/§4): the {@code APPROVAL} section of {@code app_setting},
 * its {@code GET/PUT /api/v1/settings/approval} seam, the 1–72 / 2–168 / {@code escalate >
 * remind} validation, the one {@code SETTING_UPDATE} audit row per save, and the documented
 * fallback to enabled 12/24 when the row is missing, malformed or inconsistent.
 *
 * <p>Package {@code com.sf.release.reminder}, not {@code ..web..}, like the settings/notification
 * controller tests: the web package keeps production classes only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalReminderSettingsTest {

    private static final String USER_HEADER = "X-User-Id";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SettingsService settings;

    @BeforeEach
    void setUp() {
        ReminderFixtures.clean(jdbc);
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.ADMIN_A, "ADMIN");
        ReminderFixtures.givenUser(jdbc, ReminderFixtures.DEV, "DEV");
    }

    @AfterEach
    void restoreDefaults() {
        // The schema is shared with the rest of the suite: a saved SLA must not leak into another
        // slice's approval/reminder tests.
        ReminderFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void the_approval_section_returns_the_seeded_defaults() throws Exception {
        mvc.perform(get("/api/v1/settings/approval")
                .header(USER_HEADER, ReminderFixtures.ADMIN_A))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.remindHours").value(12))
            .andExpect(jsonPath("$.data.escalateHours").value(24))
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void the_approval_endpoints_require_setting_manage() throws Exception {
        mvc.perform(get("/api/v1/settings/approval").header(USER_HEADER, ReminderFixtures.DEV))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));

        mvc.perform(put("/api/v1/settings/approval").header(USER_HEADER, ReminderFixtures.DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"remindHours\":12,\"escalateHours\":24}"))
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(storedApproval()).isEqualTo(
            objectMapper.readTree(ReminderFixtures.APPROVAL_SEED_JSON));
    }

    // ------------------------------------------------------------------ save

    @Test
    void saving_the_sla_persists_the_section_and_audits_the_whole_change() throws Exception {
        mvc.perform(put("/api/v1/settings/approval")
                .header(USER_HEADER, ReminderFixtures.ADMIN_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"remindHours\":6,\"escalateHours\":18}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.remindHours").value(6))
            .andExpect(jsonPath("$.data.escalateHours").value(18))
            .andExpect(jsonPath("$.data.updatedBy").value(ReminderFixtures.ADMIN_A));

        JsonNode stored = storedApproval();
        assertThat(stored.path("enabled").asBoolean()).isFalse();
        assertThat(stored.path("remindHours").asInt()).isEqualTo(6);
        assertThat(stored.path("escalateHours").asInt()).isEqualTo(18);

        Map<String, Object> audit = soleAudit("APPROVAL");
        assertThat(audit.get("actor")).isEqualTo(ReminderFixtures.ADMIN_A);
        assertThat(audit.get("action")).isEqualTo("SETTING_UPDATE");
        assertThat(audit.get("object_type")).isEqualTo("SETTING");
        assertThat(audit.get("scope_unit_id")).isNull();
        assertThat(audit.get("scope_subsystem_id")).isNull();
        JsonNode before = objectMapper.readTree((String) audit.get("before_json"));
        JsonNode after = objectMapper.readTree((String) audit.get("after_json"));
        assertThat(before.path("enabled").asBoolean()).isTrue();
        assertThat(before.path("remindHours").asInt()).isEqualTo(12);
        assertThat(after.path("remindHours").asInt()).isEqualTo(6);
        assertThat(after.path("escalateHours").asInt()).isEqualTo(18);
    }

    @Test
    void the_sla_rejects_out_of_range_or_inconsistent_values() throws Exception {
        List<String> invalid = List.of(
            "{}",
            "{\"remindHours\":12,\"escalateHours\":24}",
            "{\"enabled\":true,\"escalateHours\":24}",
            "{\"enabled\":true,\"remindHours\":12}",
            "{\"enabled\":true,\"remindHours\":0,\"escalateHours\":24}",
            "{\"enabled\":true,\"remindHours\":73,\"escalateHours\":168}",
            "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":1}",
            "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":169}",
            "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":12}",
            "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":11}");

        for (String body : invalid) {
            mvc.perform(put("/api/v1/settings/approval")
                    .header(USER_HEADER, ReminderFixtures.ADMIN_A)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40000));
        }

        assertThat(storedApproval())
            .as("an invalid save must never change the stored section")
            .isEqualTo(objectMapper.readTree(ReminderFixtures.APPROVAL_SEED_JSON));
        assertThat(settings.approvalSla().enabled()).isTrue();
        assertThat(settings.approvalSla().remindHours()).isEqualTo(12);
        assertThat(settings.approvalSla().escalateHours()).isEqualTo(24);
    }

    // ------------------------------------------------------------------ fallback

    @Test
    void an_invalid_or_missing_section_falls_back_to_enabled_12_and_24() {
        jdbc.update("UPDATE app_setting SET value_json = '[]' WHERE setting_key = 'APPROVAL'");
        assertSla(settings.approvalSla(), true, 12, 24);

        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'APPROVAL'");
        assertSla(settings.approvalSla(), true, 12, 24);

        ReminderFixtures.insertSetting(jdbc, "APPROVAL", "123");
        assertSla(settings.approvalSla(), true, 12, 24);

        // A readable but inconsistent pair: the escalation must stay above the reminder, so the
        // documented defaults win instead of a threshold crossing that would escalate immediately.
        jdbc.update("UPDATE app_setting SET value_json = ? WHERE setting_key = 'APPROVAL'",
            "{\"enabled\":false,\"remindHours\":20,\"escalateHours\":10}");
        assertSla(settings.approvalSla(), true, 12, 24);
    }

    @Test
    void a_valid_stored_section_is_read_verbatim() {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'APPROVAL'");
        // Deliberately disable through raw SQL: the read path must not depend on a save.
        ReminderFixtures.insertSetting(jdbc, "APPROVAL",
            "{\"enabled\":false,\"remindHours\":6,\"escalateHours\":12}");
        assertSla(settings.approvalSla(), false, 6, 12);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertSla(ApprovalSla sla, boolean enabled, int remind, int escalate) {
        assertThat(sla.enabled()).isEqualTo(enabled);
        assertThat(sla.remindHours()).isEqualTo(remind);
        assertThat(sla.escalateHours()).isEqualTo(escalate);
    }

    private JsonNode storedApproval() throws JsonProcessingException {
        String json = jdbc.queryForObject(
            "SELECT value_json FROM app_setting WHERE setting_key = 'APPROVAL'", String.class);
        return objectMapper.readTree(json);
    }

    private Map<String, Object> soleAudit(String objectId) {
        return jdbc.queryForMap("SELECT actor, action, object_type, object_id, before_json,"
            + " after_json, scope_unit_id, scope_subsystem_id FROM audit_log"
            + " WHERE action = 'SETTING_UPDATE' AND object_id = ? ORDER BY id DESC LIMIT 1",
            objectId);
    }
}
