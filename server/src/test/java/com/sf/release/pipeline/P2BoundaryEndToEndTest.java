package com.sf.release.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.ReleaseApplication;
import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.GateCheckOutcome;
import com.sf.release.domain.engine.GateCheckReport;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.gate.Severity;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.infra.job.GateReconcileJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The P2 end-to-end release: item creation and publish over HTTP, the real pipeline handlers driven
 * by a stub engine, the seven gate checks reported asynchronously, the two gates decided, and the
 * production admission answered.
 *
 * <p><strong>What P2 changed.</strong> P1 stopped {@code PAUSED} before {@code VERIFY} because the
 * node had no handler. Now {@code VERIFY} runs: it triggers the checks, waits for their results, and
 * completes when both gates are satisfied.
 *
 * <p><strong>What P3 changes here.</strong> {@code PROD_DEPLOY} now has a handler, so the pipeline
 * enters the node when {@code VERIFY} completes. It must <em>not</em> deploy yet: the 变更项 is still
 * {@code GATE_PENDING}, and the handler's batch boundary pauses the instance with a reason until the
 * release is submitted, approved and executed through {@code POST .../execute}. The generic
 * {@code /resume} endpoint deliberately refuses that pause (production is not a gate recovery), so
 * this class keeps pinning the boundary, one node and one permission further out.
 *
 * <p><strong>The engine is a stub, the wiring is real.</strong> A {@code @Primary} bean replaces the
 * CODING adapter so the test can decide what each check reports, while the registry, orchestrator,
 * repository, migrations and gate services stay exactly as they ship.
 *
 * <p><strong>What this class deliberately covers.</strong> The happy path, a 高危 finding (final,
 * not waivable), a 中危 finding that waits for a waiver (including the self-approval refusal and the
 * expiry revocation), the test-pass permission rules, a failed test, and an unreachable engine (which
 * must never read as a pass).
 */
