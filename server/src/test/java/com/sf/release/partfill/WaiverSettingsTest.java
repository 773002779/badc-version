package com.sf.release.partfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.infra.repo.AppSettingMapper;
import com.sf.release.infra.repo.AppUserRepository;
import com.sf.release.infra.repo.UserRoleMapper;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * P6-F design §3/§5.3: the {@code WAIVER} settings section and its
 * {@code GET/PUT /api/v1/settings/waiver} seam.
 *
 * <p>Pinned here: the seeded seven-day default, the 1–90 validation of {@code defaultDays}, the
 * {@code setting:manage} permission (40009), the documented fallback to seven days when the row is
 * missing/malformed/out of range or unreadable, and the one {@code SETTING_UPDATE} audit row per
 * save with the section before/after.
 *
 * <p>Package {@code com.sf.release.partfill}, not {@code ..web..}, like the other settings tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WaiverSettingsTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "p6f-ws-admin";
    private static final String DEV = "p6f-ws-dev";
    private static final String PATH = "/api/v1/settings/waiver";

    private static final String GATE_SEED = "{\"enabledChecks\":[\"SONAR\",\"COMPONENT_VULN\","
        + "\"ARCHITECTURE\",\"VERSION_DEFECT\",\"SECURITY_DYNAMIC\",\"AUTO_TEST\",\"DB_NORM\"]}";
    private static final String APPROVER_SEED = "{\"opsLead\":[],\"deptManager\":[]}";
    private static final String LOCK_SEED = "{\"pauseBoundHours\":168}";
    private static final String APPROVAL_SEED =
        "{\"enabled\":true,\"remindHours\":12,\"escalateHours\":24}";
    private static final String WAIVER_SEED = "{\"defaultDays\":7}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SettingsService settings;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_role WHERE user_id LIKE 'p6f-ws-%'");
        jdbc.update("DELETE FROM app_user WHERE id LIKE 'p6f-ws-%'");
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        resetSettings();
    }

    @AfterEach
    void restoreSettings() {
        resetSettings();
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void the_seeded_waiver_default_is_seven_days() throws Exception {
        mvc.perform(get(PATH).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.defaultDays").value(7))
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    // ------------------------------------------------------------------ permission

    @Test
    void both_waiver_endpoints_require_setting_manage() throws Exception {
        mvc.perform(get(PATH).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));

        mvc.perform(put(PATH).header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultDays\":30}"))
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(settings.waiver().defaultDays())
            .as("a denied save must not change the section")
            .isEqualTo(7);
    }

    // ------------------------------------------------------------------ validation

    @Test
    void the_default_accepts_one_and_ninety_days_and_persists_both() throws Exception {
        mvc.perform(put(PATH).header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultDays\":1}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.defaultDays").value(1));
        assertThat(storedWaiverDays()).isEqualTo(1);

        mvc.perform(put(PATH).header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultDays\":90}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.defaultDays").value(90));
        assertThat(storedWaiverDays()).isEqualTo(90);
    }

    @Test
    void values_outside_one_to_ninety_days_are_40000_and_never_stored() throws Exception {
        List<String> invalid = List.of(
            "{}",
            "{\"defaultDays\":null}",
            "{\"defaultDays\":0}",
            "{\"defaultDays\":91}",
            "{\"defaultDays\":-1}");

        for (String body : invalid) {
            mvc.perform(put(PATH).header(USER_HEADER, ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40000));
        }

        assertThat(settings.waiver().defaultDays())
            .as("an invalid save must never change the stored section")
            .isEqualTo(7);
        assertThat(storedWaiverDays()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ save + audit

    @Test
    void saving_the_default_persists_the_section_and_audits_the_change() throws Exception {
        mvc.perform(put(PATH).header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"defaultDays\":10}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.defaultDays").value(10))
            .andExpect(jsonPath("$.data.updatedBy").value(ADMIN));

        assertThat(storedWaiverDays()).isEqualTo(10);

        Map<String, Object> audit = soleSettingAudit();
        assertThat(audit.get("actor")).isEqualTo(ADMIN);
        assertThat(audit.get("action")).isEqualTo("SETTING_UPDATE");
        assertThat(audit.get("object_type")).isEqualTo("SETTING");
        assertThat(audit.get("object_id")).isEqualTo("WAIVER");
        assertThat(audit.get("scope_unit_id")).isNull();
        assertThat(audit.get("scope_subsystem_id")).isNull();
        JsonNode before = objectMapper.readTree((String) audit.get("before_json"));
        JsonNode after = objectMapper.readTree((String) audit.get("after_json"));
        assertThat(before.path("defaultDays").asInt()).isEqualTo(7);
        assertThat(after.path("defaultDays").asInt()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ fallback

    @Test
    void a_missing_or_invalid_section_falls_back_to_seven_days() throws Exception {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'WAIVER'");
        assertThat(settings.waiver().defaultDays()).isEqualTo(7);

        for (String json : List.of("[]", "123", "{}", "{\"defaultDays\":0}",
                "{\"defaultDays\":91}", "{\"days\":7}")) {
            replaceWaiver(json);
            assertThat(settings.waiver().defaultDays())
                .as("stored section " + json)
                .isEqualTo(7);
        }

        replaceWaiver("{\"defaultDays\":10}");
        assertThat(settings.waiver().defaultDays()).isEqualTo(10);
    }

    @Test
    void an_unreadable_section_falls_back_to_seven_days() {
        AppSettingMapper failing = mock(AppSettingMapper.class);
        when(failing.selectById("WAIVER")).thenThrow(new IllegalStateException("db is down"));

        SettingsService isolated = new SettingsService(failing, mock(UserRoleMapper.class),
            mock(AppUserRepository.class), mock(AuditService.class), new ObjectMapper(),
            mock(PlatformTransactionManager.class));

        assertThat(isolated.waiver().defaultDays()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ fixtures

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    /** The five seeded sections, exactly as V30/V33/V34 write them. */
    private void resetSettings() {
        jdbc.update("DELETE FROM app_setting");
        insertSetting("GATE", GATE_SEED);
        insertSetting("APPROVER", APPROVER_SEED);
        insertSetting("LOCK", LOCK_SEED);
        insertSetting("APPROVAL", APPROVAL_SEED);
        insertSetting("WAIVER", WAIVER_SEED);
    }

    private void insertSetting(String key, String json) {
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES (?, ?, NOW(3))", key, json);
    }

    /** Stores a raw section payload; the row may be missing (the fallback test deletes it). */
    private void replaceWaiver(String json) {
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES ('WAIVER', ?, NOW(3))"
            + " ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_at = NOW(3)",
            json);
    }

    private int storedWaiverDays() {
        String json = jdbc.queryForObject(
            "SELECT value_json FROM app_setting WHERE setting_key = 'WAIVER'", String.class);
        try {
            return objectMapper.readTree(json).path("defaultDays").asInt();
        } catch (Exception ex) {
            throw new IllegalStateException("stored WAIVER section is not JSON: " + json, ex);
        }
    }

    private Map<String, Object> soleSettingAudit() {
        return jdbc.queryForMap("SELECT actor, action, object_type, object_id, before_json,"
            + " after_json, scope_unit_id, scope_subsystem_id FROM audit_log"
            + " WHERE action = 'SETTING_UPDATE' AND object_id = 'WAIVER'"
            + " ORDER BY id DESC LIMIT 1");
    }
}