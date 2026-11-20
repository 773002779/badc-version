package com.sf.release.resource;

import com.sf.release.application.UnitAppService;
import com.sf.release.application.UnitAppService.BindingCmd;
import com.sf.release.application.UnitAppService.CreateCmd;
import com.sf.release.application.UnitAppService.EnvDeCmd;
import com.sf.release.application.UnitAppService.EnvDeView;
import com.sf.release.application.UnitAppService.PageQuery;
import com.sf.release.application.UnitAppService.PageResult;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deployment units, CODING task bindings, environment DEs and the data-scope filter
 * against the real test schema (Flyway migrate, MyBatis-Plus, MySQL 8).
 *
 * <p>Data scope is installed with {@link TestUserContext}; the HTTP seam of the
 * interceptor itself is already covered by {@code PermissionInterceptorTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class UnitServiceTest {

    private static final String OWNER = "01388007";
    private static final String INACTIVE = "01399999";
    private static final int PARAM_INVALID_CODE = 40000;
    private static final int UNIT_NOT_CONFIGURED_CODE = 40011;

    @Autowired UnitAppService unitService;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits("t5-admin");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", OWNER, INACTIVE);
        givenUser(OWNER, "ACTIVE");
        givenUser(INACTIVE, "INACTIVE");
        subsystemId = givenSubsystem("SCC-QUOTA");
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void create_stores_unit_and_rejects_unknown_subsystem() {
        long id = unitService.create(new CreateCmd(
            subsystemId, "quota-api", null, "DU-QUOTA", OWNER, "spring-boot",
            "COMPATIBLE", " 配额服务 ", "outbound-os", "K8S", "IMAGE", "quota-api:latest"));

        UnitView view = unitService.get(id);
        assertThat(view.subsystemId()).isEqualTo(subsystemId);
        assertThat(view.name()).isEqualTo("quota-api");
        assertThat(view.type()).isEqualTo("UNIT");
        assertThat(view.du()).isEqualTo("DU-QUOTA");
        assertThat(view.ownerId()).isEqualTo(OWNER);
        assertThat(view.frameworkTemplate()).isEqualTo("spring-boot");
        assertThat(view.branchMode()).isEqualTo("COMPATIBLE");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.description()).isEqualTo("配额服务");
        // Task 15 compares commits inside the module of a shared repository.
        assertThat(view.modulePath()).isEqualTo("outbound-os");
        assertThat(view.deployType()).isEqualTo("K8S");
        assertThat(view.imageType()).isEqualTo("IMAGE");
        assertThat(view.imageName()).isEqualTo("quota-api:latest");
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.updatedAt()).isNotNull();

        // A LIB unit is allowed too (shared shape, no deploy/env config in phase 1).
        long lib = unitService.create(new CreateCmd(
            subsystemId, "quota-sdk", "LIB", null, OWNER, null, null, null, null, null, null, null));
        assertThat(unitService.get(lib).type()).isEqualTo("LIB");
        assertThatThrownBy(() -> unitService.addEnvDe(lib, env("TEST", "quota-sdk-sit", 0)))
            .as("FR-R-03: a second-party library has no deployment environment")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.STATE_NOT_ALLOWED);

        assertThatThrownBy(() -> unitService.create(new CreateCmd(999_999L, "ghost-api", OWNER)))
            .as("unknown subsystem")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(PARAM_INVALID_CODE));

        assertThatThrownBy(() -> unitService.create(new CreateCmd(
            subsystemId, "quota-bad-owner", null, null, INACTIVE, null, null, null, null, null, null, null)))
            .as("owner must be an ACTIVE employee")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> unitService.create(new CreateCmd(
            subsystemId, "quota-api", null, null, OWNER, null, null, null, null, null, null, null)))
            .as("unit name is unique inside a subsystem")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.DUPLICATE_NAME);
    }

    @Test
    void update_replaces_editable_fields_and_keeps_subsystem() {
        long id = unitService.create(cmd("quota-api"));

        unitService.update(id, new UpdateCmd(" quota-api-v2 ", "LIB", "DU-QUOTA", OWNER,
            "spring-boot", "COMPATIBLE", "INACTIVE", " 配额服务 ", "outbound-os",
            "K8S", "IMAGE", "quota-api:v2"));

        UnitView view = unitService.get(id);
        assertThat(view.name()).isEqualTo("quota-api-v2");
        assertThat(view.type()).isEqualTo("LIB");
        assertThat(view.du()).isEqualTo("DU-QUOTA");
        assertThat(view.branchMode()).isEqualTo("COMPATIBLE");
        assertThat(view.status()).isEqualTo("INACTIVE");
        assertThat(view.description()).isEqualTo("配额服务");
        assertThat(view.modulePath()).isEqualTo("outbound-os");
        assertThat(view.imageName()).isEqualTo("quota-api:v2");
        // The subsystem link is fixed once created.
        assertThat(view.subsystemId()).isEqualTo(subsystemId);

        assertThatThrownBy(() -> unitService.update(999_999L, new UpdateCmd("ghost", OWNER)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void publishable_requires_build_test_and_prod_deploy() {
        long id = unitService.create(cmd("quota-api"));

        assertThatThrownBy(() -> unitService.assertPublishable(id))
            .as("no CODING task configured at all")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED)
            .satisfies(error -> assertThat(((BizError) error).getCode())
                .isEqualTo(UNIT_NOT_CONFIGURED_CODE));

        unitService.bindCodingTasks(id, List.of(build("P-BUILD"), testDeploy("P-TEST")));
        assertThatThrownBy(() -> unitService.assertPublishable(id))
            .as("BUILD + TEST_DEPLOY without PROD_DEPLOY is not publishable")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED);

        // Binding accumulates: PROD_DEPLOY is added, the first two stay.
        unitService.bindCodingTasks(id, List.of(prodDeploy("P-PROD")));
        assertThatCode(() -> unitService.assertPublishable(id)).doesNotThrowAnyException();
        assertThat(countBindings(id)).isEqualTo(3);
        assertThat(taskId(id, "BUILD")).isEqualTo("P-BUILD");
        assertThat(taskId(id, "TEST_DEPLOY")).isEqualTo("P-TEST");
        assertThat(taskId(id, "PROD_DEPLOY")).isEqualTo("P-PROD");
    }

    @Test
    void daily_deploy_is_optional_and_defaults_to_test_deploy() {
        long id = unitService.create(cmd("quota-api"));
        unitService.bindCodingTasks(id, List.of(
            build("P-BUILD"), testDeploy("P-TEST"), prodDeploy("P-PROD")));

        assertThat(unitService.resolveDailyDeployPlan(id)).isEqualTo("P-TEST");

        unitService.bindCodingTasks(id, List.of(dailyDeploy("P-DAILY")));
        assertThat(unitService.resolveDailyDeployPlan(id)).isEqualTo("P-DAILY");

        // Without either TEST_DEPLOY or DAILY_DEPLOY there is nothing to resolve.
        long bare = unitService.create(cmd("quota-worker"));
        unitService.bindCodingTasks(bare, List.of(build("P-BUILD")));
        assertThatThrownBy(() -> unitService.resolveDailyDeployPlan(bare))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.UNIT_NOT_CONFIGURED);
    }

    @Test
    void duplicate_task_kind_is_rejected_and_rebinding_updates_in_place() {
        long id = unitService.create(cmd("quota-api"));
        unitService.bindCodingTasks(id, List.of(build("P-BUILD")));

        assertThatThrownBy(() -> unitService.bindCodingTasks(id,
            List.of(build("P-BUILD-2"), build("P-BUILD-3"))))
            .as("the same (unit_id, task_kind) may appear only once per bind request")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(PARAM_INVALID_CODE));

        assertThat(countBindings(id)).isEqualTo(1);
        assertThat(taskId(id, "BUILD")).isEqualTo("P-BUILD");

        // The unique key uk_unit_kind is the database-level guard as well.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO coding_task_binding (unit_id, task_kind, task_id, created_at, updated_at)"
                + " VALUES (?, 'BUILD', 'P-DIRECT', NOW(3), NOW(3))", id))
            .isInstanceOf(DuplicateKeyException.class);

        // Re-binding the same kind is an upsert, not a duplicate.
        unitService.bindCodingTasks(id,
            List.of(new BindingCmd("BUILD", "P-BUILD-4", "{\"profile\":\"prod\"}")));
        assertThat(countBindings(id)).isEqualTo(1);
        assertThat(taskId(id, "BUILD")).isEqualTo("P-BUILD-4");
        assertThat(jdbc.queryForObject(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.profile'))"
                + " FROM coding_task_binding WHERE unit_id = ? AND task_kind = 'BUILD'",
            String.class, id)).isEqualTo("prod");

        assertThatThrownBy(() -> unitService.bindCodingTasks(id,
            List.of(new BindingCmd("BUILD", "P-BUILD-5", "{not-json"))))
            .as("params_json must be valid JSON")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> unitService.bindCodingTasks(id,
            List.of(new BindingCmd("DEPLOY", "P-UNKNOWN"))))
            .as("task_kind must be one of the four supported kinds")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);

        assertThatThrownBy(() -> unitService.bindCodingTasks(999_999L, List.of(build("P-BUILD"))))
            .as("bindings belong to an existing unit")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void env_de_is_ordered_by_deploy_batch_no() {
        long id = unitService.create(cmd("quota-api"));

        unitService.addEnvDe(id, env("PROD", "cwsp-quota-api-prod", 20));
        unitService.addEnvDe(id, env("DR", "cwsp-quota-api-dr", 10));
        unitService.addEnvDe(id, env("PROD", "cwsp-quota-api-prod-a", 0));
        unitService.addEnvDe(id, env("TEST", "cwsp-quota-api-sit", 0));

        List<EnvDeView> des = unitService.listEnvDe(id);
        assertThat(des).extracting(EnvDeView::deployBatchNo).containsExactly(0, 0, 10, 20);
        assertThat(des).extracting(EnvDeView::deName)
            .containsExactly("cwsp-quota-api-prod-a", "cwsp-quota-api-sit",
                "cwsp-quota-api-dr", "cwsp-quota-api-prod");
        assertThat(des).extracting(EnvDeView::envKind)
            .containsExactly("PROD", "TEST", "DR", "PROD");

        // The batch number participates in ordering and is NOT NULL DEFAULT 0.
        assertThat(jdbc.queryForObject(
            "SELECT IS_NULLABLE FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'env_de'"
                + " AND column_name = 'deploy_batch_no'", String.class)).isEqualTo("NO");
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, created_at, updated_at)"
            + " VALUES (?, 'TEST', 'legacy-sit', NOW(3), NOW(3))", id);
        assertThat(jdbc.queryForObject(
            "SELECT deploy_batch_no FROM env_de WHERE de_name = 'legacy-sit'", Integer.class))
            .isZero();

        assertThatThrownBy(() -> unitService.addEnvDe(id, env("BROKEN", "bad", 0)))
            .as("env_kind is a closed vocabulary")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> unitService.addEnvDe(id, env("PROD", "negative", -1)))
            .as("a negative batch number is invalid")
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
        assertThatThrownBy(() -> unitService.addEnvDe(999_999L, env("PROD", "ghost", 0)))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.PARAM_INVALID);
    }

    @Test
    void unit_list_is_filtered_by_data_scope() {
        long unitA = unitService.create(cmd("quota-api"));
        long unitB = unitService.create(cmd("quota-worker"));
        unitService.addEnvDe(unitA, env("TEST", "quota-api-sit", 0));

        TestUserContext.scoped("u-dev", unitA);
        PageResult<UnitView> onlyA = unitService.page(new PageQuery(1, 10));
        assertThat(onlyA.total()).isEqualTo(1);
        assertThat(onlyA.records()).extracting(UnitView::id).containsExactly(unitA);
        assertThat(unitService.page(new PageQuery(1, 10, "worker")).total())
            .as("the keyword never widens the scope")
            .isZero();

        // Detail and unit-scoped child reads are fail-closed too.
        assertThatThrownBy(() -> unitService.get(unitB))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.FORBIDDEN)
            .satisfies(error -> assertThat(((BizError) error).getCode()).isEqualTo(40009));
        assertThatThrownBy(() -> unitService.listEnvDe(unitB))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.FORBIDDEN);

        TestUserContext.noUnits("u-no-scope");
        PageResult<UnitView> none = unitService.page(new PageQuery(1, 10));
        assertThat(none.total()).isZero();
        assertThat(none.records()).isEmpty();
        assertThatThrownBy(() -> unitService.get(unitA))
            .isInstanceOf(BizException.class)
            .extracting("error").isEqualTo(BizError.FORBIDDEN);

        TestUserContext.allUnits("u-admin");
        assertThat(unitService.page(new PageQuery(1, 10)).total()).isEqualTo(2);
    }

    private CreateCmd cmd(String name) {
        return new CreateCmd(subsystemId, name, OWNER);
    }

    private static BindingCmd build(String taskId) {
        return new BindingCmd("BUILD", taskId);
    }

    private static BindingCmd testDeploy(String taskId) {
        return new BindingCmd("TEST_DEPLOY", taskId);
    }

    private static BindingCmd prodDeploy(String taskId) {
        return new BindingCmd("PROD_DEPLOY", taskId);
    }

    private static BindingCmd dailyDeploy(String taskId) {
        return new BindingCmd("DAILY_DEPLOY", taskId);
    }

    private static EnvDeCmd env(String envKind, String deName, int deployBatchNo) {
        return new EnvDeCmd(envKind, deName, deployBatchNo);
    }

    private int countBindings(long unitId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM coding_task_binding WHERE unit_id = ?", Integer.class, unitId);
        return count == null ? 0 : count;
    }

    private String taskId(long unitId, String taskKind) {
        return jdbc.queryForObject(
            "SELECT task_id FROM coding_task_binding WHERE unit_id = ? AND task_kind = ?",
            String.class, unitId, taskKind);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String status) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, ?, NOW(3))",
            userId, "用户" + userId, status);
    }
}