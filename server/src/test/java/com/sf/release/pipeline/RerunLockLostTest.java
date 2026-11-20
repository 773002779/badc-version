package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.RerunAppService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 重新发布 lock-lost path (I5): {@code bindInstance} reporting a lost row must not release
 * the {@code deploy_unit_lock} sentinel. Releasing would delete a row that now belongs to
 * another holder (or to nobody), i.e. it would free somebody else's deployment unit — the same
 * rule the publish path already follows.
 *
 * <p>The test replaces only {@code bindInstance} through a spy so the "lock lost" result is
 * deterministic without a real race: {@code acquire} still inserts the sentinel row, the rerun
 * transaction rolls back, and the assertion is that the row is still there.
 */
@SpringBootTest(properties = "release.rerun-locklost-probe=fresh-context")
@ActiveProfiles("test")
class RerunLockLostTest {

    private static final String OWNER = "01388007";
    private static final String BRANCH = "release/202611";
    private static final String BASE = "a".repeat(40);
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final int UNIT_LOCKED_CODE = 40006;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired RerunAppService rerunService;
    @Autowired JdbcTemplate jdbc;
    @SpyBean UnitLockService unitLockService;
    @MockBean GitCommitProbe commitProbe;

    private long unitId;
    private long itemId;
    private long publishedInstanceId;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-LOCKLOST");
        long taskId = givenTask(subsystemId, "202611-LOCKLOST");
        unitId = givenUnit(subsystemId, "locklost-api");
        givenBinding(unitId, "BUILD", "P-BUILD-LOCKLOST");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-LOCKLOST");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-LOCKLOST");
        givenDe(unitId, "TEST", "locklost-sit", 0);
        givenDe(unitId, "PROD", "locklost-prod", 1);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "锁丢失", PLAN_DATE));

        PublishResult published = publishService.publish(itemId);
        publishedInstanceId = instanceId(published.pipelineNo());
        // Turn the published instance into a failed one a rerun may resume from.
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", "sha256:locklost", BASE, publishedInstanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS' WHERE instance_id = ?"
            + " AND node_type = 'BUILD'", publishedInstanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'FAILED' WHERE instance_id = ?"
            + " AND node_type = 'TEST_DEPLOY'", publishedInstanceId);
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ?,"
            + " last_test_pass_by = 'tester', last_test_pass_at = NOW(3) WHERE id = ?",
            BASE, itemId);
        assertThat(unitLockService.release(unitId, publishedInstanceId)).isTrue();
        when(commitProbe.headSha(unitId, BRANCH)).thenReturn(BASE);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    @Test
    void a_lost_lock_bind_is_reported_but_never_released() {
        // The fixture released the published instance's lock; only the rerun's own calls count.
        clearInvocations(unitLockService);
        doReturn(false).when(unitLockService).bindInstance(anyLong(), anyLong());

        assertThatThrownBy(() -> rerunService.rerun(itemId, false))
            .isInstanceOf(BizException.class)
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.UNIT_LOCKED);
                assertThat(ex.getError().getCode()).isEqualTo(UNIT_LOCKED_CODE);
            });

        verify(unitLockService, never()).release(anyLong(), anyLong());
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", unitId);
        assertThat(((Number) row.get("instance_id")).longValue())
            .as("the sentinel row is left alone: a release could free a new holder's unit")
            .isZero();
        assertThat(count("pipeline_instance"))
            .as("the rerun transaction rolled back, so no half-created instance survives")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId)).isEqualTo("FAILED");
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private long instanceId(String pipelineNo) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/locklost', NOW(3), NOW(3))", subsystem, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, codingTaskId);
    }

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }
}