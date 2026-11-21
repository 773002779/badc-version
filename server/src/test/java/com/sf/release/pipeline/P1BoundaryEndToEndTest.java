package com.sf.release.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sf.release.ReleaseApplication;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The P1 end-to-end release (I9): item creation and publish over HTTP, then the
 * <strong>production</strong> {@link NodeHandlerRegistry} (only {@code BUILD} and
 * {@code TEST_DEPLOY}; no placeholder handler is registered) driven through the real handlers by
 * a stub engine's {@code onTaskCompleted} callbacks. The pipeline must stop at the P1 boundary:
 * the instance ends {@code PAUSED} at {@code VERIFY} with a stored reason instead of trying to
 * enter a node this phase does not implement.
 *
 * <p>The engine is a {@code @Primary} stub bean, so the production handler beans get it while the
 * rest of the wiring (registry, orchestrator, repository, Flyway schema) is untouched.
 *
 * <p><strong>Why this test lives outside {@code web}.</strong> The ArchUnit rule forbids
 * {@code web -> domain}/{@code infra}; this test drives the orchestrator and the real handlers,
 * so it belongs to the pipeline package. The HTTP read path is still exercised through MockMvc.
 */
@SpringBootTest(classes = {ReleaseApplication.class, P1BoundaryEndToEndTest.StubEngineConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P1BoundaryEndToEndTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String USER = "t-e2e-dev";
    private static final String OWNER = "01388007";
    private static final String BRANCH = "release/202611";
    private static final String CLIENT_BRANCH = "client/ignored-branch";
    private static final String MODULE = "outbound-os/quota";
    private static final String DIGEST = "sha256:p1-boundary";
    /** A SHA-256 length commit: proves the full value survives the pipeline (V14). */
    private static final String COMMIT = "9f".repeat(32);
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String ITEMS = "/api/v1/items";
    private static final String PUBLISH = "/api/v1/items/{itemId}/publish";
    private static final String RELEASE = "/api/v1/releases/{publishNo}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired StubEngine engine;

    private long taskId;
    private long unitId;
    private long testDeOne;
    private long testDeTwo;
    private long itemId;
    private long instanceId;
    private String publishNo;
    private String pipelineNo;

    @BeforeEach
    void publishThroughTheHttpApi() throws Exception {
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
        jdbc.update("DELETE FROM user_scope WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", USER, OWNER);
        engine.clear();

        givenUser(OWNER);
        givenUser(USER, "DEV");
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            USER);
        long subsystemId = givenSubsystem("SCC-P1-E2E");
        taskId = givenTask(subsystemId, "202611-P1-E2E");
        unitId = givenUnit(subsystemId, "p1-e2e-api");
        // C21: the only branch the platform may use lives on the CODING build binding.
        givenBinding(unitId, "BUILD", "P-BUILD-E2E",
            "{\"branch\":\"" + BRANCH + "\"}");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-E2E", null);
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-E2E", null);
        testDeOne = givenDe(unitId, "TEST", "p1-e2e-sit", 0);
        testDeTwo = givenDe(unitId, "TEST", "p1-e2e-uat", 0);
        givenDe(unitId, "PROD", "p1-e2e-prod", 1);

        itemId = createItem();
        Publish published = publish(itemId);
        publishNo = published.publishNo();
        pipelineNo = published.pipelineNo();
        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", pipelineNo);
        instanceId = ((Number) instance.get("id")).longValue();
    }

    @Test
    void the_p1_pipeline_builds_deploys_test_and_pauses_at_verify() throws Exception {
        assertThat(handlers.nodeTypes())
            .as("only the production handlers exist: no placeholder fakes SUCCESS")
            .containsExactlyInAnyOrder(NodeType.BUILD, NodeType.TEST_DEPLOY);
        assertThat(jdbc.queryForObject("SELECT source_branch FROM release_item WHERE id = ?",
            String.class, itemId))
            .as("the client-supplied branch was ignored, the binding branch is used")
            .isEqualTo(BRANCH);

        orchestrator.advance(instanceId);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(engine.buildCalls).hasSize(1);
        BuildCmd build = engine.buildCalls.get(0);
        assertThat(build.taskId()).isEqualTo("P-BUILD-E2E");
        assertThat(build.branch()).isEqualTo(BRANCH);
        assertThat(build.pipelineNo()).isEqualTo(pipelineNo);

        orchestrator.onTaskCompleted(buildTask(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS,
            null, null);
        orchestrator.onTaskCompleted(buildTask(TaskType.PKG_BUILD), NodeTaskStatus.SUCCESS,
            null, null);
        orchestrator.onTaskCompleted(buildTask(TaskType.IMG_BUILD), NodeTaskStatus.SUCCESS,
            DIGEST, null);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(engine.deployCalls)
            .as("the TEST_DEPLOY handler triggered one deploy per TEST DE, none for PROD")
            .hasSize(2);
        assertThat(engine.deployCalls).allSatisfy(cmd -> {
            assertThat(cmd.envKind()).isEqualTo("TEST");
            assertThat(cmd.imageDigest()).isEqualTo(DIGEST);
            assertThat(cmd.taskId()).isEqualTo("P-TEST-E2E");
        });
        assertThat(engine.deployCalls).extracting(DeployCmd::deName)
            .containsExactlyInAnyOrder("p1-e2e-sit", "p1-e2e-uat");

        orchestrator.onTaskCompleted(deployTask(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(deployTask(testDeTwo), NodeTaskStatus.SUCCESS, null, null);

        // ---- the P1 boundary ----
        assertThat(instanceStatus())
            .as("VERIFY has no handler in phase 1, so the instance pauses instead of failing")
            .isEqualTo("PAUSED");
        assertThat(currentNode())
            .as("the blocked node stays the current node")
            .isEqualTo("VERIFY");
        assertThat(statusReason())
            .as("the pause reason is stored on the row (V13)")
            .contains("VERIFY")
            .contains("NodeHandler");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.VERIFY))
            .as("a node this phase cannot run is never entered")
            .isEqualTo("PENDING");
        assertThat(nodeStatus(NodeType.PROD_DEPLOY)).isEqualTo("PENDING");
        assertThat(verifyCheckTaskCount()).isEqualTo(7);
        assertThat(verifyCheckTasksPending()).isTrue();
        assertThat(unitLockRows())
            .as("a paused pipeline releases its deployment unit")
            .isZero();
        assertThat(itemStatus()).isEqualTo("TESTING");
        assertThat(testReadyAt()).as("both TEST DEs succeeded, so the ready claim was written")
            .isNotNull();
        assertThat(imageDigest()).isEqualTo(DIGEST);
        assertThat(commitSha())
            .as("a 64-character commit is stored in full (V14)")
            .isEqualTo(COMMIT)
            .hasSize(64);
        assertThat(engine.deployCalls).extracting(DeployCmd::deName)
            .as("no production deploy is triggered in phase 1")
            .doesNotContain("p1-e2e-prod");

        // ---- the HTTP read path sees the same execution ----
        mvc.perform(get(RELEASE, publishNo).header(USER_HEADER, USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").value(publishNo))
            .andExpect(jsonPath("$.data.pipelineNo").value(pipelineNo))
            .andExpect(jsonPath("$.data.status").value("PAUSED"))
            .andExpect(jsonPath("$.data.imageDigest").value(DIGEST))
            .andExpect(jsonPath("$.data.commitSha").value(COMMIT))
            .andExpect(jsonPath("$.data.nodes.length()").value(4))
            .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
            .andExpect(jsonPath("$.data.nodes[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[0].tasks.length()").value(3))
            .andExpect(jsonPath("$.data.nodes[1].nodeType").value("TEST_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[1].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[1].tasks.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[1].tasks[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[1].tasks[1].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[2].nodeType").value("VERIFY"))
            .andExpect(jsonPath("$.data.nodes[2].status").value("PENDING"))
            .andExpect(jsonPath("$.data.nodes[3].nodeType").value("PROD_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[3].status").value("PENDING"));
    }

    // ------------------------------------------------------------------ HTTP helpers

    private long createItem() throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
            .put("taskId", taskId)
            .put("unitId", unitId)
            .put("version", "P1 边界")
            .put("planReleaseDate", PLAN_DATE.toString())
            .put("sourceBranch", CLIENT_BRANCH));
        MvcResult result = mvc.perform(post(ITEMS)
                .header(USER_HEADER, USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return data(result).path("itemId").asLong();
    }

    private Publish publish(long item) throws Exception {
        MvcResult result = mvc.perform(post(PUBLISH, item).header(USER_HEADER, USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").isNotEmpty())
            .andExpect(jsonPath("$.data.pipelineNo").isNotEmpty())
            .andReturn();
        JsonNode data = data(result);
        return new Publish(data.path("publishNo").asText(), data.path("pipelineNo").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private record Publish(String publishNo, String pipelineNo) {
    }

    // ------------------------------------------------------------------ pipeline state helpers

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

    private String imageDigest() {
        return jdbc.queryForObject("SELECT image_digest FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String commitSha() {
        return jdbc.queryForObject("SELECT commit_sha FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private Timestamp testReadyAt() {
        return jdbc.queryForObject("SELECT test_ready_at FROM pipeline_instance WHERE id = ?",
            Timestamp.class, instanceId);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private int unitLockRows() {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deploy_unit_lock WHERE unit_id = ?", Integer.class, unitId);
        return rows == null ? 0 : rows;
    }

    private int verifyCheckTaskCount() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM node_task WHERE instance_id = ? AND node_type = 'VERIFY'"
                + " AND task_type = 'CHECK'", Integer.class, instanceId);
        return count == null ? 0 : count;
    }

    private boolean verifyCheckTasksPending() {
        Integer pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM node_task WHERE instance_id = ? AND node_type = 'VERIFY'"
                + " AND task_type = 'CHECK' AND status = 'PENDING'", Integer.class, instanceId);
        return pending != null && pending == 7;
    }

    // ------------------------------------------------------------------ fixtures

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
        if (roles != null) {
            for (String role : roles) {
                jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
            }
        }
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
     * Replaces the CODING adapter for this test: it records the triggers (so their content can be
     * asserted) and answers with distinct external ids. The production handlers do the rest; no
     * handler is registered by the test.
     */
    static final class StubEngine implements CicdEngine {

        private final List<BuildCmd> buildCalls = new ArrayList<>();
        private final List<DeployCmd> deployCalls = new ArrayList<>();

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

        void clear() {
            buildCalls.clear();
            deployCalls.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        /**
         * {@code @Primary} so the production handler beans get the stub while the CODING adapter
         * bean (and the rest of the wiring) stays intact.
         */
        @Bean
        @Primary
        StubEngine p1EndToEndStubEngine() {
            return new StubEngine();
        }
    }
}