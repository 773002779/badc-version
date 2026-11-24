package com.sf.release.audit;

import com.sf.release.application.CodingConnAppService;
import com.sf.release.application.ExecuteProdService;
import com.sf.release.application.FreezeService;
import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.ReleaseApprovalService;
import com.sf.release.application.RerunAppService;
import com.sf.release.application.RollbackService;
import com.sf.release.application.SubmitProdService;
import com.sf.release.application.SubsystemAppService;
import com.sf.release.application.TaskAppService;
import com.sf.release.application.UnitAppService;
import com.sf.release.application.WindowService;
import com.sf.release.application.notification.NotificationSettingService;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
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

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The action-to-trigger wiring of the audit design section 1: every action is produced by a
 * <em>real</em> service call, not by a direct {@code record} call, so the test proves the trigger
 * point actually writes its row. Each assertion names the actor, the object and the changed fields.
 *
 * <p>The pipeline advancer, git probe and CI engine are mocked because these tests are about the
 * audit seam, not about the engine: the publish/submit/execute/rerun state writes are real.
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    AuditWiringTest.StubEngineConfig.class})
@ActiveProfiles("test")
class AuditWiringTest {

    private static final String PUBLISH_NO = "PO-AUD-WIRE";
    private static final String DIGEST = "sha256:audit-wire";
    private static final String OA = "OA-AUD-0001";

    @Autowired PublishAppService publish;
    @Autowired SubmitProdService submit;
    @Autowired ExecuteProdService execute;
    @Autowired RerunAppService rerun;
    @Autowired ReleaseApprovalService approvals;
    @Autowired GateService gates;
    @Autowired GateCheckService checks;
    @Autowired GateWaiverService waivers;
    @Autowired FreezeService freezes;
    @Autowired RollbackService rollbacks;
    @Autowired CodingConnAppService codingConn;
    @Autowired NotificationSettingService notificationSettings;
    @Autowired WindowService windows;
    @Autowired SubsystemAppService subsystems;
    @Autowired UnitAppService units;
    @Autowired TaskAppService tasks;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;

    @MockBean GitCommitProbe commitProbe;
    @MockBean PipelineAdvancer pipelineAdvancer;
    @Autowired StubEngine cicdEngine;

    private long subsystem;
    private long unit;
    private long task;

    @BeforeEach
    void setUp() {
        AuditFixtures.clean(jdbc);
        InMemoryDistributedLock.clearAllLeases();
        subsystem = AuditFixtures.givenSubsystem(jdbc, "AUD-SYS", AuditFixtures.USER);
        unit = AuditFixtures.givenUnit(jdbc, subsystem, "aud-unit", AuditFixtures.USER);
        task = AuditFixtures.givenTask(jdbc, subsystem, "AUD-TASK", AuditFixtures.USER);
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ release flow

    @Test
    void publish_writes_the_publish_action_with_the_item_state_change() {
        givenPublishableUnit();
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-PUB", "PENDING",
            "CHECK_RUNNING", "TEST_PENDING", null);
        TestUserContext.allUnits(AuditFixtures.USER);

        PublishAppService.PublishResult result = publish.publish(item);

        Map<String, Object> row = lastRow("PUBLISH");
        assertThat(row.get("actor")).isEqualTo(AuditFixtures.USER);
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo(result.publishNo());
        assertThat((String) row.get("summary")).contains("发起发布").contains(result.publishNo());
        assertThat((String) row.get("before_json")).contains("PENDING");
        assertThat((String) row.get("after_json")).contains("BUILDING")
            .contains(result.pipelineNo());
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void submit_prod_writes_the_submission_with_its_planned_time_and_snapshot() {
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-SUB", "GATE_PENDING",
            "CHECK_PASS", "TEST_PASS", AuditFixtures.USER);
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "INIT");
        long order = orderId(PUBLISH_NO);
        AuditFixtures.givenInstance(jdbc, order, "PL-AUD-SUB", "INITIAL", "RUNNING", "VERIFY",
            DIGEST, null);
        AuditFixtures.givenCheck(jdbc, item, order, "SONAR", "IMAGE", "PASS", "S3");
        TestUserContext.as(AuditFixtures.USER, Set.of("OPS_LEAD"),
            Set.of(Permissions.CONTROL_SUBMIT_PROD));

