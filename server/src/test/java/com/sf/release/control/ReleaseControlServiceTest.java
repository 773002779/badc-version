package com.sf.release.control;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.ReleaseControlService;
import com.sf.release.application.ReleaseControlService.ArchiveResult;
import com.sf.release.application.ReleaseControlService.ControlResult;
import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.RerunAppService;
import com.sf.release.application.RollbackService;
import com.sf.release.application.RollbackVersionView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stop / exit / close / archive use cases against the real schema (P5-B design §1–§5, §7):
 *
 * <ul>
 *   <li><strong>stop</strong> (7.1): a READY/PAUSED production release and a PROD_DEPLOYING release
 *       mid-batch both end {@code TERMINATED}; the successful batch is kept, the unfinished tasks are
 *       skipped, the fake engine's {@code cancel} is called and a cancel failure is only a WARN;</li>
 *   <li><strong>exit</strong> (7.2): TESTING / GATE_PENDING / a paused instance all end
 *       {@code TERMINATED}; pending approvals become {@code CANCELED}, unfinished checks
 *       {@code INVALID}, the unit lock is released;</li>
 *   <li><strong>close</strong> (7.3): an approval-waiting item becomes {@code CLOSED}, a second
 *       close and a {@code DONE} item answer 40001, a LIB item may close;</li>
 *   <li><strong>archive</strong> (7.4): {@code DONE} stays {@code DONE}, the READY item becomes
 *       {@code CLOSED}, the task {@code ARCHIVED}; item creation/publish/daily/rerun answer 40001;
 *       the release record and the rollback version stay readable and rollback still executes; all
 *       locks are released;</li>
 *   <li><strong>idempotency/illegal</strong> (7.5), <strong>permission and scope</strong> (7.6) and
 *       <strong>audit/notification</strong> (7.7), including the failure isolation of the last two.</li>
 * </ul>
 *
 * <p>Package {@code com.sf.release.control}, not {@code ..web..} (design §7 item 8).
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    ReleaseControlServiceTest.StubEngineConfig.class})
@ActiveProfiles("test")
class ReleaseControlServiceTest {

    private static final String OWNER = "ctrl-owner";
    private static final String DEV = "ctrl-dev";
    private static final String TESTER = "ctrl-tester";
    private static final String OPS = "ctrl-ops";
    private static final String PRODUCT = "ctrl-product";
    private static final String STOPPER = "ctrl-stopper";
    private static final String NOBODY = "ctrl-nobody";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String REASON = "线上故障止损，停止后续批次";

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired DailyReleaseService dailyService;
    @Autowired RerunAppService rerunService;
    @Autowired ReleaseControlService control;
    @Autowired ReleaseQueryService queryService;
    @Autowired RollbackService rollbackService;
    @Autowired UnitLockService unitLockService;
    @Autowired StubEngine engine;
    @Autowired JdbcTemplate jdbc;
    @MockBean GitCommitProbe commitProbe;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long testDe;
    private long prodDe;
    private long drDe;
    private long blueDe;

    @BeforeEach
    void setUp() {
        InMemoryDistributedLock.clearAllLeases();
        engine.clear();
        engine.failCancel(false);
        ControlFixtures.clean(jdbc);
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of());

        ControlFixtures.givenUser(jdbc, OWNER);
        ControlFixtures.givenUser(jdbc, DEV, "DEV");
        ControlFixtures.givenUser(jdbc, TESTER, "TEST");
        ControlFixtures.givenUser(jdbc, OPS, "OPS");
        ControlFixtures.givenUser(jdbc, PRODUCT, "PRODUCT");
        ControlFixtures.givenUser(jdbc, STOPPER, "OPS");
        ControlFixtures.givenUser(jdbc, NOBODY);

        subsystemId = ControlFixtures.givenSubsystem(jdbc, "SCC-CONTROL", OWNER);
        taskId = ControlFixtures.givenTask(jdbc, subsystemId, "202611-CONTROL", OWNER);
        ControlFixtures.givenMember(jdbc, taskId, DEV, "DEV");
        ControlFixtures.givenMember(jdbc, taskId, TESTER, "TEST");
        ControlFixtures.givenMember(jdbc, taskId, OPS, "OPS");
        ControlFixtures.givenMember(jdbc, taskId, PRODUCT, "PRODUCT");

