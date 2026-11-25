package com.sf.release.lib;

import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.LibPublishNodeHandler;
import com.sf.release.application.LibSecurityNodeHandler;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.GateCheckMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second-party library pipeline against the real test schema (MySQL, Flyway V1-V29) with a
 * recording engine stub: publish creates the three-node LIB graph, the build chain stops after
 * {@code PKG_BUILD}, the security node is decided by the three artifact checks (PASS/WAIVED) and
 * the artifact upload ends the item {@code DONE} — all without entering {@code TESTING}, the
 * production states, a notification or a new table (P6-A design §3/§5).
 */
@SpringBootTest
@ActiveProfiles("test")
class LibPipelineTest {

    private static final int STATE_NOT_ALLOWED = 40001;
    private static final int UNIT_LOCKED = 40006;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired GateService gateService;
    @Autowired UnitLockService unitLockService;
    @Autowired GateCheckMapper gateCheckMapper;
    @Autowired NodeTaskMapper nodeTaskMapper;
    @Autowired ReleaseItemMapper releaseItemMapper;
    @Autowired ReleaseOrderMapper releaseOrderMapper;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private LibStubEngine engine;
    private GateCheckService checkService;
    private LibSecurityNodeHandler securityHandler;
    private LibPublishNodeHandler publishHandler;

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long itemId;
    private long instanceId;
    private String publishNo;

