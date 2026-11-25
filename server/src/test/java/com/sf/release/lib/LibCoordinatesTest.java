package com.sf.release.lib;

import com.sf.release.application.UnitAppService;
import com.sf.release.application.UnitAppService.CreateCmd;
import com.sf.release.application.UnitAppService.UnitView;
import com.sf.release.application.UnitAppService.UpdateCmd;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Artifact coordinate rules of the unit form (P6-A design §4, test §5 item 1): a {@code LIB}
 * must supply both Maven coordinates in the documented format, while a {@code UNIT} ignores the
 * two fields and stores nothing — the deployment path never changes.
 */
@SpringBootTest
@ActiveProfiles("test")
class LibCoordinatesTest {

    private static final String SUBSYSTEM = "SCC-P6A-COORD";

    @Autowired UnitAppService unitAppService;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;

    @BeforeEach
    void setUp() {
        LibFixtures.reset(jdbc);
        TestUserContext.allUnits(LibFixtures.OWNER);
        LibFixtures.givenUser(jdbc, LibFixtures.OWNER);
        subsystemId = LibFixtures.givenSubsystem(jdbc, SUBSYSTEM);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    @Test
    void a_lib_requires_both_coordinates() {
        assertThatThrownBy(() -> unitAppService.create(createCmd("p6a-sdk", "LIB", null, "sdk")))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat(ex.getError().getCode()).isEqualTo(40000);
                assertThat(payload(ex)).containsEntry("artifactGroupId", "不能为空");
            });
        assertThatThrownBy(() -> unitAppService.create(createCmd("p6a-sdk", "LIB", "com.sf", " ")))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(unitCount("p6a-sdk")).isZero();
    }

    @Test
    void a_lib_rejects_coordinates_outside_the_documented_format() {
        assertThatThrownBy(() -> unitAppService.create(
            createCmd("p6a-sdk", "LIB", "com/sf group", "sdk")))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat(payload(ex)).containsKey("artifactGroupId");
            });
        assertThatThrownBy(() -> unitAppService.create(
            createCmd("p6a-sdk", "LIB", "com.sf", "sdk@1")))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID);
                assertThat(payload(ex)).containsKey("artifactArtifactId");
            });
        assertThatThrownBy(() -> unitAppService.create(
            createCmd("p6a-sdk", "LIB", "g".repeat(129), "sdk")))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThat(unitCount("p6a-sdk")).isZero();
    }

    @Test
    void a_lib_with_valid_coordinates_stores_them_and_exposes_them_in_the_view() {
        long id = unitAppService.create(createCmd("p6a-sdk", "LIB", "com.sf.quota", "quota-sdk"));

        UnitView view = unitAppService.get(id);
        assertThat(view.type()).isEqualTo("LIB");
        assertThat(view.artifactGroupId()).isEqualTo("com.sf.quota");
        assertThat(view.artifactArtifactId()).isEqualTo("quota-sdk");
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT artifact_group_id, artifact_artifact_id FROM deploy_unit WHERE id = ?", id);
        assertThat(row.get("artifact_group_id")).isEqualTo("com.sf.quota");
        assertThat(row.get("artifact_artifact_id")).isEqualTo("quota-sdk");
    }

    @Test
    void a_unit_ignores_the_coordinate_fields() {
        long id = unitAppService.create(
            createCmd("p6a-api", "UNIT", "com.sf.ignored", "ignored-artifact"));

        UnitView view = unitAppService.get(id);
        assertThat(view.type()).isEqualTo("UNIT");
        assertThat(view.artifactGroupId()).isNull();
        assertThat(view.artifactArtifactId()).isNull();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT artifact_group_id, artifact_artifact_id FROM deploy_unit WHERE id = ?", id);
        assertThat(row.get("artifact_group_id")).isNull();
        assertThat(row.get("artifact_artifact_id")).isNull();
    }

    @Test
    void updating_a_lib_requires_and_can_change_the_coordinates() {
        long id = unitAppService.create(createCmd("p6a-sdk", "LIB", "com.sf.quota", "quota-sdk"));

        assertThatThrownBy(() -> unitAppService.update(id, updateCmd("p6a-sdk", "LIB", null, null)))
            .isInstanceOfSatisfying(BizException.class, ex ->
                assertThat(ex.getError()).isEqualTo(BizError.PARAM_INVALID));
        assertThat(unitAppService.get(id).artifactArtifactId())
            .as("the rejected update changed nothing")
            .isEqualTo("quota-sdk");

        unitAppService.update(id, updateCmd("p6a-sdk", "LIB", "com.sf.new", "new-sdk"));
        UnitView updated = unitAppService.get(id);
        assertThat(updated.artifactGroupId()).isEqualTo("com.sf.new");
        assertThat(updated.artifactArtifactId()).isEqualTo("new-sdk");
    }

    @Test
    void updating_a_unit_ignores_coordinate_fields_even_if_sent() {
        long id = unitAppService.create(createCmd("p6a-api", "UNIT", null, null));

        unitAppService.update(id, updateCmd("p6a-api", "UNIT", "com.sf.ignored", "ignored"));

        UnitView view = unitAppService.get(id);
        assertThat(view.artifactGroupId()).isNull();
        assertThat(view.artifactArtifactId()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private CreateCmd createCmd(String name, String type, String groupId, String artifactId) {
        return new CreateCmd(subsystemId, name, type, null, LibFixtures.OWNER, null, null, null,
            null, null, null, null, null, null, groupId, artifactId);
    }

    private UpdateCmd updateCmd(String name, String type, String groupId, String artifactId) {
        return new UpdateCmd(name, type, null, LibFixtures.OWNER, null, null, null, null, null,
            null, null, null, null, null, groupId, artifactId);
    }

    private int unitCount(String name) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deploy_unit WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    private static Map<String, Object> payload(BizException ex) {
        assertThat(ex.getPayload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
        return payload;
    }
}