        unitId = ControlFixtures.givenUnit(jdbc, subsystemId, "control-api", OWNER);
        ControlFixtures.givenBinding(jdbc, unitId, "BUILD", "P-BUILD-CONTROL");
        ControlFixtures.givenBinding(jdbc, unitId, "TEST_DEPLOY", "P-TEST-CONTROL");
        ControlFixtures.givenBinding(jdbc, unitId, "PROD_DEPLOY", "P-PROD-CONTROL");
        ControlFixtures.givenBinding(jdbc, unitId, "DAILY_DEPLOY", "P-DAILY-CONTROL");
        jdbc.update("UPDATE coding_task_binding SET params_json = '{\"branch\":\"release/202611\"}'"
            + " WHERE unit_id = ? AND task_kind = 'BUILD'", unitId);
        testDe = ControlFixtures.givenDe(jdbc, unitId, "TEST", "control-sit", 0);
        prodDe = ControlFixtures.givenDe(jdbc, unitId, "PROD", "control-prod-1", 1);
        drDe = ControlFixtures.givenDe(jdbc, unitId, "DR", "control-dr-2", 2);
        blueDe = ControlFixtures.givenDe(jdbc, unitId, "BLUE", "control-blue-3", 3);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ stop (7.1)

    @Test
    void stop_a_ready_paused_release_terminates_the_instance_and_the_item_and_keeps_the_checks() {
        Published published = publish("release/stop-ready");
        setItemStatus(published.itemId(), "READY");
        setGateStatus(published.itemId(), "CHECK_PASS");
        pauseAtProdDeploy(published.instanceId());
        assertThat(unitLockCount()).as("the setup still holds the unit lock").isEqualTo(1);

        asStopper();
        ControlResult result = control.stop(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
        assertThat(result.status()).isEqualTo("TERMINATED");
        assertThat(result.terminatedPipelineNo()).isEqualTo(published.pipelineNo());
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(statusReason(published.instanceId())).isEqualTo(REASON);
        assertThat(endAt(published.instanceId())).isNotNull();
        assertThat(itemStatus(published.itemId())).isEqualTo("TERMINATED");
        assertThat(orderStatus(published.publishNo())).isEqualTo("TERMINATED");
        assertThat(nodeStatus(published.instanceId(), "PROD_DEPLOY")).isEqualTo("SKIPPED");
        assertThat(unitLockCount()).as("the unit lock is released after the commit").isZero();
        assertThat(gateStatus(published.itemId()))
            .as("a stop keeps what was tested: it does not invalidate the checks")
            .isEqualTo("CHECK_PASS");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT actor, action, object_type, object_id, scope_unit_id, reason"
                + " FROM audit_log WHERE action = 'STOP'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("object_type")).isEqualTo("ORDER");
        assertThat(String.valueOf(rows.get(0).get("object_id")))
            .isEqualTo(published.publishNo());
        assertThat(rows.get(0).get("reason")).isEqualTo(REASON);
        assertThat(((Number) rows.get(0).get("scope_unit_id")).longValue()).isEqualTo(unitId);

        assertThat(recipientsOf("RELEASE_STOPPED", published.publishNo()))
            .contains(DEV, OPS, OWNER);
        assertThat(jdbc.queryForObject("SELECT object_type FROM notification WHERE event_key ="
            + " 'RELEASE_STOPPED' LIMIT 1", String.class)).isEqualTo("ORDER");
        assertThat(jdbc.queryForObject("SELECT body FROM notification WHERE event_key ="
            + " 'RELEASE_STOPPED' LIMIT 1", String.class))
            .contains("已上线批次保留").contains(REASON);
    }

    @Test
    void stop_mid_prod_deploy_keeps_the_successful_batch_and_cancels_the_in_flight_one() {
        Published published = publish("release/stop-deploying");
        runningProdDeploy(published.itemId(), published.instanceId());
        // Batch 1 already went online; batch 2 is running in the engine; batch 3 never started.
        setDeployTask(published.instanceId(), prodDe, "SUCCESS", null);
        setDeployTask(published.instanceId(), drDe, "WAITING_EXTERNAL", "job-dr-2");

        asStopper();
        ControlResult result = control.stop(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(deployTaskStatus(published.instanceId(), prodDe))
            .as("an already deployed batch is never recalled (J8/J21)").isEqualTo("SUCCESS");
        assertThat(deployTaskStatus(published.instanceId(), drDe)).isEqualTo("SKIPPED");
        assertThat(deployTaskStatus(published.instanceId(), blueDe)).isEqualTo("SKIPPED");
        assertThat(deployTaskSummary(published.instanceId(), drDe))
            .contains("已取消").contains(REASON);
        assertThat(engine.cancelled()).as("the in-flight engine job is cancelled")
            .containsExactly("job-dr-2");
        assertThat(nodeStatus(published.instanceId(), "PROD_DEPLOY")).isEqualTo("SKIPPED");
        assertThat(unitLockCount()).isZero();
    }

    @Test
    void stop_an_engine_cancel_failure_is_only_a_warning() {
        Published published = publish("release/stop-cancel-fail");
        runningProdDeploy(published.itemId(), published.instanceId());
        setDeployTask(published.instanceId(), drDe, "WAITING_EXTERNAL", "job-broken");
        engine.failCancel(true);

        asStopper();
        ControlResult result = control.stop(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(itemStatus(published.itemId())).isEqualTo("TERMINATED");
    }

    @Test
    void stop_refuses_a_state_outside_the_production_flow_with_current_status() {
        Published published = publish("release/stop-building");

        asStopper();
        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> control.stop(published.publishNo(), REASON), "BUILDING");
    }

    @Test
    void stop_is_idempotent_in_the_refusing_sense() {
        Published published = publish("release/stop-twice");
        setItemStatus(published.itemId(), "READY");
        pauseAtProdDeploy(published.instanceId());
        asStopper();
        control.stop(published.publishNo(), REASON);

        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> control.stop(published.publishNo(), REASON), "TERMINATED");
    }

    @Test
    void stop_requires_the_execute_prod_permission_and_the_unit_scope() {
        Published published = publish("release/stop-perm");

        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        assertBizError(BizError.FORBIDDEN,
            () -> control.stop(published.publishNo(), REASON), null);

        TestUserContext.scopedWithPerms(STOPPER, Set.of(Permissions.CONTROL_EXECUTE_PROD),
            unitId + 1_000L);
        assertBizError(BizError.FORBIDDEN,
            () -> control.stop(published.publishNo(), REASON), null);
    }

    @Test
    void stop_rejects_an_unknown_publish_number_and_a_blank_reason() {
        asStopper();
        assertBizError(BizError.PARAM_INVALID, () -> control.stop("PO-UNKNOWN", REASON), null);

        Published published = publish("release/stop-reason");
        asStopper();
        assertBizError(BizError.PARAM_INVALID,
            () -> control.stop(published.publishNo(), "  "), null);
        assertBizError(BizError.PARAM_INVALID,
            () -> control.stop(published.publishNo(), "x".repeat(201)), null);
    }

    // ------------------------------------------------------------------ exit (7.2)

    @Test
    void exit_a_testing_release_voids_the_pending_approvals_and_invalidates_unfinished_checks() {
        Published published = publish("release/exit-testing");
        long orderId = orderIdOf(published.publishNo());
        setItemStatus(published.itemId(), "TESTING");
        runningVerify(published.instanceId());
        long snapshotId = ControlFixtures.givenSnapshot(jdbc, orderId, published.itemId(), DEV);
        ControlFixtures.givenApproval(jdbc, orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        ControlFixtures.givenApproval(jdbc, orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        ControlFixtures.givenGateCheck(jdbc, published.itemId(), "SONAR", "IMAGE", "RUNNING");
        ControlFixtures.givenGateCheck(jdbc, published.itemId(), "VULN", "IMAGE", "PENDING");
        ControlFixtures.givenGateCheck(jdbc, published.itemId(), "POLICY", "ENV", "PASS");
        assertThat(unitLockCount()).isEqualTo(1);

        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        ControlResult result = control.exit(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
        assertThat(result.terminatedPipelineNo()).isEqualTo(published.pipelineNo());
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(itemStatus(published.itemId())).isEqualTo("TERMINATED");
        assertThat(orderStatus(published.publishNo())).isEqualTo("TERMINATED");
        assertThat(unitLockCount()).isZero();

        assertThat(jdbc.queryForList("SELECT status FROM approval WHERE snapshot_id = ?",
            String.class, snapshotId)).containsOnly("CANCELED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE decided_at IS NOT NULL",
            Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT comment FROM approval WHERE step_no = 1",
            String.class)).isEqualTo(REASON);
        assertThat(jdbc.queryForObject("SELECT decided_by FROM approval WHERE step_no = 1",
            String.class)).as("nobody signed a voided step").isNull();

        assertThat(jdbc.queryForObject("SELECT status FROM gate_check WHERE check_kind = 'SONAR'",
            String.class)).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT status FROM gate_check WHERE check_kind = 'VULN'",
            String.class)).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT status FROM gate_check WHERE check_kind = 'POLICY'",
            String.class)).as("a recorded result is history and stays").isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT summary FROM gate_check WHERE check_kind = 'SONAR'",
            String.class)).isEqualTo(REASON);
        assertThat(gateStatus(published.itemId())).isEqualTo("CHECK_INVALID");

        assertThat(auditActions("EXIT")).hasSize(1);
        assertThat(recipientsOf("RELEASE_EXITED", published.publishNo()))
            .contains(DEV, OPS, OWNER);
    }

    @Test
    void exit_works_on_a_gate_pending_release_whose_instance_is_paused() {
        Published published = publish("release/exit-gate-pending");
        setItemStatus(published.itemId(), "GATE_PENDING");
        pauseAtProdDeploy(published.instanceId());

        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        ControlResult result = control.exit(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(unitLockCount()).isZero();
    }

    @Test
    void exit_is_allowed_for_operations_when_they_hold_execute_prod_but_not_release_publish() {
        Published published = publish("release/exit-ops");
        setItemStatus(published.itemId(), "TESTING");

        TestUserContext.as(STOPPER, Set.of("OPS"), Set.of(Permissions.CONTROL_EXECUTE_PROD));
        ControlResult result = control.exit(published.publishNo(), REASON);

        assertThat(result.itemStatus()).isEqualTo("TERMINATED");
    }

    @Test
    void exit_is_forbidden_without_either_permission() {
        Published published = publish("release/exit-nobody");
        TestUserContext.as(NOBODY, Set.of(), Set.of());
        assertBizError(BizError.FORBIDDEN, () -> control.exit(published.publishNo(), REASON),
            null);
    }

    @Test
    void exit_refuses_a_done_item_and_an_already_terminal_item() {
        Published done = publish("release/exit-done");
        setItemStatus(done.itemId(), "DONE");
        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        assertBizError(BizError.STATE_NOT_ALLOWED, () -> control.exit(done.publishNo(), REASON),
            "DONE");

        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_PUBLISH));
        setItemStatus(done.itemId(), "CLOSED");
        assertBizError(BizError.STATE_NOT_ALLOWED, () -> control.exit(done.publishNo(), REASON),
            "CLOSED");
    }

    // ------------------------------------------------------------------ close (7.3)

    @Test
    void close_an_approval_waiting_item_cascades_and_notifies_the_task_roles() {
        Published published = publish("release/close-gate-pending");
        long orderId = orderIdOf(published.publishNo());
        setItemStatus(published.itemId(), "GATE_PENDING");
        pauseAtProdDeploy(published.instanceId());
        long snapshotId = ControlFixtures.givenSnapshot(jdbc, orderId, published.itemId(), DEV);
        ControlFixtures.givenApproval(jdbc, orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
        ControlFixtures.givenGateCheck(jdbc, published.itemId(), "SONAR", "IMAGE", "PENDING");

        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        ControlResult result = control.close(published.itemId(), REASON);

        assertThat(result.itemStatus()).isEqualTo("CLOSED");
        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(result.terminatedPipelineNo()).isEqualTo(published.pipelineNo());
        assertThat(itemStatus(published.itemId())).isEqualTo("CLOSED");
        assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        assertThat(orderStatus(published.publishNo())).isEqualTo("CLOSED");
        assertThat(unitLockCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE snapshot_id = ?",
            String.class, snapshotId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM gate_check WHERE item_id = ?",
            String.class, published.itemId())).isEqualTo("INVALID");
        assertThat(gateStatus(published.itemId())).isEqualTo("CHECK_INVALID");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT action, object_type, object_id, reason FROM audit_log WHERE action = 'CLOSE'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("object_type")).isEqualTo("ITEM");
        assertThat(String.valueOf(rows.get(0).get("object_id")))
            .isEqualTo(String.valueOf(published.itemId()));
        assertThat(rows.get(0).get("reason")).isEqualTo(REASON);

        // ITEM_CLOSED audience: DEV/TEST/OPS + the creator, not PRODUCT (design §5).
        assertThat(recipientsOf("ITEM_CLOSED", String.valueOf(published.itemId())))
            .contains(DEV, TESTER, OPS, OWNER).doesNotContain(PRODUCT);
    }

    @Test
    void close_refuses_a_second_call_and_a_done_item() {
        Published published = publish("release/close-twice");
        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        setItemStatus(published.itemId(), "GATE_PENDING");
        control.close(published.itemId(), REASON);

        assertBizError(BizError.STATE_NOT_ALLOWED, () -> control.close(published.itemId(), REASON),
            "CLOSED");

        Published done = publish("release/close-done");
        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        setItemStatus(done.itemId(), "DONE");
        assertBizError(BizError.STATE_NOT_ALLOWED, () -> control.close(done.itemId(), REASON),
            "DONE");
    }

    @Test
    void close_allows_a_library_item() {
        long library = ControlFixtures.givenItem(jdbc, taskId, unitId, "CR-LIB-CLOSE",
            "release/lib-close", "LIB", "PENDING", "CHECK_RUNNING");

        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
        ControlResult result = control.close(library, REASON);

        assertThat(result.itemStatus()).isEqualTo("CLOSED");
        assertThat(result.status()).as("a never published item has no order to mirror on").isNull();
        assertThat(result.terminatedPipelineNo()).isNull();
        assertThat(itemStatus(library)).isEqualTo("CLOSED");
        assertThat(auditActions("CLOSE")).hasSize(1);
    }

    @Test
    void close_requires_release_publish_and_an_existing_item() {
        long item = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/close-perm", "关闭权限", PLAN_DATE));

        TestUserContext.as(STOPPER, Set.of("OPS"), Set.of(Permissions.CONTROL_EXECUTE_PROD));
        assertBizError(BizError.FORBIDDEN, () -> control.close(item, REASON), null);

        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_PUBLISH));
        assertBizError(BizError.PARAM_INVALID, () -> control.close(9_999_999L, REASON), null);
    }

    @Test
    void a_failing_notification_never_reverts_a_close() {
        Published published = publish("release/close-notify-broken");
        setItemStatus(published.itemId(), "GATE_PENDING");
        jdbc.execute("RENAME TABLE notification TO notification_broken");
        try {
            TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.RELEASE_PUBLISH));
            ControlResult result = control.close(published.itemId(), REASON);

            assertThat(result.itemStatus()).isEqualTo("CLOSED");
            assertThat(itemStatus(published.itemId())).isEqualTo("CLOSED");
        } finally {
            jdbc.execute("RENAME TABLE notification_broken TO notification");
        }
    }

    // ------------------------------------------------------------------ archive (7.4)

    @Test
    void archive_closes_the_unfinished_items_and_archives_the_task() {
        ArchiveFixture fixture = archiveFixture("release/archive-main");

        asCreator();
        ArchiveResult result = control.archive(taskId, REASON);

        assertThat(result.taskStatus()).isEqualTo("ARCHIVED");
        assertThat(result.closedItems()).isEqualTo(1);
        assertThat(result.terminatedInstances()).isEqualTo(1);
        assertThat(taskStatus(taskId)).isEqualTo("ARCHIVED");
        assertThat(itemStatus(fixture.doneItem())).as("a DONE item stays DONE").isEqualTo("DONE");
        assertThat(itemStatus(fixture.failedItem()))
            .as("an already closed-out item is not reopened").isEqualTo("GATE_FAILED");
        assertThat(itemStatus(fixture.readyItem())).isEqualTo("CLOSED");
        assertThat(instanceStatus(fixture.readyInstance())).isEqualTo("TERMINATED");
        assertThat(unitLockCount()).as("all locks of the task are released").isZero();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT action, object_type, object_id, object_label, reason, after_json"
                + " FROM audit_log WHERE action = 'ARCHIVE'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("object_type")).isEqualTo("TASK");
        assertThat(String.valueOf(rows.get(0).get("object_id"))).isEqualTo(String.valueOf(taskId));
        assertThat(rows.get(0).get("reason")).isEqualTo(REASON);
        assertThat(String.valueOf(rows.get(0).get("after_json")))
            .contains("\"closedItems\"").contains("\"status\"").contains("ARCHIVED");

        assertThat(recipientsOf("TASK_ARCHIVED", String.valueOf(taskId)))
            .contains(DEV, TESTER, OPS, PRODUCT, OWNER);
        assertThat(jdbc.queryForObject("SELECT object_type FROM notification WHERE event_key ="
            + " 'TASK_ARCHIVED' LIMIT 1", String.class)).isEqualTo("TASK");
    }

    @Test
    void an_archived_task_refuses_new_items_publish_daily_and_rerun() {
        ArchiveFixture fixture = archiveFixture("release/archive-readonly");
        asCreator();
        control.archive(taskId, REASON);
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.ITEM_CREATE,
            Permissions.RELEASE_PUBLISH, Permissions.RELEASE_DAILY));

        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> itemService.createFromBinding(taskId, unitId, "v2", PLAN_DATE), "任务已归档");
        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> publishService.publish(fixture.readyItem()), "任务已归档");
        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> dailyService.start(fixture.readyItem()), "任务已归档");
        assertBizError(BizError.STATE_NOT_ALLOWED,
            () -> rerunService.rerun(fixture.readyItem()), "任务已归档");
    }

    @Test
    void an_archived_task_keeps_the_release_record_and_the_rollback_entry() {
        ArchiveFixture fixture = archiveFixture("release/archive-rollback");
        asCreator();
        control.archive(taskId, REASON);
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.ROLLBACK_EXECUTE));

        assertThat(queryService.detail(fixture.donePublishNo()).publishNo())
            .as("the release record is still readable").isEqualTo(fixture.donePublishNo());
        RollbackService.VersionList versions = rollbackService.versions(unitId);
        assertThat(versions.current().digest()).isEqualTo(fixture.currentDigest());
        assertThat(versions.history()).extracting(RollbackVersionView::digest)
            .contains(fixture.targetDigest());

        RollbackService.ExecuteResult executed = rollbackService.execute(unitId,
            fixture.targetDigest(), "归档后仍可回滚");
        assertThat(executed.rollbackId()).isPositive();
        assertThat(executed.pipelineNo()).startsWith("PL-");
        assertThat(jdbc.queryForObject("SELECT status FROM rollback_record WHERE id = ?",
            String.class, executed.rollbackId())).isEqualTo("RUNNING");
    }

    @Test
    void archive_terminates_an_in_flight_rollback_and_its_record_converges_to_failed() {
        ArchiveFixture fixture = archiveFixture("release/archive-rb-terminated");
        // The paused ready instance would have released its unit in reality (the orchestrator
        // releases the lease on a pause); free it so the rollback can take the unit.
        unitLockService.release(unitId, fixture.readyInstance());
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.ROLLBACK_EXECUTE));
        RollbackService.ExecuteResult executed = rollbackService.execute(unitId,
            fixture.targetDigest(), "先发起回滚");
        assertThat(jdbc.queryForObject("SELECT status FROM rollback_record WHERE id = ?",
            String.class, executed.rollbackId())).isEqualTo("RUNNING");
        jdbc.execute("RENAME TABLE audit_log TO audit_log_broken");
        try {
            asCreator();
            ArchiveResult result = control.archive(taskId, REASON);

            assertThat(result.taskStatus()).isEqualTo("ARCHIVED");
            assertThat(instanceStatus(instanceIdOf(executed.pipelineNo())))
                .isEqualTo("TERMINATED");
            assertThat(jdbc.queryForObject("SELECT status FROM rollback_record WHERE id = ?",
                String.class, executed.rollbackId())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT failure_reason FROM rollback_record WHERE id = ?",
                String.class, executed.rollbackId())).contains(REASON);
            assertThat(itemStatus(fixture.doneItem()))
                .as("a terminated rollback does not move the source item").isEqualTo("DONE");
        } finally {
            jdbc.execute("RENAME TABLE audit_log_broken TO audit_log");
        }
    }

    @Test
    void archive_refuses_a_second_call_and_unknown_or_unauthorized_tasks() {
        archiveFixture("release/archive-twice");
        asCreator();
        control.archive(taskId, REASON);

        assertBizError(BizError.STATE_NOT_ALLOWED, () -> control.archive(taskId, REASON),
            "ARCHIVED");

        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.TASK_CREATE));
        assertBizError(BizError.PARAM_INVALID, () -> control.archive(9_999_999L, REASON), null);

        long otherTask = ControlFixtures.givenTask(jdbc, subsystemId, "202611-CONTROL-OTHER",
            "someone-else");
        TestUserContext.as(DEV, Set.of("DEV"), Set.of(Permissions.TASK_CREATE));
        assertBizError(BizError.FORBIDDEN, () -> control.archive(otherTask, REASON), null);
    }

    @Test
    void archive_is_scoped_to_the_units_of_the_items_it_closes() {
        archiveFixture("release/archive-scope");

        TestUserContext.scopedWithPerms(OWNER, Set.of(Permissions.TASK_CREATE), unitId + 1_000L);
        assertBizError(BizError.FORBIDDEN, () -> control.archive(taskId, REASON), null);
        assertThat(taskStatus(taskId)).as("nothing was archived").isEqualTo("ACTIVE");
    }

    @Test
    void a_failing_audit_write_never_blocks_a_stop() {
        Published published = publish("release/stop-audit-broken");
        setItemStatus(published.itemId(), "READY");
        pauseAtProdDeploy(published.instanceId());
        jdbc.execute("RENAME TABLE audit_log TO audit_log_broken");
        try {
            asStopper();
            ControlResult result = control.stop(published.publishNo(), REASON);

            assertThat(result.itemStatus()).isEqualTo("TERMINATED");
            assertThat(instanceStatus(published.instanceId())).isEqualTo("TERMINATED");
        } finally {
            jdbc.execute("RENAME TABLE audit_log_broken TO audit_log");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record Published(long itemId, String publishNo, String pipelineNo, long instanceId) {
    }

    private record ArchiveFixture(long doneItem, String donePublishNo, long failedItem,
                                  long readyItem, long readyInstance, String currentDigest,
                                  String targetDigest) {
    }

    /**
     * The design's archive fixture (§7 item 4): a task with a {@code DONE} release (and a fully
     * deployed production version, so the rollback drawer has a current image), an older deployed
     * version of the same unit (the rollback target), a {@code READY} item whose pipeline is paused at
     * production and holds the unit lock, and an already closed-out {@code GATE_FAILED} item.
     */
    private ArchiveFixture archiveFixture(String branch) {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of());
        long doneItem = itemService.create(taskId,
            new CreateItemCmd(unitId, branch + "-done", "已完成", PLAN_DATE));
        String donePublishNo = "PO-CTRL-DONE";
        long doneOrder = ControlFixtures.givenOrder(jdbc, doneItem, donePublishNo, "DONE", OPS);
        String currentDigest = "sha256:control-current";
        long doneInstance = ControlFixtures.givenInstance(jdbc, doneOrder,
            "PL-CTRL-DONE", "DONE", "INITIAL", "PROD_DEPLOY", currentDigest);
        ControlFixtures.givenDeployedVersion(jdbc, doneInstance, prodDe, 1,
            Instant.now().minusSeconds(3600));
        setItemStatus(doneItem, "DONE");

        long targetItem = ControlFixtures.givenItem(jdbc, taskId, unitId,
            "CR-CTRL-TARGET", branch + "-old", "UNIT", "DONE", "CHECK_PASS");
        String targetPublishNo = "PO-CTRL-TARGET";
        long targetOrder = ControlFixtures.givenOrder(jdbc, targetItem, targetPublishNo, "DONE",
            OPS);
        String targetDigest = "sha256:control-target";
        long targetInstance = ControlFixtures.givenInstance(jdbc, targetOrder,
            "PL-CTRL-TARGET", "DONE", "INITIAL", "PROD_DEPLOY", targetDigest);
        ControlFixtures.givenDeployedVersion(jdbc, targetInstance, prodDe, 1,
            Instant.now().minusSeconds(7200));

        long failedItem = itemService.create(taskId,
            new CreateItemCmd(unitId, branch + "-failed", "已失败", PLAN_DATE));
        setItemStatus(failedItem, "GATE_FAILED");

        Published ready = publish(branch + "-ready");
        setItemStatus(ready.itemId(), "READY");
        pauseAtProdDeploy(ready.instanceId());
        return new ArchiveFixture(doneItem, donePublishNo, failedItem, ready.itemId(),
            ready.instanceId(), currentDigest, targetDigest);
    }

    private Published publish(String branch) {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.ITEM_CREATE,
            Permissions.RELEASE_PUBLISH));
        long itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, branch, "变更 " + branch, PLAN_DATE));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        long instanceId = instanceIdOf(published.pipelineNo());
        return new Published(itemId, published.publishNo(), published.pipelineNo(), instanceId);
    }

    private void asStopper() {
        TestUserContext.as(STOPPER, Set.of("OPS"), Set.of(Permissions.CONTROL_EXECUTE_PROD));
    }

    private void asCreator() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.TASK_CREATE));
    }

    private void pauseAtProdDeploy(long instanceId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE pipeline_instance SET status = 'PAUSED', current_node ="
                + " 'PROD_DEPLOY', status_reason = '停在生产边界', paused_at = ?, end_at = ?,"
                + " start_at = ? WHERE id = ?", Timestamp.from(now), Timestamp.from(now),
            Timestamp.from(now.minusSeconds(600)), instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = ?"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", Timestamp.from(now),
            instanceId);
    }

    private void runningVerify(long instanceId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING', current_node = 'VERIFY',"
            + " start_at = ? WHERE id = ?", Timestamp.from(now), instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = ?"
            + " WHERE instance_id = ? AND node_type = 'VERIFY'", Timestamp.from(now), instanceId);
    }

    private void runningProdDeploy(long itemId, long instanceId) {
        Instant now = Instant.now();
        setItemStatus(itemId, "PROD_DEPLOYING");
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING', current_node ="
            + " 'PROD_DEPLOY', start_at = ? WHERE id = ?", Timestamp.from(now), instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = ?"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", Timestamp.from(now),
            instanceId);
    }

    private void setItemStatus(long itemId, String status) {
        jdbc.update("UPDATE release_item SET status = ?, row_version = row_version + 1"
            + " WHERE id = ?", status, itemId);
    }

    private void setGateStatus(long itemId, String status) {
        jdbc.update("UPDATE release_item SET gate_status = ? WHERE id = ?", status, itemId);
    }

    private void setDeployTask(long instanceId, long deId, String status, String externalId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE node_task SET status = ?, external_id = ?, started_at = ?,"
                + " updated_at = ? WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " AND task_type = 'DEPLOY' AND target_id = ?", status, externalId,
            Timestamp.from(now.minusSeconds(300)), Timestamp.from(now), instanceId, deId);
    }

    private long instanceIdOf(String pipelineNo) {
        Long id = jdbc.queryForObject("SELECT id FROM pipeline_instance WHERE pipeline_no = ?",
            Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private long orderIdOf(String publishNo) {
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private String gateStatus(long itemId) {
        return jdbc.queryForObject("SELECT gate_status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private String instanceStatus(long instanceId) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String statusReason(long instanceId) {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private Instant endAt(long instanceId) {
        Timestamp value = jdbc.queryForObject("SELECT end_at FROM pipeline_instance WHERE id = ?",
            Timestamp.class, instanceId);
        return value == null ? null : value.toInstant();
    }

    private String orderStatus(String publishNo) {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, publishNo);
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM release_task WHERE id = ?", String.class,
            taskId);
    }

    private String nodeStatus(long instanceId, String nodeType) {
        return jdbc.queryForObject("SELECT status FROM pipeline_node WHERE instance_id = ?"
            + " AND node_type = ?", String.class, instanceId, nodeType);
    }

    private String deployTaskStatus(long instanceId, long deId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND task_type = 'DEPLOY' AND target_id = ?",
            String.class, instanceId, deId);
    }

    private String deployTaskSummary(long instanceId, long deId) {
        return jdbc.queryForObject("SELECT result_summary FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND task_type = 'DEPLOY' AND target_id = ?",
            String.class, instanceId, deId);
    }

    private int unitLockCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM deploy_unit_lock",
            Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> auditActions(String action) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE action = ?", String.class,
            action);
    }

    private List<String> recipientsOf(String eventKey, String objectId) {
        return jdbc.queryForList("SELECT recipient FROM notification WHERE event_key = ?"
            + " AND object_id = ? AND channel = 'INAPP'", String.class, eventKey, objectId);
    }

    /**
     * Asserts the business error code and, when given, that the payload's {@code currentStatus}, the
     * message or the payload contains it.
     */
    private static void assertBizError(BizError expected, Runnable call, String contains) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BizException.class)
            .satisfies(ex -> {
                BizException biz = (BizException) ex;
                assertThat(biz.getError()).isEqualTo(expected);
                if (contains != null) {
                    String payload = String.valueOf(biz.getPayload());
                    assertThat(biz.getMessage() + " " + payload).contains(contains);
                }
            });
    }

    // ------------------------------------------------------------------ stub engine

    static final class StubEngine implements CicdEngine {

        private final List<String> cancelled = new ArrayList<>();
        private boolean failCancel;

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the control tests");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("no deploy in the control tests");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the control tests");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the control tests");
        }

        @Override
        public void cancel(String externalId) {
            if (failCancel) {
                throw new IllegalStateException("engine is unavailable: " + externalId);
            }
            cancelled.add(externalId);
        }

        List<String> cancelled() {
            return List.copyOf(cancelled);
        }

        void clear() {
            cancelled.clear();
        }

        void failCancel(boolean value) {
            this.failCancel = value;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine controlStubEngine() {
            return new StubEngine();
        }
    }
}