@SpringBootTest(classes = {ReleaseApplication.class, P2BoundaryEndToEndTest.StubEngineConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P2BoundaryEndToEndTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String OWNER = "01388007";
    private static final String DEV = "t-p2-dev";
    private static final String TESTER = "t-p2-tester";
    private static final String OUTSIDER = "t-p2-outsider";
    private static final String REVIEWER = "t-p2-reviewer";
    private static final String ADMIN = "t-p2-admin";

    private static final String BRANCH = "release/p2";
    private static final String CLIENT_BRANCH = "client/ignored-branch";
    private static final String MODULE = "outbound-os/gate";
    private static final String DIGEST = "sha256:p2-gate";
    private static final String COMMIT = "9f".repeat(32);
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);

    private static final String ITEMS = "/api/v1/items";
    private static final String PUBLISH = "/api/v1/items/{itemId}/publish";
    private static final String RELEASE = "/api/v1/releases/{publishNo}";
    private static final String GATE = "/api/v1/items/{itemId}/gate";
    private static final String TEST_PASS = "/api/v1/items/{itemId}/gate/test-pass";
    private static final String TEST_FAIL = "/api/v1/items/{itemId}/gate/test-fail";
    private static final String TEST_REVOKE = "/api/v1/items/{itemId}/gate/test-revoke";
    private static final String WAIVER = "/api/v1/items/{itemId}/gate/waiver";
    private static final String REFRESH = "/api/v1/items/{itemId}/gate/checks/{kind}/refresh";
    private static final String RESUME = "/api/v1/releases/{publishNo}/resume";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired StubEngine engine;
    @Autowired GateCheckService checkService;
    @Autowired GateService gateService;
    @Autowired GateWaiverService waiverService;
    @Autowired DistributedLock lock;

    private long taskId;
    private long unitId;
    private long testDeOne;
    private long testDeTwo;
    private long itemId;
    private long instanceId;
    private String publishNo;

    @BeforeEach
    void publishThroughTheHttpApi() throws Exception {
        cleanDatabase();
        engine.clear();

        givenUser(OWNER, "OWNER");
        givenUser(DEV, "DEV");
        givenUser(TESTER, "TEST");
        givenUser(OUTSIDER, "TEST");
        givenUser(REVIEWER, "OPS_LEAD");
        givenUser(ADMIN, "ADMIN");
        for (String user : List.of(OWNER, DEV, TESTER, OUTSIDER, REVIEWER, ADMIN)) {
            jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id)"
                + " VALUES (?, 'ALL', 0)", user);
        }

        long subsystemId = givenSubsystem("SCC-P2-E2E");
        taskId = givenTask(subsystemId, "202611-P2-E2E");
        unitId = givenUnit(subsystemId, "p2-e2e-api");
        givenMember(taskId, DEV, "DEV");
        givenMember(taskId, TESTER, "TEST");
        givenMember(taskId, OWNER, "OWNER");
        givenBinding(unitId, "BUILD", "P-BUILD-E2E", "{\"branch\":\"" + BRANCH + "\"}");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-E2E", null);
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-E2E", null);
        testDeOne = givenDe(unitId, "TEST", "p2-e2e-sit", 0);
        testDeTwo = givenDe(unitId, "TEST", "p2-e2e-uat", 0);
        givenDe(unitId, "PROD", "p2-e2e-prod", 1);

        itemId = createItem();
        publishNo = publish(itemId);
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE order_id = (SELECT id FROM release_order"
                + " WHERE publish_no = ?)", Long.class, publishNo);
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void the_p2_pipeline_checks_passes_the_test_and_reaches_production_admission() throws Exception {
        assertThat(handlers.nodeTypes())
            .as("P2 adds the verify handler; P3 adds the production deploy handler")
            .containsExactlyInAnyOrder(NodeType.BUILD, NodeType.TEST_DEPLOY, NodeType.VERIFY,
                NodeType.PROD_DEPLOY);

        runToChecksRunning();

        assertThat(instanceStatus())
            .as("the checks are running, the instance keeps going instead of pausing")
            .isEqualTo("RUNNING");
        assertThat(currentNode()).isEqualTo("VERIFY");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
        assertThat(nodeStatus(NodeType.PROD_DEPLOY))
            .as("production is never entered in P2")
            .isEqualTo("PENDING");
        assertThat(gateCheckCount()).isEqualTo(7);
        assertThat(gateCheckStatuses()).containsOnly("RUNNING");
        assertThat(gateStatus()).isEqualTo("CHECK_RUNNING");
        assertThat(itemStatus()).isEqualTo("TESTING");
        assertThat(engine.checkCalls).hasSize(7);
        assertThat(engine.checkCalls).extracting(GateCheckCmd::checkType)
            .containsExactlyInAnyOrder(CheckType.values());
        assertThat(engine.checkCalls).allSatisfy(cmd -> {
            assertThat(cmd.pipelineNo()).isEqualTo(pipelineNo());
            assertThat(cmd.imageDigest()).isEqualTo(DIGEST);
            assertThat(cmd.commitSha()).isEqualTo(COMMIT);
        });

        // The engine reports a clean result for every check.
        engine.reportAll(GateCheckOutcome.SUCCESS, Severity.NONE);
        pollChecks();

        assertThat(gateCheckStatuses()).containsOnly("PASS");
        assertThat(gateStatus()).isEqualTo("CHECK_PASS");
        assertThat(nodeStatus(NodeType.VERIFY))
            .as("the checks passed but the test gate has not, so the node keeps waiting")
            .isEqualTo("RUNNING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");

        // A single check can be re-run on demand ("检查项可单独刷新"): its result becomes unknown
        // again, and the gate must read "still running" until the new result arrives.
        assertOk(refreshCheck(TESTER, "SONAR"));
        assertThat(gateStatus()).isEqualTo("CHECK_RUNNING");
        assertThat(engine.checkCalls).hasSize(8);
        pollChecks();
        assertThat(gateStatus()).isEqualTo("CHECK_PASS");

        // The tester passes the test; both gates are satisfied now.
        assertOk(testPass(TESTER));

        assertThat(itemStatus())
            .as("both gates satisfied: the item is ready to be submitted")
            .isEqualTo("GATE_PENDING");
        assertThat(gateStatus()).isEqualTo("CHECK_PASS");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("SUCCESS");
        assertThat(instanceStatus())
            .as("P3 ships the production handler, so the pipeline enters PROD_DEPLOY and pauses"
                + " there until the release is approved and executed")
            .isEqualTo("PAUSED");
        assertThat(currentNode()).isEqualTo("PROD_DEPLOY");
        assertThat(nodeStatus(NodeType.PROD_DEPLOY)).isEqualTo("RUNNING");
        assertThat(statusReason()).contains("PROD_DEPLOY").contains("执行生产");
        assertThat(engine.deployCalls)
            .as("no production deploy may be triggered before the release is executed")
            .hasSize(2);

        // The generic gate resume is not the production path: executing has its own permission
        // (control:execute-prod) and its own review, so this endpoint refuses with a pointer.
        mvc.perform(post(RESUME, publishNo).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                "执行生产")));

        // The verification panel answers "why can/can't this go to production".
        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conclusion").value("PASS"))
            .andExpect(jsonPath("$.data.gateStatus").value("CHECK_PASS"))
            .andExpect(jsonPath("$.data.test").value("测试通过"))
            .andExpect(jsonPath("$.data.passed").value(7))
            .andExpect(jsonPath("$.data.total").value(7))
            .andExpect(jsonPath("$.data.maxSeverity").value("NONE"))
            .andExpect(jsonPath("$.data.groups.length()").value(2))
            .andExpect(jsonPath("$.data.groups[0].stage").value("IMAGE"))
            .andExpect(jsonPath("$.data.groups[0].checks.length()").value(4))
            .andExpect(jsonPath("$.data.groups[1].stage").value("ENV"))
            .andExpect(jsonPath("$.data.groups[1].checks.length()").value(3))
            .andExpect(jsonPath("$.data.admission.eligible").value(true))
            .andExpect(jsonPath("$.data.admission.missing.length()").value(0))
            .andExpect(jsonPath("$.data.waiver").doesNotExist())
            .andExpect(jsonPath("$.data.testPassBy").value(TESTER));

        mvc.perform(get(RELEASE, publishNo).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAUSED"))
            .andExpect(jsonPath("$.data.itemId").value(itemId))
            .andExpect(jsonPath("$.data.nodes[2].nodeType").value("VERIFY"))
            .andExpect(jsonPath("$.data.nodes[2].status").value("SUCCESS"));
    }

    @Test
    void a_high_severity_finding_fails_the_item_and_the_instance() throws Exception {
        runToChecksRunning();
        engine.reportAll(GateCheckOutcome.SUCCESS, Severity.NONE);
        engine.report(CheckType.SONAR, GateCheckOutcome.FAILED, Severity.S1, 2);

        pollChecks();

        assertThat(gateStatus()).isEqualTo("CHECK_FAIL");
        assertThat(itemStatus())
            .as("高危 is not waivable, so the item is closed out instead of waiting")
            .isEqualTo("GATE_FAILED");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(statusReason()).contains("门禁未通过").contains("高危");
        assertThat(unitLockRows()).as("a failed pipeline releases its unit").isZero();

        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.data.conclusion").value("BLOCK"))
            .andExpect(jsonPath("$.data.waivable").value(false))
            .andExpect(jsonPath("$.data.maxSeverity").value("S1"))
            .andExpect(jsonPath("$.data.admission.eligible").value(false))
            .andExpect(jsonPath("$.data.admission.missing[0]").exists());

        assertThat(waiverRequest(DEV, "高危也想豁免", Instant.now().plusSeconds(86_400)))
            .as("a 高危 finding can never be waived")
            .isEqualTo(40002);
    }

    @Test
    void a_medium_finding_waits_for_a_waiver_and_its_expiry_revokes_the_admission()
            throws Exception {
        runToChecksRunning();
        engine.reportAll(GateCheckOutcome.SUCCESS, Severity.NONE);
        engine.report(CheckType.AUTO_TEST, GateCheckOutcome.FAILED, Severity.S2, 5);
        pollChecks();

        assertThat(gateStatus()).isEqualTo("CHECK_FAIL");
        assertThat(itemStatus())
            .as("中危 can still be waived, so the pipeline waits instead of failing")
            .isEqualTo("TESTING");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");

        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.data.conclusion").value("BLOCK"))
            .andExpect(jsonPath("$.data.waivable").value(true))
            .andExpect(jsonPath("$.data.blocking").value(1))
            .andExpect(jsonPath("$.data.groups[1].checks[1].kind").value("AUTO_TEST"))
            .andExpect(jsonPath("$.data.groups[1].checks[1].waiverHint").value("可申请豁免"));

        // The tester passes; the item still cannot pass because the check gate blocks.
        assertOk(testPass(TESTER));
        assertThat(itemStatus()).isEqualTo("TESTING");

        long waiverId = waiverRequestOk(DEV, "自动化测试 5 个失败均为已知用例",
            Instant.now().plusSeconds(7 * 86_400));
        assertThat(waiverStatus()).isEqualTo("PENDING");
        assertThat(gateStatus())
            .as("a pending waiver does not count")
            .isEqualTo("CHECK_FAIL");

        assertThat(approveWaiver(DEV, waiverId))
            .as("approving needs the gate:waive permission")
            .isEqualTo(40009);

        assertOk(approveWaiver(REVIEWER, waiverId));
        assertThat(waiverStatus()).isEqualTo("APPROVED");
        assertThat(gateStatus()).isEqualTo("CHECK_WAIVED");
        assertThat(itemStatus())
            .as("with the test passed and the medium finding waived, both gates are satisfied")
            .isEqualTo("GATE_PENDING");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(currentNode()).isEqualTo("PROD_DEPLOY");

        // The waiver expires: the admission is revoked, the already-finished work is not.
        jdbc.update("UPDATE gate_waiver SET expire_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 MINUTE)"
            + " WHERE id = ?", waiverId);
        Instant now = Instant.now();
        assertThat(waiverService.expireDue(now, 50)).isEqualTo(1);

        assertThat(waiverStatus()).isEqualTo("EXPIRED");
        assertThat(gateStatus()).isEqualTo("CHECK_INVALID");
        assertThat(itemStatus())
            .as("an expired waiver revokes the admission (requirements §4.6)")
            .isEqualTo("GATE_FAILED");
        assertThat(nodeStatus(NodeType.VERIFY))
            .as("the node that already succeeded is never rolled back")
            .isEqualTo("SUCCESS");

        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.data.conclusion").value("INVALID"))
            .andExpect(jsonPath("$.data.gateStatus").value("CHECK_INVALID"))
            .andExpect(jsonPath("$.data.waiver.status").value("EXPIRED"))
            .andExpect(jsonPath("$.data.admission.eligible").value(false));
    }

    @Test
    void only_a_task_tester_may_pass_and_only_the_passer_may_revoke() throws Exception {
        runToChecksRunning();
        engine.reportAll(GateCheckOutcome.SUCCESS, Severity.NONE);
        pollChecks();

        assertThat(testPass(OUTSIDER))
            .as("a TEST role is not enough: the caller must be a member of the release task")
            .isEqualTo(40009);
        assertThat(testPass(DEV))
            .as("a developer cannot decide the test")
            .isEqualTo(40009);

        assertOk(testPass(TESTER));
        assertThat(itemStatus()).isEqualTo("GATE_PENDING");

        assertThat(testRevoke(DEV, "我想撤回"))
            .as("only the person who passed the test may revoke it (C19)")
            .isEqualTo(40009);
        assertThat(testRevoke(TESTER, "  "))
            .as("a revoke needs a reason")
            .isEqualTo(40000);

        assertOk(testRevoke(TESTER, "环境被复用了"));
        assertThat(testStatus()).isEqualTo("TEST_REVOKED");
        assertThat(itemStatus())
            .as("a revoked pass revokes the admission")
            .isEqualTo("GATE_FAILED");
        assertThat(testFailReason()).isEqualTo("环境被复用了");
        assertThat(nodeStatus(NodeType.VERIFY))
            .as("the node that already succeeded is never rolled back by a revocation")
            .isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
    }

    @Test
    void a_failed_test_fails_the_item_with_the_reason_everyone_can_read() throws Exception {
        runToChecksRunning();
        engine.reportAll(GateCheckOutcome.SUCCESS, Severity.NONE);
        pollChecks();

        assertThat(testFail(TESTER, "  "))
            .as("a failure needs a reason")
            .isEqualTo(40000);
        assertOk(testFail(TESTER, "接口返回 500，阻塞"));

        assertThat(testStatus()).isEqualTo("TEST_FAIL");
        assertThat(itemStatus()).isEqualTo("GATE_FAILED");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("FAILED");
        assertThat(statusReason()).contains("测试不通过").contains("接口返回 500");

        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.data.test").value("测试不通过"))
            .andExpect(jsonPath("$.data.testFailReason").value("接口返回 500，阻塞"))
            .andExpect(jsonPath("$.data.admission.eligible").value(false));
    }

    @Test
    void an_unreachable_engine_leaves_the_gate_not_finished_never_passed() throws Exception {
        runToChecksRunning();
        engine.failQueries = true;

        pollChecks();

        assertThat(gateCheckStatuses()).containsOnly("UNKNOWN");
        assertThat(gateStatus())
            .as("an outage is never a pass")
            .isEqualTo("CHECK_RUNNING");
        assertThat(itemStatus()).isEqualTo("TESTING");
        assertThat(instanceStatus())
            .as("the pipeline keeps waiting for the checks instead of failing the release")
            .isEqualTo("RUNNING");

        mvc.perform(get(GATE, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.data.conclusion").value("RUNNING"))
            .andExpect(jsonPath("$.data.admission.eligible").value(false))
            .andExpect(jsonPath("$.data.groups[0].checks[0].status").value("UNKNOWN"));
    }

    // ------------------------------------------------------------------ flow helpers

    /** Drives the real handlers up to "the VERIFY node is running and the checks were triggered". */
    private void runToChecksRunning() {
        // The test profile turns auto-start off, so the committed instance is still PENDING.
        orchestrator.advance(instanceId);
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(engine.buildCalls).hasSize(1);
        orchestrator.onTaskCompleted(buildTask(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS,
            null, null);
        orchestrator.onTaskCompleted(buildTask(TaskType.PKG_BUILD), NodeTaskStatus.SUCCESS,
            null, null);
        orchestrator.onTaskCompleted(buildTask(TaskType.IMG_BUILD), NodeTaskStatus.SUCCESS,
            DIGEST, null);

        assertThat(engine.deployCalls)
            .as("the TEST_DEPLOY handler triggered one deploy per TEST DE, none for PROD")
            .hasSize(2);
        assertThat(engine.deployCalls).allSatisfy(cmd -> {
            assertThat(cmd.envKind()).isEqualTo("TEST");
            assertThat(cmd.imageDigest()).isEqualTo(DIGEST);
            assertThat(cmd.taskId()).isEqualTo("P-TEST-E2E");
        });
        orchestrator.onTaskCompleted(deployTask(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(deployTask(testDeTwo), NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
        assertThat(testReadyAt()).as("the test environment became ready, so the tester may decide")
            .isNotNull();
    }

    /** Runs the gate polling round; the checks are aged so they are due for observation. */
    private void pollChecks() {
        jdbc.update("UPDATE gate_check SET updated_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 MINUTE)");
        new GateReconcileJob(lock, checkService, gateService, waiverService).pollChecks();
    }

    // ------------------------------------------------------------------ HTTP helpers

    private long createItem() throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
            .put("taskId", taskId)
            .put("unitId", unitId)
            .put("version", "P2 门禁")
            .put("planReleaseDate", PLAN_DATE.toString())
            .put("sourceBranch", CLIENT_BRANCH));
        MvcResult result = mvc.perform(post(ITEMS)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return data(result).path("itemId").asLong();
    }

    private String publish(long item) throws Exception {
        MvcResult result = mvc.perform(post(PUBLISH, item).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return data(result).path("publishNo").asText();
    }

    private int testPass(String user) throws Exception {
        return code(mvc.perform(post(TEST_PASS, itemId).header(USER_HEADER, user)).andReturn());
    }

    private int testFail(String user, String reason) throws Exception {
        return code(mvc.perform(post(TEST_FAIL, itemId)
            .header(USER_HEADER, user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", reason)))).andReturn());
    }

    private int testRevoke(String user, String reason) throws Exception {
        return code(mvc.perform(post(TEST_REVOKE, itemId)
            .header(USER_HEADER, user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", reason)))).andReturn());
    }

    private int waiverRequest(String user, String reason, Instant expireAt) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
            .put("reason", reason)
            .put("impact", "影响可控")
            .put("expireAt", expireAt.toString()));
        return code(mvc.perform(post(WAIVER, itemId)
            .header(USER_HEADER, user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn());
    }

    private long waiverRequestOk(String user, String reason, Instant expireAt) throws Exception {
        assertOk(mvc.perform(post(WAIVER, itemId)
            .header(USER_HEADER, user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(json.createObjectNode()
                .put("reason", reason)
                .put("impact", "影响可控")
                .put("expireAt", expireAt.toString())))).andReturn());
        return jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver WHERE item_id = ?",
            Long.class, itemId);
    }

    private int approveWaiver(String user, long waiverId) throws Exception {
        return code(mvc.perform(post(WAIVER + "/{waiverId}/approve", itemId, waiverId)
            .header(USER_HEADER, user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "同意")))).andReturn());
    }

    private int refreshCheck(String user, String kind) throws Exception {
        return code(mvc.perform(post(REFRESH, itemId, kind).header(USER_HEADER, user))
            .andReturn());
    }

    private void assertOk(int code) {
        assertThat(code).as("the call should succeed").isZero();
    }

    private void assertOk(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(code(result))
            .as("the call should succeed, body: %s", body)
            .isZero();
    }

    private int code(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    // ------------------------------------------------------------------ database helpers

    private String pipelineNo() {
        return jdbc.queryForObject("SELECT pipeline_no FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private long buildTask(TaskType type) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                + " AND task_type = ?", Long.class, instanceId, type.name());
        return id == null ? 0L : id;
    }

    private long deployTask(long deId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'TEST_DEPLOY'"
                + " AND target_id = ?", Long.class, instanceId, deId);
        return id == null ? 0L : id;
    }

    private String nodeStatus(NodeType type) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, type.name());
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String statusReason() {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private Instant testReadyAt() {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT test_ready_at FROM pipeline_instance WHERE id = ?", java.sql.Timestamp.class,
            instanceId);
        return value == null ? null : value.toInstant();
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private String gateStatus() {
        return jdbc.queryForObject("SELECT gate_status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private String testStatus() {
        return jdbc.queryForObject("SELECT test_status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private String testFailReason() {
        return jdbc.queryForObject("SELECT test_fail_reason FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private int gateCheckCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gate_check WHERE item_id = ?",
            Integer.class, itemId);
        return count == null ? 0 : count;
    }

    private List<String> gateCheckStatuses() {
        return jdbc.queryForList("SELECT status FROM gate_check WHERE item_id = ? ORDER BY id",
            String.class, itemId);
    }

    private String waiverStatus() {
        return jdbc.queryForObject("SELECT status FROM gate_waiver WHERE item_id = ?"
            + " ORDER BY id DESC LIMIT 1", String.class, itemId);
    }

    private int unitLockRows() {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deploy_unit_lock WHERE unit_id = ?", Integer.class, unitId);
        return rows == null ? 0 : rows;
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
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
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
    }

    // ------------------------------------------------------------------ fixtures

    private void givenUser(String userId, String role) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
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

    private void givenMember(long task, String user, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", task, user, role);
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?,"
                + " NOW(3), NOW(3))",
            subsystem, name, OWNER, MODULE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String codingTaskId, String paramsJson) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, params_json,"
                + " enabled, created_at, updated_at) VALUES (?, ?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, codingTaskId, paramsJson);
    }

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }

    // ------------------------------------------------------------------ stub engine

    /**
     * Replaces the CODING adapter: it records every trigger and answers the checks with whatever the
     * test configured. Builds/deploys report what the test asks for; the production handlers do the
     * rest.
     */
    static final class StubEngine implements CicdEngine {

        private final List<BuildCmd> buildCalls = new ArrayList<>();
        private final List<DeployCmd> deployCalls = new ArrayList<>();
        private final List<GateCheckCmd> checkCalls = new ArrayList<>();
        private final Map<CheckType, GateCheckReport> reports = new EnumMap<>(CheckType.class);

        /** When true every check query fails, the way an outage would. */
        private volatile boolean failQueries;

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            buildCalls.add(cmd);
            int seq = buildCalls.size();
            return new TriggerResult("ext-build-" + seq,
                "https://coding.example/build/" + seq, COMMIT);
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(cmd);
            int seq = deployCalls.size();
            return new TriggerResult("ext-deploy-" + seq, "https://coding.example/deploy/" + seq);
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("the end-to-end test drives callbacks");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public TriggerResult startCheck(GateCheckCmd cmd) {
            checkCalls.add(cmd);
            return new TriggerResult("chk-" + cmd.checkType().name(),
                "https://coding.example/check/" + cmd.checkType().name());
        }

        @Override
        public GateCheckReport queryCheck(String externalId, CheckType checkType) {
            if (failQueries) {
                throw EngineException.unavailable("CODING 不可达");
            }
            return reports.getOrDefault(checkType, GateCheckReport.running());
        }

        void report(CheckType type, GateCheckOutcome outcome, Severity severity, int issues) {
            reports.put(type, new GateCheckReport(outcome, severity, issues,
                outcome == GateCheckOutcome.SUCCESS ? null : "检查结果", null));
        }

        void reportAll(GateCheckOutcome outcome, Severity severity) {
            reports.clear();
            for (CheckType type : CheckType.values()) {
                report(type, outcome, severity, 0);
            }
        }

        void clear() {
            buildCalls.clear();
            deployCalls.clear();
            checkCalls.clear();
            reports.clear();
            failQueries = false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine p2EndToEndStubEngine() {
            return new StubEngine();
        }
    }
}