    @BeforeEach
    void setUp() {
        LibFixtures.reset(jdbc);
        TestUserContext.allUnits(LibFixtures.OWNER);
        LibFixtures.givenUser(jdbc, LibFixtures.OWNER);
        subsystemId = LibFixtures.givenSubsystem(jdbc, "SCC-P6A-LIB");
        taskId = LibFixtures.givenTask(jdbc, subsystemId, "202611-P6A");
        unitId = LibFixtures.givenUnit(jdbc, subsystemId, "quota-sdk", "LIB", null,
            "com.sf.quota", "quota-sdk");
        LibFixtures.givenBinding(jdbc, unitId, "BUILD", "P-BUILD-SDK");
        LibFixtures.givenBinding(jdbc, unitId, "LIB_PUBLISH", "P-PUBLISH-SDK");
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, LibFixtures.BRANCH, LibFixtures.VERSION,
                LibFixtures.PLAN_DATE));

        engine = new LibStubEngine();
        // The real GateCheckService binds a process-wide engine bean; this test rebuilds it over
        // the recording stub so the three checks are triggered (and counted) by the stub. The
        // gate decision service stays the real bean: it reads the same gate_check/waiver rows.
        checkService = new GateCheckService(engine, gateCheckMapper, nodeTaskMapper,
            releaseItemMapper, releaseOrderMapper, repository, audit, transactionManager);
        securityHandler = new LibSecurityNodeHandler(checkService, gateService, repository);
        publishHandler = new LibPublishNodeHandler(engine, repository, transactionRunner,
            completionService);
        handlers.clear();
        handlers.register(new BuildNodeHandler(engine, repository, transactionRunner,
            completionService));
        handlers.register(securityHandler);
        handlers.register(publishHandler);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ publish shape

    @Test
    void a_library_publish_creates_the_three_node_pipeline_and_holds_the_unit_lock() {
        PublishResult result = publish();

        assertThat(result.type()).isEqualTo("LIB");
        assertThat(result.publishNo()).startsWith("PO-");
        assertThat(result.pipelineNo()).startsWith("PL-");

        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT * FROM pipeline_instance WHERE id = ?", instanceId);
        assertThat(instance.get("exec_type")).isEqualTo("LIB");
        assertThat(instance.get("trigger_type")).isEqualTo("INITIAL");
        assertThat(instance.get("current_node")).isEqualTo("BUILD");
        assertThat(instance.get("status")).isEqualTo("PENDING");

        assertThat(jdbc.queryForList(
                "SELECT node_type FROM pipeline_node WHERE instance_id = ? ORDER BY seq_no",
                String.class, instanceId))
            .containsExactly("BUILD", "LIB_SECURITY", "LIB_PUBLISH");

        assertThat(jdbc.queryForList(
                "SELECT task_type FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                    + " ORDER BY id", String.class, instanceId))
            .as("the library build chain has no IMG_BUILD")
            .containsExactly("CODE_CHECK", "PKG_BUILD");
        assertThat(jdbc.queryForList(
                "SELECT task_type FROM node_task WHERE instance_id = ? AND node_type ="
                    + " 'LIB_SECURITY'", String.class, instanceId))
            .isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT task_type FROM node_task WHERE instance_id = ? AND node_type ="
                    + " 'LIB_PUBLISH'", String.class, instanceId))
            .containsExactly("LIB_PUBLISH");

        assertThat(itemStatus()).isEqualTo("BUILDING");
        assertThat(tf("SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", unitId))
            .isEqualTo(instanceId);
    }

    @Test
    void a_library_without_artifact_coordinates_cannot_publish() {
        long bareUnit = LibFixtures.givenUnit(jdbc, subsystemId, "legacy-sdk", "LIB", null,
            null, null);
        LibFixtures.givenBinding(jdbc, bareUnit, "BUILD", "P-BUILD-LEGACY");
        LibFixtures.givenBinding(jdbc, bareUnit, "LIB_PUBLISH", "P-PUB-LEGACY");
        long bareItem = itemService.create(taskId, new CreateItemCmd(bareUnit,
            LibFixtures.BRANCH, "1.0.0", LibFixtures.PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(bareItem))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED);
                assertThat(ex.getError().getCode()).isEqualTo(STATE_NOT_ALLOWED);
                assertThat(payload(ex).get("missing")).isEqualTo(List.of("ARTIFACT_COORDINATES"));
            });
        assertNoOrderOrLock();
        assertThat(itemStatus(bareItem)).isEqualTo("PENDING");
    }

    @Test
    void a_library_without_the_publish_binding_cannot_publish() {
        long noPublishUnit = LibFixtures.givenUnit(jdbc, subsystemId, "no-publish-sdk", "LIB",
            null, "com.sf.quota", "no-publish-sdk");
        LibFixtures.givenBinding(jdbc, noPublishUnit, "BUILD", "P-BUILD-NOPUB");
        long noPublishItem = itemService.create(taskId, new CreateItemCmd(noPublishUnit,
            LibFixtures.BRANCH, "1.0.0", LibFixtures.PLAN_DATE));

        assertThatThrownBy(() -> publishService.publish(noPublishItem))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED);
                assertThat(payload(ex).get("missing")).isEqualTo(List.of("LIB_PUBLISH"));
            });
        assertNoOrderOrLock();
    }

    @Test
    void a_held_library_unit_lock_rejects_the_publish_with_40006() {
        assertThat(unitLockService.acquire(unitId, 999L)).isTrue();

        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.UNIT_LOCKED);
                assertThat(ex.getError().getCode()).isEqualTo(UNIT_LOCKED);
            });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Integer.class))
            .isZero();
        assertThat(itemStatus()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ build / security

    @Test
    void the_build_chain_stops_after_pkg_build_and_a_successful_build_leaves_the_item_building() {
        publish();

        orchestrator.advance(instanceId);
        assertThat(engine.buildCalls).hasSize(1);
        assertThat(engine.buildCalls.get(0).taskId()).isEqualTo("P-BUILD-SDK");

        orchestrator.onTaskCompleted(taskId(NodeType.BUILD, TaskType.CODE_CHECK),
            NodeTaskStatus.SUCCESS, null, null);
        assertThat(engine.buildCalls)
            .as("only CODE_CHECK and PKG_BUILD, never IMG_BUILD")
            .hasSize(2);

        orchestrator.onTaskCompleted(taskId(NodeType.BUILD, TaskType.PKG_BUILD),
            NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(itemStatus())
            .as("a LIB item never enters TESTING on a build success")
            .isEqualTo("BUILDING");
        assertThat(currentNode()).isEqualTo("LIB_SECURITY");
        assertThat(instanceStatus()).isEqualTo("RUNNING");

        assertThat(engine.checkedTypes()).containsExactly(CheckType.SONAR,
            CheckType.COMPONENT_VULN, CheckType.VERSION_DEFECT);
        assertThat(jdbc.queryForList(
                "SELECT check_kind FROM gate_check WHERE item_id = ? ORDER BY check_kind",
                String.class, itemId))
            .containsExactly("COMPONENT_VULN", "SONAR", "VERSION_DEFECT");
        assertThat(jdbc.queryForList("SELECT status FROM gate_check WHERE item_id = ?",
                String.class, itemId))
            .containsOnly("RUNNING");
    }

    @Test
    void re_entering_the_security_node_does_not_start_a_fourth_check() {
        advanceToSecurity();
        assertThat(engine.checkCalls).hasSize(3);

        securityHandler.start(repository.loadInstance(instanceId));
        securityHandler.start(repository.loadInstance(instanceId));

        assertThat(engine.checkCalls).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_check WHERE item_id = ?",
            Integer.class, itemId)).isEqualTo(3);
    }

    @Test
    void security_passes_only_when_the_three_checks_passed_or_are_waived() {
        advanceToSecurity();

        finishChecks("PASS", "NONE");
        orchestrator.advance(instanceId);

        assertThat(nodeStatus(NodeType.LIB_SECURITY)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("LIB_PUBLISH");
        assertThat(itemStatus())
            .as("even after the security node the item is still BUILDING")
            .isEqualTo("BUILDING");
        assertThat(engine.publishCalls).hasSize(1);
    }

    @Test
    void a_final_security_failure_fails_the_instance_with_the_reason() {
        advanceToSecurity();

        finishChecks("FAIL", "S1");
        orchestrator.advance(instanceId);

        assertThat(nodeStatus(NodeType.LIB_SECURITY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(instanceStatusReason()).contains("二方库安全检查未通过");
        assertThat(itemStatus()).isEqualTo("FAILED");
        assertThat(engine.publishCalls)
            .as("nothing is uploaded after a blocking finding")
            .isEmpty();
    }

    @Test
    void an_approved_waiver_lets_the_security_node_pass_and_the_artifact_be_uploaded() {
        advanceToSecurity();
        finishChecks("FAIL", "S2");
        givenApprovedWaiver();

        orchestrator.advance(instanceId);

        assertThat(nodeStatus(NodeType.LIB_SECURITY)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("LIB_PUBLISH");
        assertThat(engine.publishCalls)
            .as("a medium finding covered by a valid waiver does not block the upload")
            .hasSize(1);
        assertThat(itemStatus()).isEqualTo("BUILDING");
    }

    // ------------------------------------------------------------------ artifact upload

    @Test
    void the_upload_waits_with_a_60_minute_deadline_and_the_success_ends_the_item() {
        advanceToSecurity();
        finishChecks("PASS", "NONE");
        orchestrator.advance(instanceId);

        long publishTaskId = taskId(NodeType.LIB_PUBLISH, TaskType.LIB_PUBLISH);
        assertThat(taskStatus(publishTaskId)).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(publishTaskId)).isEqualTo("publish-1");
        Instant deadline = deadline(publishTaskId);
        assertThat(Duration.between(Instant.now(), deadline))
            .isBetween(Duration.ofMinutes(55), Duration.ofMinutes(65));

        // A re-entry must never upload the same artifact twice.
        publishHandler.start(repository.loadInstance(instanceId));
        assertThat(engine.publishCalls).hasSize(1);

        orchestrator.onTaskCompleted(publishTaskId, NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus(NodeType.LIB_PUBLISH)).isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("DONE");
        assertThat(itemStatus()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, publishNo))
            .as("the 发布记录 order mirrors the finished item, not INIT")
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'PUBLISH' AND object_id = ?",
            Integer.class, publishNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
            .as("a LIB publish emits no notification (P6-A design §5 item 7)")
            .isZero();
    }

    @Test
    void an_engine_failure_fails_the_task_and_the_instance_and_keeps_the_reason() {
        advanceToSecurity();
        finishChecks("PASS", "NONE");
        engine.publishFailure = EngineException.unavailable("artifact repository 500");

        orchestrator.advance(instanceId);

        long publishTaskId = taskId(NodeType.LIB_PUBLISH, TaskType.LIB_PUBLISH);
        assertThat(taskStatus(publishTaskId)).isEqualTo("FAILED");
        assertThat(resultSummary(publishTaskId))
            .contains("ENGINE_UNAVAILABLE")
            .contains("artifact repository 500");
        assertThat(nodeStatus(NodeType.LIB_PUBLISH)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(instanceStatusReason()).contains("artifact repository 500");
        assertThat(itemStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, publishNo))
            .as("a failed library release shows FAILED in the records page, not INIT")
            .isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private PublishResult publish() {
        PublishResult result = publishService.publish(itemId);
        publishNo = result.publishNo();
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            result.pipelineNo());
        instanceId = id == null ? 0L : id;
        return result;
    }

    /** Publishes, then runs the build chain up to the security node (which starts the checks). */
    private void advanceToSecurity() {
        publish();
        orchestrator.advance(instanceId);
        orchestrator.onTaskCompleted(taskId(NodeType.BUILD, TaskType.CODE_CHECK),
            NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(taskId(NodeType.BUILD, TaskType.PKG_BUILD),
            NodeTaskStatus.SUCCESS, null, null);
        assertThat(currentNode()).isEqualTo("LIB_SECURITY");
        assertThat(engine.checkCalls).hasSize(3);
    }

    /** Finishes the three security checks of the item with one status/severity. */
    private void finishChecks(String status, String severity) {
        jdbc.update("UPDATE gate_check SET status = ?, severity = ?, finished_at = NOW(3)"
            + " WHERE item_id = ?", status, severity, itemId);
    }

    /** An approved waiver covering the item's current (null) commit/digest snapshot. */
    private void givenApprovedWaiver() {
        Long orderId = jdbc.queryForObject(
            "SELECT order_id FROM pipeline_instance WHERE id = ?", Long.class, instanceId);
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, requested_by,"
                + " requested_at, approved_by, approved_at, expire_at, commit_sha, image_digest,"
                + " created_at, updated_at) VALUES (?, ?, 'APPROVED', '中危可接受，限期内修复', ?,"
                + " NOW(3), ?, NOW(3), DATE_ADD(NOW(3), INTERVAL 1 DAY), NULL, NULL, NOW(3),"
                + " NOW(3))",
            itemId, orderId, LibFixtures.OWNER, LibFixtures.APPROVER);
    }

    private long taskId(NodeType nodeType, TaskType taskType) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = ? AND task_type = ?",
            Long.class, instanceId, nodeType.name(), taskType.name());
        return id == null ? 0L : id;
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String externalId(long taskId) {
        return jdbc.queryForObject("SELECT external_id FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String resultSummary(long taskId) {
        return jdbc.queryForObject("SELECT result_summary FROM node_task WHERE id = ?",
            String.class, taskId);
    }

    private Instant deadline(long taskId) {
        Timestamp value = jdbc.queryForObject("SELECT deadline_at FROM node_task WHERE id = ?",
            Timestamp.class, taskId);
        return value == null ? null : value.toInstant();
    }

    private String nodeStatus(NodeType nodeType) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, nodeType.name());
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceStatusReason() {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String itemStatus() {
        return itemStatus(itemId);
    }

    private String itemStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            id);
    }

    private long tf(String sql, long param) {
        Long value = jdbc.queryForObject(sql, Long.class, param);
        return value == null ? 0L : value;
    }

    private void assertNoOrderOrLock() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_instance", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deploy_unit_lock", Integer.class))
            .isZero();
    }

    private static Map<String, Object> payload(BizException ex) {
        assertThat(ex.getPayload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
        return payload;
    }
}