        submit.submit(PUBLISH_NO, Instant.now().plusSeconds(3600), OA);

        Map<String, Object> row = lastRow("SUBMIT_PROD");
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo(PUBLISH_NO);
        assertThat((String) row.get("summary")).contains("提交生产").contains(PUBLISH_NO);
        assertThat((String) row.get("before_json")).contains("GATE_PENDING");
        assertThat((String) row.get("after_json")).contains("\"oaTicketNo\": \"" + OA + "\"")
            .contains("snapshotId");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void execute_prod_writes_the_execution_with_the_produced_item_and_instance_states() {
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-EXE", "READY");
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "READY");
        long order = orderId(PUBLISH_NO);
        long instance = AuditFixtures.givenInstance(jdbc, order, "PL-AUD-EXE", "INITIAL", "PAUSED",
            "PROD_DEPLOY", DIGEST, null);
        long de = AuditFixtures.givenDe(jdbc, unit, "PROD", "aud-prod", 1);
        AuditFixtures.givenBinding(jdbc, unit, "PROD_DEPLOY", "P-PROD-AUD");
        AuditFixtures.givenNode(jdbc, instance, "PROD_DEPLOY", 3, "PENDING");
        AuditFixtures.givenNodeTask(jdbc, instance, "PROD_DEPLOY", "DEPLOY", 1, de, "PENDING");
        AuditFixtures.givenCheck(jdbc, item, order, "SONAR", "IMAGE", "PASS", "S3");
        AuditFixtures.givenSnapshot(jdbc, order, item, "audit-submitter", DIGEST);
        unitLockService.acquire(unit, instance);
        TestUserContext.as(AuditFixtures.USER, Set.of("OPS"),
            Set.of(Permissions.CONTROL_EXECUTE_PROD));

        execute.execute(PUBLISH_NO);

