package com.sf.release.pipelineconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP seam and persistence of {@code GET/PUT /api/v1/units/{unitId}/pipeline-config}
 * (P6-C design §3/§5 items 1, 2, 6 and 7):
 *
 * <ul>
 *   <li>a unit without a row returns the documented defaults with {@code configured:false}; a save
 *       persists a full replacement that the next GET echoes;</li>
 *   <li>every out-of-range/unknown value is 40000 and never creates a row;</li>
 *   <li>a save writes one scoped {@code SETTING_UPDATE} audit row for object {@code UNIT} with the
 *       whole configuration before/after;</li>
 *   <li>both endpoints need {@code setting:manage} and a non-ALL caller is limited to its
 *       {@code user_scope} units (40009).</li>
 * </ul>
 *
 * <p>The package is {@code com.sf.release.pipelineconfig} (not {@code ..web..}) for the same reason
 * as the P6-B settings controller test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PipelineConfigControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String PATH = "/api/v1/units/{unitId}/pipeline-config";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long subsystemId;
    private long unitId;
    private long otherUnitId;

    @BeforeEach
    void setUp() {
        PipelineConfigFixtures.clean(jdbc);
        PipelineConfigFixtures.givenUser(jdbc, PipelineConfigFixtures.ADMIN, "ADMIN");
        PipelineConfigFixtures.givenUser(jdbc, PipelineConfigFixtures.DEV, "DEV");
        PipelineConfigFixtures.givenUser(jdbc, PipelineConfigFixtures.UNIT_SETTINGS,
            PipelineConfigFixtures.SCOPE_ROLE);
        PipelineConfigFixtures.givenUnitSettingsRole(jdbc);

        subsystemId = PipelineConfigFixtures.givenSubsystem(jdbc, "p6c-http");
        unitId = PipelineConfigFixtures.givenUnit(jdbc, subsystemId, "p6c-http-api");
        otherUnitId = PipelineConfigFixtures.givenUnit(jdbc, subsystemId, "p6c-http-worker");
        PipelineConfigFixtures.givenScope(jdbc, PipelineConfigFixtures.UNIT_SETTINGS, unitId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM unit_pipeline_config");
        PipelineConfigFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ defaults and save

    @Test
    void a_unit_without_a_row_returns_the_defaults_and_configured_false() throws Exception {
        mvc.perform(get(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.unitId").value(unitId))
            .andExpect(jsonPath("$.data.envChecksEnabled").value(true))
            .andExpect(jsonPath("$.data.buildTimeoutMinutes").value(60))
            .andExpect(jsonPath("$.data.deployTimeoutMinutes").value(8))
            .andExpect(jsonPath("$.data.batchOrder").value("FORWARD"))
            .andExpect(jsonPath("$.data.batchOrderLabel").value("顺序"))
            .andExpect(jsonPath("$.data.configured").value(false))
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist())
            .andExpect(jsonPath("$.data.updatedAt").doesNotExist());

        assertThat(configRows(unitId)).isZero();
    }

    @Test
    void a_save_is_a_full_replacement_echoed_by_the_next_get() throws Exception {
        mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("false", "15", "3", "REVERSE")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.envChecksEnabled").value(false))
            .andExpect(jsonPath("$.data.buildTimeoutMinutes").value(15))
            .andExpect(jsonPath("$.data.deployTimeoutMinutes").value(3))
            .andExpect(jsonPath("$.data.batchOrder").value("REVERSE"))
            .andExpect(jsonPath("$.data.batchOrderLabel").value("逆序"))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.updatedBy").value(PipelineConfigFixtures.ADMIN));

        mvc.perform(get(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.envChecksEnabled").value(false))
            .andExpect(jsonPath("$.data.buildTimeoutMinutes").value(15))
            .andExpect(jsonPath("$.data.deployTimeoutMinutes").value(3))
            .andExpect(jsonPath("$.data.batchOrder").value("REVERSE"))
            .andExpect(jsonPath("$.data.configured").value(true));

        // A second save replaces the row in place (upsert, one row per unit).
        mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("true", "240", "120", "FORWARD")))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.buildTimeoutMinutes").value(240))
            .andExpect(jsonPath("$.data.deployTimeoutMinutes").value(120))
            .andExpect(jsonPath("$.data.batchOrder").value("FORWARD"));
        assertThat(configRows(unitId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ validation

    @Test
    void out_of_range_and_unknown_values_are_40000_and_leave_no_row() throws Exception {
        List<String> invalid = List.of(
            body("true", "4", "8", "FORWARD"),
            body("true", "241", "8", "FORWARD"),
            body("true", "60", "0", "FORWARD"),
            body("true", "60", "121", "FORWARD"),
            body("true", "60", "8", "SIDEWAYS"),
            body("true", "sixty", "8", "FORWARD"),
            body("true", "60", "eight", "FORWARD"),
            body("maybe", "60", "8", "FORWARD"),
            body(null, "60", "8", "FORWARD"),
            "{}");

        for (String payload : invalid) {
            mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
        }

        assertThat(configRows(unitId))
            .as("an invalid save must not create the row")
            .isZero();
    }

    @Test
    void an_unknown_unit_is_40000_on_read_and_write() throws Exception {
        mvc.perform(get(PATH, 999_999L).header(USER_HEADER, PipelineConfigFixtures.ADMIN))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(put(PATH, 999_999L).header(USER_HEADER, PipelineConfigFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("true", "60", "8", "FORWARD")))
            .andExpect(jsonPath("$.code").value(40000));
    }

    // ------------------------------------------------------------------ permission and scope

    @Test
    void both_endpoints_require_setting_manage() throws Exception {
        mvc.perform(get(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));

        mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("true", "60", "8", "FORWARD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));

        assertThat(configRows(unitId)).isZero();
    }

    @Test
    void a_scoped_caller_can_only_touch_its_own_units() throws Exception {
        mvc.perform(get(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.UNIT_SETTINGS))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.unitId").value(unitId));

        mvc.perform(get(PATH, otherUnitId).header(USER_HEADER, PipelineConfigFixtures.UNIT_SETTINGS))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(otherUnitId));

        mvc.perform(put(PATH, otherUnitId).header(USER_HEADER, PipelineConfigFixtures.UNIT_SETTINGS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("true", "60", "8", "FORWARD")))
            .andExpect(jsonPath("$.code").value(40009));

        mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.UNIT_SETTINGS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("false", "20", "5", "REVERSE")))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.updatedBy")
                .value(PipelineConfigFixtures.UNIT_SETTINGS));
    }

    // ------------------------------------------------------------------ audit

    @Test
    void the_save_writes_one_unit_scoped_setting_update_audit_row() throws Exception {
        mvc.perform(put(PATH, unitId).header(USER_HEADER, PipelineConfigFixtures.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("false", "15", "3", "REVERSE")))
            .andExpect(jsonPath("$.code").value(0));

        Map<String, Object> audit = jdbc.queryForMap(
            "SELECT actor, action, object_type, object_id, object_label, before_json, after_json,"
                + " scope_unit_id, scope_subsystem_id FROM audit_log"
                + " WHERE action = 'SETTING_UPDATE' AND object_id = ?",
            String.valueOf(unitId));
        assertThat(audit.get("actor")).isEqualTo(PipelineConfigFixtures.ADMIN);
        assertThat(audit.get("object_type")).isEqualTo("UNIT");
        assertThat(audit.get("object_label")).isEqualTo("部署单元流水线配置（" + unitId + "）");
        assertThat(audit.get("scope_unit_id")).isEqualTo(unitId);
        assertThat(audit.get("scope_subsystem_id")).isNull();

        JsonNode before = objectMapper.readTree((String) audit.get("before_json"));
        JsonNode after = objectMapper.readTree((String) audit.get("after_json"));
        assertThat(before.path("envChecksEnabled").asBoolean()).isTrue();
        assertThat(before.path("buildTimeoutMinutes").asInt()).isEqualTo(60);
        assertThat(before.path("deployTimeoutMinutes").asInt()).isEqualTo(8);
        assertThat(before.path("batchOrder").asText()).isEqualTo("FORWARD");
        assertThat(before.path("configured").asBoolean()).isFalse();
        assertThat(after.path("envChecksEnabled").asBoolean()).isFalse();
        assertThat(after.path("buildTimeoutMinutes").asInt()).isEqualTo(15);
        assertThat(after.path("deployTimeoutMinutes").asInt()).isEqualTo(3);
        assertThat(after.path("batchOrder").asText()).isEqualTo("REVERSE");
        assertThat(after.path("configured").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log"
            + " WHERE action = 'SETTING_UPDATE'", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static String body(String envChecksEnabled, String build, String deploy, String order) {
        return "{\"envChecksEnabled\":\"" + envChecksEnabled + "\",\"buildTimeoutMinutes\":\""
            + build + "\",\"deployTimeoutMinutes\":\"" + deploy + "\",\"batchOrder\":\"" + order
            + "\"}";
    }

    private int configRows(long id) {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unit_pipeline_config WHERE unit_id = ?", Integer.class, id);
        return rows == null ? 0 : rows;
    }
}