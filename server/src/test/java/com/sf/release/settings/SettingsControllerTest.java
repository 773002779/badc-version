package com.sf.release.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.security.AuthProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * HTTP seam and persistence of the P6-B settings endpoints (design §3/§5):
 * {@code GET/PUT /api/v1/settings/{gate,approvers,lock}}.
 *
 * <p>Pinned here: the seeded defaults, the 40000 validation of every block, the one
 * {@code SETTING_UPDATE} audit row per save with the whole section before/after, the exact
 * {@code user_role} replacement for the two approval roles, and the immediate effect of that
 * replacement on {@code JdbcAuthProvider}.
 *
 * <p>Package {@code com.sf.release.settings}, not {@code ..web..}, exactly like the notification
 * controller test: the web package keeps production classes only and may not depend on
 * domain/infra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsControllerTest {

    private static final String USER_HEADER = "X-User-Id";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthProvider authProvider;

    /** Only here so this class shares one Spring context with {@link SettingsEffectTest}. */
    @MockBean GitCommitProbe commitProbe;

    @BeforeEach
    void setUp() {
        SettingsFixtures.clean(jdbc);
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.ADMIN, "ADMIN");
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.DEV, "DEV");
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.NEW_LEAD);
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.NEW_MANAGER);
        SettingsFixtures.givenInactiveUser(jdbc, SettingsFixtures.INACTIVE);
    }

    @AfterEach
    void restoreDefaults() {
        // The schema is shared with the rest of the suite; a modified gate switch must not leak
        // into another slice's gate/publish tests.
        SettingsFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void the_three_sections_return_the_seeded_defaults() throws Exception {
        mvc.perform(get("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.enabledChecks.length()").value(7))
            .andExpect(jsonPath("$.data.enabledChecks[0]").value("SONAR"))
            .andExpect(jsonPath("$.data.enabledChecks[6]").value("DB_NORM"))
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist());

        mvc.perform(get("/api/v1/settings/approvers").header(USER_HEADER, SettingsFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.opsLead.length()").value(0))
            .andExpect(jsonPath("$.data.deptManager.length()").value(0));

        mvc.perform(get("/api/v1/settings/lock").header(USER_HEADER, SettingsFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pauseBoundHours").value(168))
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void every_settings_endpoint_requires_setting_manage() throws Exception {
        mvc.perform(get("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.DEV))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));
        mvc.perform(put("/api/v1/settings/approvers").header(USER_HEADER, SettingsFixtures.DEV)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(put("/api/v1/settings/lock").header(USER_HEADER, SettingsFixtures.DEV)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pauseBoundHours\":1}"))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(get("/api/v1/settings/lock"))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ gate

    @Test
    void the_gate_rejects_an_unknown_check_and_an_empty_list() throws Exception {
        mvc.perform(put("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabledChecks\":[\"SONAR\",\"NOT_A_CHECK\"]}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.enabledChecks").isNotEmpty());

        mvc.perform(put("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabledChecks\":[]}"))
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(put("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(jsonPath("$.code").value(40000));

        assertThat(setting("GATE")).as("the invalid saves must not change the stored section")
            .isEqualTo(objectMapper.readTree(SettingsFixtures.GATE_SEED_JSON));
    }

    @Test
    void saving_the_gate_persists_the_enabled_set_and_audits_the_whole_section() throws Exception {
        mvc.perform(put("/api/v1/settings/gate").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabledChecks\":[\"DB_NORM\",\"SONAR\"]}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.enabledChecks.length()").value(2))
            .andExpect(jsonPath("$.data.enabledChecks[0]").value("SONAR"))
            .andExpect(jsonPath("$.data.enabledChecks[1]").value("DB_NORM"))
            .andExpect(jsonPath("$.data.updatedBy").value(SettingsFixtures.ADMIN));

        JsonNode stored = objectMapper.readTree(settingJson("GATE"));
        assertThat(stored.path("enabledChecks")).hasSize(2);
        assertThat(stored.path("enabledChecks").get(0).asText()).isEqualTo("SONAR");
        assertThat(stored.path("enabledChecks").get(1).asText()).isEqualTo("DB_NORM");

        Map<String, Object> audit = soleSettingAudit("GATE");
        assertThat(audit.get("actor")).isEqualTo(SettingsFixtures.ADMIN);
        assertThat(audit.get("object_type")).isEqualTo("SETTING");
        assertThat(audit.get("scope_unit_id")).isNull();
        assertThat(audit.get("scope_subsystem_id")).isNull();
        JsonNode before = objectMapper.readTree((String) audit.get("before_json"));
        JsonNode after = objectMapper.readTree((String) audit.get("after_json"));
        assertThat(before.path("enabledChecks")).hasSize(7);
        assertThat(after.path("enabledChecks")).hasSize(2);
        assertThat(after.path("enabledChecks").get(1).asText()).isEqualTo("DB_NORM");
    }

    // ------------------------------------------------------------------ approvers

    @Test
    void the_approver_lists_replace_only_the_two_approval_roles() throws Exception {
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OLD_OPS_LEAD, "OPS_LEAD");
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OLD_DEPT_MANAGER, "DEPT_MANAGER");

        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[\"" + SettingsFixtures.NEW_LEAD + "\",\""
                    + SettingsFixtures.NEW_LEAD + "\"],\"deptManager\":[\""
                    + SettingsFixtures.NEW_MANAGER + "\",\"" + SettingsFixtures.NEW_LEAD
                    + "\"]}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.opsLead.length()").value(1))
            .andExpect(jsonPath("$.data.opsLead[0]").value(SettingsFixtures.NEW_LEAD))
            .andExpect(jsonPath("$.data.deptManager.length()").value(2))
            .andExpect(jsonPath("$.data.updatedBy").value(SettingsFixtures.ADMIN));

        assertThat(usersOfRole("OPS_LEAD"))
            .containsExactly(SettingsFixtures.NEW_LEAD);
        assertThat(usersOfRole("DEPT_MANAGER"))
            .containsExactlyInAnyOrder(SettingsFixtures.NEW_LEAD, SettingsFixtures.NEW_MANAGER);
        assertThat(usersOfRole("DEV"))
            .as("a non-approval role is never touched")
            .containsExactly(SettingsFixtures.DEV);

        JsonNode stored = objectMapper.readTree(settingJson("APPROVER"));
        assertThat(stored.path("opsLead")).hasSize(1);
        assertThat(stored.path("deptManager")).hasSize(2);

        Map<String, Object> audit = soleSettingAudit("APPROVER");
        JsonNode before = objectMapper.readTree((String) audit.get("before_json"));
        JsonNode after = objectMapper.readTree((String) audit.get("after_json"));
        assertThat(before.path("opsLead")).hasSize(1);
        assertThat(after.path("opsLead").get(0).asText()).isEqualTo(SettingsFixtures.NEW_LEAD);
    }

    @Test
    void the_new_approver_is_effective_immediately_and_the_removed_one_is_not() throws Exception {
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OLD_OPS_LEAD, "OPS_LEAD");

        assertThat(authProvider.authenticate(SettingsFixtures.OLD_OPS_LEAD).orElseThrow()
            .hasPerm("control:approve")).isTrue();

        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[\"" + SettingsFixtures.NEW_LEAD
                    + "\"],\"deptManager\":[]}"))
            .andExpect(jsonPath("$.code").value(0));

        // JdbcAuthProvider reads user_role on every request, so no cache/restart is involved.
        var newLead = authProvider.authenticate(SettingsFixtures.NEW_LEAD).orElseThrow();
        assertThat(newLead.roles()).contains("OPS_LEAD");
        assertThat(newLead.hasPerm("control:approve")).isTrue();
        var oldLead = authProvider.authenticate(SettingsFixtures.OLD_OPS_LEAD).orElseThrow();
        assertThat(oldLead.roles()).doesNotContain("OPS_LEAD");
        assertThat(oldLead.hasPerm("control:approve")).isFalse();
    }

    @Test
    void an_empty_approver_list_clears_the_role() throws Exception {
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OLD_OPS_LEAD, "OPS_LEAD");
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OLD_DEPT_MANAGER, "DEPT_MANAGER");

        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[],\"deptManager\":[]}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.opsLead.length()").value(0))
            .andExpect(jsonPath("$.data.deptManager.length()").value(0));

        assertThat(usersOfRole("OPS_LEAD")).isEmpty();
        assertThat(usersOfRole("DEPT_MANAGER")).isEmpty();
    }

    @Test
    void the_approver_lists_reject_a_missing_inactive_or_blank_user() throws Exception {
        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[\"no-such-user\"],\"deptManager\":[]}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.opsLead").isNotEmpty());

        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[],\"deptManager\":[\"" + SettingsFixtures.INACTIVE
                    + "\"]}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.deptManager").isNotEmpty());

        mvc.perform(put("/api/v1/settings/approvers")
                .header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opsLead\":[\"  \"],\"deptManager\":[]}"))
            .andExpect(jsonPath("$.code").value(40000));

        assertThat(usersOfRole("OPS_LEAD")).isEmpty();
        assertThat(setting("APPROVER")).isEqualTo(
            objectMapper.readTree(SettingsFixtures.APPROVER_SEED_JSON));
    }

    // ------------------------------------------------------------------ lock

    @Test
    void the_lock_bound_is_validated_and_persisted() throws Exception {
        for (String body : List.of("{\"pauseBoundHours\":0}", "{\"pauseBoundHours\":8761}",
                "{\"pauseBoundHours\":null}", "{}")) {
            mvc.perform(put("/api/v1/settings/lock")
                    .header(USER_HEADER, SettingsFixtures.ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40000));
        }
        assertThat(setting("LOCK")).isEqualTo(
            objectMapper.readTree(SettingsFixtures.LOCK_SEED_JSON));

        mvc.perform(put("/api/v1/settings/lock").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseBoundHours\":2}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pauseBoundHours").value(2))
            .andExpect(jsonPath("$.data.updatedBy").value(SettingsFixtures.ADMIN));

        mvc.perform(put("/api/v1/settings/lock").header(USER_HEADER, SettingsFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseBoundHours\":8760}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pauseBoundHours").value(8760));

        assertThat(objectMapper.readTree(settingJson("LOCK")).path("pauseBoundHours").asInt())
            .isEqualTo(8760);
        Map<String, Object> audit = soleSettingAudit("LOCK");
        assertThat(objectMapper.readTree((String) audit.get("before_json"))
            .path("pauseBoundHours").asInt()).isEqualTo(2);
        assertThat(objectMapper.readTree((String) audit.get("after_json"))
            .path("pauseBoundHours").asInt()).isEqualTo(8760);
    }

    // ------------------------------------------------------------------ helpers

    private String settingJson(String section) {
        return jdbc.queryForObject(
            "SELECT value_json FROM app_setting WHERE setting_key = ?", String.class, section);
    }

    /** MySQL normalizes JSON whitespace, so assertions compare parsed trees, not raw text. */
    private JsonNode setting(String section) throws JsonProcessingException {
        return objectMapper.readTree(settingJson(section));
    }

    private List<String> usersOfRole(String role) {
        return jdbc.queryForList(
            "SELECT user_id FROM user_role WHERE role = ? ORDER BY user_id", String.class, role);
    }

    private Map<String, Object> soleSettingAudit(String objectId) {
        return jdbc.queryForMap("SELECT actor, action, object_type, object_id, before_json,"
            + " after_json, scope_unit_id, scope_subsystem_id FROM audit_log"
            + " WHERE action = 'SETTING_UPDATE' AND object_id = ? ORDER BY id DESC LIMIT 1",
            objectId);
    }
}