        Map<String, Object> row = lastRow("EXECUTE_PROD");
        assertThat(row.get("object_id")).isEqualTo(PUBLISH_NO);
        assertThat((String) row.get("before_json")).contains("READY").contains("PAUSED");
        assertThat((String) row.get("after_json")).contains("PROD_DEPLOYING")
            .contains("RUNNING").contains("PROD_DEPLOY");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void rerun_writes_the_rerun_with_the_new_pipeline_and_trigger_type() {
        givenPublishableUnit();
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-RERUN", "FAILED",
            "CHECK_PASS", "TEST_PENDING", null);
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "FAILED");
        TestUserContext.allUnits(AuditFixtures.USER);

        RerunAppService.RerunResult result = rerun.rerun(item, true);

        Map<String, Object> row = lastRow("RERUN");
        assertThat(row.get("object_id")).isEqualTo(PUBLISH_NO);
        assertThat((String) row.get("summary")).contains("重新发布").contains(PUBLISH_NO);
        assertThat((String) row.get("before_json")).contains("FAILED");
        assertThat((String) row.get("after_json")).contains("BUILDING")
            .contains(result.pipelineNo()).contains("RERUN_FULL");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void approve_writes_the_signature_and_the_state_it_produced() {
        givenPendingApproval("OPS_LEAD");
        TestUserContext.as(AuditFixtures.USER, Set.of("OPS_LEAD"),
            Set.of(Permissions.CONTROL_APPROVE));

        approvals.approve(PUBLISH_NO, "同意放行");

        Map<String, Object> row = lastRow("APPROVE");
        assertThat(row.get("object_type")).isEqualTo("ORDER");
        assertThat(row.get("object_id")).isEqualTo(PUBLISH_NO);
        assertThat(row.get("reason")).isEqualTo("同意放行");
        assertThat((String) row.get("summary")).contains("审批通过").contains("第 1 步");
        assertThat((String) row.get("before_json")).contains("PENDING_APPROVAL")
            .contains("PENDING");
        assertThat((String) row.get("after_json")).contains("READY")
            .contains("APPROVED").contains("\"chainComplete\": true");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void reject_writes_the_rejection_reason_and_the_terminal_state() {
        givenPendingApproval("OPS_LEAD");
        TestUserContext.as(AuditFixtures.USER, Set.of("OPS_LEAD"),
            Set.of(Permissions.CONTROL_APPROVE));

        approvals.reject(PUBLISH_NO, "窗口外风险过大");

        Map<String, Object> row = lastRow("REJECT");
        assertThat(row.get("object_id")).isEqualTo(PUBLISH_NO);
        assertThat(row.get("reason")).isEqualTo("窗口外风险过大");
        assertThat((String) row.get("summary")).contains("审批驳回");
        assertThat((String) row.get("after_json")).contains("REJECTED")
            .contains("\"chainComplete\": false");
    }

    // ------------------------------------------------------------------ gate

    @Test
    void pass_test_writes_the_test_pass_with_the_actor() {
        long item = givenTestingItem("CR-AUD-PASS");
        TestUserContext.as(AuditFixtures.USER, Set.of("ADMIN"),
            Set.of(Permissions.GATE_TEST_PASS));

        gates.passTest(item);

        Map<String, Object> row = lastRow("GATE_TEST_PASS");
        assertThat(row.get("object_type")).isEqualTo("ITEM");
        assertThat(row.get("object_id")).isEqualTo(String.valueOf(item));
        assertThat(row.get("object_label")).isEqualTo("CR-AUD-PASS");
        assertThat((String) row.get("after_json")).contains("TEST_PASS")
            .contains(AuditFixtures.USER);
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void fail_test_writes_the_reason_and_the_failed_state() {
        long item = givenTestingItem("CR-AUD-FAIL");
        TestUserContext.as(AuditFixtures.USER, Set.of("ADMIN"),
            Set.of(Permissions.GATE_TEST_PASS));

        gates.failTest(item, "接口超时");

        Map<String, Object> row = lastRow("GATE_TEST_FAIL");
        assertThat(row.get("reason")).isEqualTo("接口超时");
        assertThat((String) row.get("before_json")).contains("TEST_PENDING");
        assertThat((String) row.get("after_json")).contains("TEST_FAIL")
            .contains("接口超时");
    }

    @Test
    void revoke_test_writes_the_required_reason_and_the_revoked_state() {
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-REVOKE", "TESTING",
            "CHECK_PASS", "TEST_PASS", AuditFixtures.USER);
        TestUserContext.as(AuditFixtures.USER, Set.of("ADMIN"),
            Set.of(Permissions.GATE_TEST_REVOKE));

        gates.revokeTest(item, "环境部署错了");

        Map<String, Object> row = lastRow("GATE_TEST_REVOKE");
        assertThat(row.get("reason")).isEqualTo("环境部署错了");
        assertThat((String) row.get("before_json")).contains("TEST_PASS");
        assertThat((String) row.get("after_json")).contains("TEST_REVOKED")
            .contains("环境部署错了");
    }

    @Test
    void refresh_writes_the_check_kind_and_the_state_it_restarted() {
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-REFRESH", "TESTING");
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "PENDING");
        long order = orderId(PUBLISH_NO);
        long instance = AuditFixtures.givenInstance(jdbc, order, "PL-AUD-REFRESH", "INITIAL",
            "RUNNING", "VERIFY", DIGEST, null);
        TestUserContext.allUnits(AuditFixtures.USER);

        checks.refresh(item, "SONAR", instance);

        Map<String, Object> row = lastRow("GATE_REFRESH");
        assertThat(row.get("object_id")).isEqualTo(String.valueOf(item));
        assertThat((String) row.get("after_json")).contains("SONAR").contains("RUNNING");
        assertThat((String) row.get("summary")).contains("刷新检查");
    }

    @Test
    void waiver_request_writes_the_reason_and_the_pending_waiver() {
        long item = givenWaivableItem("CR-AUD-WREQ");
        TestUserContext.as(AuditFixtures.USER, Set.of("ADMIN"), Set.of());
        Instant expire = Instant.now().plusSeconds(86400);

        waivers.request(item, "已知中危可接受", "影响可控", expire);

        Map<String, Object> row = lastRow("WAIVER_REQUEST");
        assertThat(row.get("object_type")).isEqualTo("WAIVER");
        assertThat(row.get("reason")).isEqualTo("已知中危可接受");
        assertThat((String) row.get("before_json")).isNull();
        assertThat((String) row.get("after_json")).contains("PENDING")
            .contains("已知中危可接受").contains("影响可控");
    }

    @Test
    void waiver_approve_and_reject_write_their_decisions() {
        long item = givenWaivableItem("CR-AUD-WDEC");
        long pending = AuditFixtures.givenWaiver(jdbc, item, null, "PENDING", "audit-requester",
            Instant.now().plusSeconds(86400), DIGEST);
        TestUserContext.as(AuditFixtures.USER, Set.of("ADMIN"),
            Set.of(Permissions.GATE_WAIVE));

        waivers.approve(pending, "同意豁免");
        Map<String, Object> approved = lastRow("WAIVER_APPROVE");
        assertThat(approved.get("object_id")).isEqualTo(String.valueOf(pending));
        assertThat(approved.get("reason")).isEqualTo("同意豁免");
        assertThat((String) approved.get("after_json")).contains("APPROVED")
            .contains(AuditFixtures.USER);

        long second = AuditFixtures.givenWaiver(jdbc, item, null, "PENDING", "audit-requester",
            Instant.now().plusSeconds(86400), DIGEST);
        waivers.reject(second, "不接受该风险");
        Map<String, Object> rejected = lastRow("WAIVER_REJECT");
        assertThat(rejected.get("object_id")).isEqualTo(String.valueOf(second));
        assertThat(rejected.get("reason")).isEqualTo("不接受该风险");
        assertThat((String) rejected.get("after_json")).contains("REJECTED");
    }

    // ------------------------------------------------------------------ freezes

    @Test
    void freeze_create_and_release_write_both_events() {
        TestUserContext.as(AuditFixtures.USER, Set.of("DEPT_MANAGER", "OPS_LEAD"),
            Set.of(Permissions.CONTROL_FREEZE, Permissions.CONTROL_UNFREEZE));
        Instant now = Instant.now();

        long id = freezes.create("SUBSYSTEM", subsystem, "大促封板", "只读", now.minusSeconds(60),
            now.plusSeconds(3600), AuditFixtures.USER).id();

        Map<String, Object> created = lastRow("FREEZE_CREATE");
        assertThat(created.get("object_type")).isEqualTo("FREEZE");
        assertThat(created.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat(created.get("reason")).isEqualTo("大促封板");
        assertThat((String) created.get("after_json")).contains("ACTIVE")
            .contains("SUBSYSTEM").contains("大促封板");
        assertThat(((Number) created.get("scope_subsystem_id")).longValue()).isEqualTo(subsystem);

        freezes.release(id, AuditFixtures.USER, "风险解除");

        Map<String, Object> released = lastRow("FREEZE_RELEASE");
        assertThat(released.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat(released.get("reason")).isEqualTo("风险解除");
        assertThat((String) released.get("before_json")).contains("ACTIVE");
        assertThat((String) released.get("after_json")).contains("RELEASED")
            .contains(AuditFixtures.USER);
    }

    @Test
    void freeze_expiry_is_recorded_as_the_system_actor() {
        Instant now = Instant.now();
        long id = AuditFixtures.givenFreezeInForce(jdbc, subsystem, "到期封板", "audit-initiator",
            now.minusSeconds(7200), now.minusSeconds(3600));
        TestUserContext.clear();

        freezes.expireDue(now);

        Map<String, Object> row = lastRow("FREEZE_EXPIRE");
        assertThat(row.get("actor")).isEqualTo("SYSTEM");
        assertThat(row.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat((String) row.get("before_json")).contains("ACTIVE");
        assertThat((String) row.get("after_json")).contains("EXPIRED");
        assertThat(row.get("trace_id")).isNull();
    }

    // ------------------------------------------------------------------ rollback and settings

    @Test
    void rollback_execute_writes_the_source_target_and_the_reason() {
        long de = AuditFixtures.givenDe(jdbc, unit, "PROD", "aud-rb-prod", 1);
        AuditFixtures.givenBinding(jdbc, unit, "PROD_DEPLOY", "P-PROD-AUD");
        Instant now = Instant.now();
        AuditFixtures.givenVersion(jdbc, task, subsystem, unit, "PO-AUD-RB-1", "sha256:audit-cur",
            de, now.minusSeconds(3600));
        AuditFixtures.givenVersion(jdbc, task, subsystem, unit, "PO-AUD-RB-2", "sha256:audit-old",
            de, now.minusSeconds(7200));
        TestUserContext.as(AuditFixtures.USER, Set.of("OPS"),
            Set.of(Permissions.ROLLBACK_EXECUTE));

        RollbackService.ExecuteResult result = rollbacks.execute(unit, "sha256:audit-old",
            "线上故障止损");

        Map<String, Object> row = lastRow("ROLLBACK_EXECUTE");
        assertThat(row.get("object_type")).isEqualTo("ROLLBACK");
        assertThat(row.get("object_id")).isEqualTo(String.valueOf(result.rollbackId()));
        assertThat(row.get("reason")).isEqualTo("线上故障止损");
        assertThat((String) row.get("before_json")).contains("sha256:audit-cur");
        assertThat((String) row.get("after_json")).contains("sha256:audit-old");
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unit);
    }

    @Test
    void coding_connection_save_writes_a_platform_setting_without_the_secret() {
        TestUserContext.allUnits(AuditFixtures.USER);

        codingConn.save("GLOBAL", null, "https://coding.example/api", "team-a", "project-a",
            "plaintext-secret-token");

        Map<String, Object> row = lastRow("SETTING_UPDATE");
        assertThat(row.get("object_id")).isEqualTo("CODING:GLOBAL");
        assertThat(row.get("scope_unit_id")).isNull();
        assertThat(row.get("scope_subsystem_id")).isNull();
        assertThat((String) row.get("before_json")).isNull();
        assertThat((String) row.get("after_json")).contains("team-a").contains("project-a")
            .doesNotContain("plaintext-secret-token");
    }

    @Test
    void notification_setting_save_writes_the_platform_setting() {
        TestUserContext.allUnits(AuditFixtures.USER);

        notificationSettings.save(true, "https://qyapi.example.invalid/hook");

        Map<String, Object> row = lastRow("SETTING_UPDATE");
        assertThat(row.get("object_id")).isEqualTo("NOTIFICATION");
        assertThat((String) row.get("before_json")).contains("\"wecomEnabled\": false");
        assertThat((String) row.get("after_json")).contains("\"wecomEnabled\": true")
            .contains("qyapi.example.invalid");
    }

    @Test
    void window_save_writes_the_window_update() {
        TestUserContext.allUnits(AuditFixtures.USER);

        windows.save(new WindowService.SaveCommand("GLOBAL", null, true, "Asia/Shanghai",
            List.of(new WindowService.RuleInput(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            List.of()));

        Map<String, Object> row = lastRow("WINDOW_UPDATE");
        assertThat(row.get("object_id")).isEqualTo("WINDOW:GLOBAL");
        assertThat((String) row.get("after_json")).contains("\"ruleCount\": 1")
            .contains("Asia/Shanghai");
    }

    @Test
    void subsystem_create_and_update_write_resource_events() {
        AuditFixtures.givenUser(jdbc, AuditFixtures.USER);
        TestUserContext.allUnits(AuditFixtures.USER);

        long id = subsystems.create(new SubsystemAppService.CreateCmd("AUD-NEW-SYS",
            AuditFixtures.USER, null));

        Map<String, Object> created = lastRow("RESOURCE_UPDATE");
        assertThat(created.get("object_type")).isEqualTo("SUBSYSTEM");
        assertThat(created.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat((String) created.get("before_json")).isNull();
        assertThat((String) created.get("after_json")).contains("AUD-NEW-SYS");
        assertThat(((Number) created.get("scope_subsystem_id")).longValue()).isEqualTo(id);

        subsystems.update(id, new SubsystemAppService.UpdateCmd("AUD-NEW-SYS-2",
            AuditFixtures.USER, null));

        Map<String, Object> updated = lastRow("RESOURCE_UPDATE");
        assertThat((String) updated.get("before_json")).contains("AUD-NEW-SYS");
        assertThat((String) updated.get("after_json")).contains("AUD-NEW-SYS-2");
    }

    @Test
    void unit_create_and_update_write_unit_scoped_resource_events() {
        AuditFixtures.givenUser(jdbc, AuditFixtures.USER);
        TestUserContext.allUnits(AuditFixtures.USER);

        long id = units.create(new UnitAppService.CreateCmd(subsystem, "AUD-NEW-UNIT",
            AuditFixtures.USER));

        Map<String, Object> created = lastRow("RESOURCE_UPDATE");
        assertThat(created.get("object_type")).isEqualTo("UNIT");
        assertThat(created.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat(((Number) created.get("scope_unit_id")).longValue()).isEqualTo(id);
        assertThat(((Number) created.get("scope_subsystem_id")).longValue()).isEqualTo(subsystem);

        units.update(id, new UnitAppService.UpdateCmd("AUD-NEW-UNIT-2", AuditFixtures.USER));

        Map<String, Object> updated = lastRow("RESOURCE_UPDATE");
        assertThat((String) updated.get("before_json")).contains("AUD-NEW-UNIT");
        assertThat((String) updated.get("after_json")).contains("AUD-NEW-UNIT-2");
    }

    @Test
    void task_create_writes_a_system_scoped_resource_event() {
        AuditFixtures.givenUser(jdbc, AuditFixtures.USER);
        TestUserContext.allUnits(AuditFixtures.USER);

        long id = tasks.create(new TaskAppService.CreateTaskCmd("AUD-NEW-TASK", subsystem,
            AuditFixtures.USER));

        Map<String, Object> row = lastRow("RESOURCE_UPDATE");
        assertThat(row.get("object_type")).isEqualTo("TASK");
        assertThat(row.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat(row.get("scope_unit_id")).isNull();
        assertThat(((Number) row.get("scope_subsystem_id")).longValue()).isEqualTo(subsystem);
        assertThat((String) row.get("after_json")).contains("AUD-NEW-TASK");
    }

    // ------------------------------------------------------------------ fixtures

    private void givenPublishableUnit() {
        AuditFixtures.givenBinding(jdbc, unit, "BUILD", "P-BUILD-AUD");
        AuditFixtures.givenBinding(jdbc, unit, "TEST_DEPLOY", "P-TEST-AUD");
        AuditFixtures.givenBinding(jdbc, unit, "PROD_DEPLOY", "P-PROD-AUD");
        AuditFixtures.givenDe(jdbc, unit, "TEST", "aud-test", 0);
        AuditFixtures.givenDe(jdbc, unit, "PROD", "aud-prod", 1);
    }

    private long givenTestingItem(String crid) {
        long item = AuditFixtures.givenItem(jdbc, task, unit, crid, "TESTING", "CHECK_RUNNING",
            "TEST_PENDING", null);
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "PENDING");
        long order = orderId(PUBLISH_NO);
        AuditFixtures.givenInstance(jdbc, order, "PL-" + crid, "INITIAL", "RUNNING", "VERIFY",
            DIGEST, Instant.now());
        return item;
    }

    private long givenWaivableItem(String crid) {
        long item = AuditFixtures.givenItem(jdbc, task, unit, crid, "TESTING", "CHECK_RUNNING",
            "TEST_PENDING", null);
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "PENDING");
        long order = orderId(PUBLISH_NO);
        AuditFixtures.givenInstance(jdbc, order, "PL-" + crid, "INITIAL", "RUNNING", "VERIFY",
            DIGEST, null);
        AuditFixtures.givenCheck(jdbc, item, order, "SONAR", "IMAGE", "FAIL", "S2");
        return item;
    }

    private void givenPendingApproval(String role) {
        long item = AuditFixtures.givenItem(jdbc, task, unit, "CR-AUD-APPR", "PENDING_APPROVAL");
        AuditFixtures.givenOrder(jdbc, item, PUBLISH_NO, "PENDING_APPROVAL");
        long order = orderId(PUBLISH_NO);
        AuditFixtures.givenInstance(jdbc, order, "PL-AUD-APPR", "INITIAL", "RUNNING", "VERIFY",
            DIGEST, null);
        AuditFixtures.givenSnapshot(jdbc, order, item, "audit-submitter", DIGEST);
        long snapshot = jdbc.queryForObject("SELECT MAX(id) FROM admission_snapshot",
            Long.class);
        AuditFixtures.givenApproval(jdbc, order, snapshot, 1, role, "PENDING");
    }

    private long orderId(String publishNo) {
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    private Map<String, Object> lastRow(String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM audit_log WHERE action = ? ORDER BY id DESC LIMIT 1", action);
        assertThat(rows).as("an audit row for %s", action).hasSize(1);
        return rows.get(0);
    }

    /** A named CI engine: {@code EngineRegistry} requires every engine bean to report a name. */
    static final class StubEngine implements CicdEngine {

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult startCheck(GateCheckCmd cmd) {
            return new TriggerResult("audit-check-1", "https://coding.example/check/1");
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the audit wiring test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("no deploy in the audit wiring test");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the audit wiring test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the audit wiring test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the audit wiring test");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine auditStubEngine() {
            return new StubEngine();
        }
    }
}