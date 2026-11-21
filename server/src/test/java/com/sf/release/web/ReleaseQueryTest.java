package com.sf.release.web;

import com.sf.release.ReleaseApplication;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the 发布内容 query (Task 16): the aggregated detail of one 发布单
 * ({@code GET /api/v1/releases/{publishNo}}), the rerun hand-off fields of Task 15, the
 * deliberately-null gate fields, the instance history and the query budget.
 *
 * <p><strong>Why the pipeline rows are seeded with SQL.</strong> This test lives in the
 * {@code web} package (the layer that owns the HTTP contract), and the ArchUnit rule of Task 1
 * forbids {@code web -> infra} and {@code web -> domain}. Publishing for real needs a user context
 * ({@code infra.security}) and the rerun use case needs the {@code domain} commit probe, so the
 * read-side fixtures are written directly as the rows a publish and a rerun produce: one
 * {@code release_order} + {@code release_item}, the instances and their pre-generated nodes and
 * tasks. The aggregation, the derivation rules, the scope guard and the response shape are the
 * real production code under test.
 *
 * <p><strong>Why a counting interceptor.</strong> The detail joins three tables and must issue at
 * most three statements (header, nodes, tasks) with no per-node query. A MyBatis
 * {@link Interceptor} counts every {@code Executor.query} of the request, so an N+1 regression
 * fails here. The authentication lookup of the {@code PermissionInterceptor} uses
 * {@code JdbcTemplate} and is therefore not counted.
 */
@SpringBootTest(classes = {ReleaseApplication.class, ReleaseQueryTest.SqlCountingConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseQueryTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "t16-http-dev";
    private static final String OUTSIDER = "t16-http-outsider";
    private static final String OWNER = "01388007";
    private static final String PATH = "/api/v1/releases/{publishNo}";
    private static final String RECORDS = "/api/v1/releases/{publishNo}/records";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BASE = "a".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";
    private static final String DIGEST = "sha256:t16-image";
    private static final String RELEASE_TIME = "2026-11-21 10:00:00";
    private static final String PUBLISH_NO = "PO-20261121-T16A";
    private static final String PIPELINE_NO = "PL-20261121-T16A";
    private static final String RERUN_PIPELINE_NO = "PL-20261121-T16B";
    private static final String FULL_RERUN_PIPELINE_NO = "PL-20261121-T16C";
    private static final List<String> NODE_ORDER =
        List.of("BUILD", "TEST_DEPLOY", "VERIFY", "PROD_DEPLOY");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired SqlStatementCounter sqlCounter;

    private long unitId;
    private long itemId;

    @BeforeEach
    void resetFixtures() {
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
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?)", DEV, OUTSIDER);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", DEV, OUTSIDER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", DEV, OUTSIDER);

        givenUser(DEV, "ADMIN");
        givenUser(OUTSIDER, null);
        long subsystemId = givenSubsystem("SCC-T16");
        long taskId = givenTask(subsystemId, "202611-T16");
        unitId = givenUnit(subsystemId, "t16-api");
        givenBinding(unitId, "BUILD", "P-BUILD-T16");
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "发布内容查询",
            PLAN_DATE));
    }

    @Test
    void release_detail_aggregates_the_four_nodes_in_pipeline_order() throws Exception {
        givenPublishedInstance();

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").value(PUBLISH_NO))
            .andExpect(jsonPath("$.data.pipelineNo").value(PIPELINE_NO))
            .andExpect(jsonPath("$.data.status").value("RUNNING"))
            .andExpect(jsonPath("$.data.triggerType").value("INITIAL"))
            .andExpect(jsonPath("$.data.commitSha").value(BASE))
            .andExpect(jsonPath("$.data.imageDigest").value(DIGEST))
            .andExpect(jsonPath("$.data.fullRerun").doesNotExist())
            .andExpect(jsonPath("$.data.rerunFromNode").doesNotExist())
            .andExpect(jsonPath("$.data.order.oaTicketNo").value("OA-16-1"))
            .andExpect(jsonPath("$.data.order.plannedReleaseTime")
                .value(containsString("2026-11-21T10:00")))
            .andExpect(jsonPath("$.data.nodes.length()").value(4))
            .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
            .andExpect(jsonPath("$.data.nodes[0].seqNo").value(0))
            .andExpect(jsonPath("$.data.nodes[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[0].tasks.length()").value(3))
            .andExpect(jsonPath("$.data.nodes[0].tasks[0].taskType").value("CODE_CHECK"))
            .andExpect(jsonPath("$.data.nodes[0].tasks[2].taskType").value("IMG_BUILD"))
            .andExpect(jsonPath("$.data.nodes[0].tasks[2].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.nodes[0].tasks[2].durationMs").value(7))
            .andExpect(jsonPath("$.data.nodes[0].tasks[2].resultSummary").value("build ok"))
            .andExpect(jsonPath("$.data.nodes[0].tasks[2].externalUrl")
                .value("https://coding.example/job/16"))
            .andExpect(jsonPath("$.data.nodes[1].nodeType").value("TEST_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[1].tasks.length()").value(1))
            .andExpect(jsonPath("$.data.nodes[1].tasks[0].taskType").value("DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[2].nodeType").value("VERIFY"))
            .andExpect(jsonPath("$.data.nodes[2].tasks.length()").value(7))
            .andExpect(jsonPath("$.data.nodes[2].tasks[6].taskType").value("CHECK"))
            .andExpect(jsonPath("$.data.nodes[2].tasks[6].targetId").value(7))
            .andExpect(jsonPath("$.data.nodes[3].nodeType").value("PROD_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[3].tasks.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[3].tasks[0].batchNo").value(1))
            .andExpect(jsonPath("$.data.nodes[3].tasks[1].batchNo").value(2));
    }

    @Test
    void gate_fields_are_present_and_null_until_the_gate_task_lands() throws Exception {
        givenPublishedInstance();

        MvcResult result = mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.gateStatus").doesNotExist())
            .andReturn();
        // "present and null" rather than "absent": P2 fills the same field name.
        assertThat(result.getResponse().getContentAsString()).contains("\"gateStatus\":null");
    }

    @Test
    void release_detail_requires_authentication() throws Exception {
        givenPublishedInstance();

        mvc.perform(get(PATH, PUBLISH_NO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void unknown_publish_number_is_a_business_error_naming_the_number() throws Exception {
        mvc.perform(get(PATH, "PO-20261121-NOSUCH").header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.publishNo").value("PO-20261121-NOSUCH"))
            .andExpect(jsonPath("$.message").value(containsString("PO-20261121-NOSUCH")));
    }

    @Test
    void a_user_outside_the_unit_scope_cannot_read_the_release() throws Exception {
        givenPublishedInstance();

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, OUTSIDER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void rerun_instance_exposes_the_resume_node_and_is_not_a_full_rerun() throws Exception {
        givenPublishedInstance();
        givenRerunInstance(RERUN_PIPELINE_NO, "RERUN_FROM_NODE", DIGEST, BASE,
            List.of("SKIPPED", "PENDING", "PENDING", "PENDING"));

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pipelineNo").value(RERUN_PIPELINE_NO))
            .andExpect(jsonPath("$.data.triggerType").value("RERUN_FROM_NODE"))
            .andExpect(jsonPath("$.data.fullRerun").value(false))
            .andExpect(jsonPath("$.data.rerunFromNode").value("TEST_DEPLOY"))
            .andExpect(jsonPath("$.data.imageDigest").value(DIGEST))
            .andExpect(jsonPath("$.data.commitSha").value(BASE))
            .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
            .andExpect(jsonPath("$.data.nodes[0].status").value("SKIPPED"))
            .andExpect(jsonPath("$.data.nodes[1].nodeType").value("TEST_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[1].status").value("PENDING"));
    }

    @Test
    void full_rerun_instance_reports_a_build_start() throws Exception {
        givenPublishedInstance();
        givenRerunInstance(FULL_RERUN_PIPELINE_NO, "RERUN_FULL", null, null,
            List.of("PENDING", "PENDING", "PENDING", "PENDING"));

        MvcResult result = mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pipelineNo").value(FULL_RERUN_PIPELINE_NO))
            .andExpect(jsonPath("$.data.triggerType").value("RERUN_FULL"))
            .andExpect(jsonPath("$.data.fullRerun").value(true))
            .andExpect(jsonPath("$.data.rerunFromNode").value("BUILD"))
            .andExpect(jsonPath("$.data.imageDigest").doesNotExist())
            .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
            .andExpect(jsonPath("$.data.nodes[0].status").value("PENDING"))
            .andReturn();
        // A full rebuild carries no image and no commit yet; both fields stay null, not absent.
        assertThat(result.getResponse().getContentAsString())
            .contains("\"imageDigest\":null")
            .contains("\"commitSha\":null");
    }

    @Test
    void release_detail_is_aggregated_in_at_most_three_sql_statements() throws Exception {
        givenPublishedInstance();
        sqlCounter.reset();

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));

        assertThat(sqlCounter.count())
            .as("header join + node read + task read, no per-node query")
            .isEqualTo(3);
    }

    @Test
    void records_returns_the_instance_history_newest_first() throws Exception {
        givenPublishedInstance();
        givenRerunInstance(RERUN_PIPELINE_NO, "RERUN_FROM_NODE", DIGEST, BASE,
            List.of("SKIPPED", "FAILED", "SKIPPED", "SKIPPED"));
        sqlCounter.reset();

        MvcResult result = mvc.perform(get(RECORDS, PUBLISH_NO).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].pipelineNo").value(RERUN_PIPELINE_NO))
            .andExpect(jsonPath("$.data[0].triggerType").value("RERUN_FROM_NODE"))
            .andExpect(jsonPath("$.data[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data[1].pipelineNo").value(PIPELINE_NO))
            .andExpect(jsonPath("$.data[1].triggerType").value("INITIAL"))
            .andExpect(jsonPath("$.data[1].status").value("RUNNING"))
            .andExpect(jsonPath("$.data[1].startAt").value(containsString("2026-11-21T10:00")))
            .andReturn();
        // No operator column exists yet, so the field is null instead of invented.
        assertThat(result.getResponse().getContentAsString()).contains("\"operator\":null");
        assertThat(sqlCounter.count())
            .as("one ownership lookup + one instance list")
            .isEqualTo(2);
    }

    // ------------------------------------------------------------------ read fixtures

    /** The rows a first publish produces: header, the running instance and its four nodes. */
    private void givenPublishedInstance() {
        long orderId = givenOrder("OA-16-1");
        long instanceId = givenInstance(orderId, PIPELINE_NO, "INITIAL", "RUNNING", DIGEST,
            BASE, RELEASE_TIME);
        givenNodes(instanceId, List.of("SUCCESS", "PENDING", "PENDING", "PENDING"));
        givenTask(instanceId, "BUILD", "CODE_CHECK", 0, 0, "PENDING", null, null, null);
        givenTask(instanceId, "BUILD", "PKG_BUILD", 0, 0, "PENDING", null, null, null);
        givenTask(instanceId, "BUILD", "IMG_BUILD", 0, 0, "SUCCESS", 7L, "build ok",
            "https://coding.example/job/16");
        givenTask(instanceId, "TEST_DEPLOY", "DEPLOY", 0, 9001L, "PENDING", null, null, null);
        for (int checkCode = 1; checkCode <= 7; checkCode++) {
            givenTask(instanceId, "VERIFY", "CHECK", 0, checkCode, "PENDING", null, null, null);
        }
        givenTask(instanceId, "PROD_DEPLOY", "DEPLOY", 1, 9002L, "PENDING", null, null, null);
        givenTask(instanceId, "PROD_DEPLOY", "DEPLOY", 2, 9003L, "PENDING", null, null, null);
    }

    /**
     * The rows a rerun produces under the same 发布单: a new instance with the given trigger and
     * the nodes before its start node {@code SKIPPED} (Task 15 pre-generation).
     */
    private void givenRerunInstance(String pipelineNo, String triggerType, String imageDigest,
                                    String commitSha, List<String> nodeStatuses) {
        Long orderId = jdbc.queryForObject(
            "SELECT id FROM release_order WHERE publish_no = ?", Long.class, PUBLISH_NO);
        assertThat(orderId).as("release order of " + PUBLISH_NO).isNotNull();
        long instanceId = givenInstance(orderId, pipelineNo, triggerType, "PENDING",
            imageDigest, commitSha, null);
        givenNodes(instanceId, nodeStatuses);
    }

    private long givenOrder(String oaTicketNo) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, oa_ticket_no,"
                + " planned_release_time, status, row_version, created_at)"
                + " VALUES (?, ?, ?, ?, 'INIT', 0, NOW(3))",
            PUBLISH_NO, itemId, oaTicketNo, RELEASE_TIME);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_order WHERE publish_no = ?", Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private long givenInstance(long orderId, String pipelineNo, String triggerType,
                               String status, String imageDigest, String commitSha,
                               String startAt) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type,"
                + " trigger_type, current_node, status, image_digest, commit_sha, start_at,"
                + " created_at) VALUES (?, ?, 'ONLINE', ?, 'BUILD', ?, ?, ?, ?, NOW(3))",
            orderId, pipelineNo, triggerType, status, imageDigest, commitSha, startAt);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private void givenNodes(long instanceId, List<String> nodeStatuses) {
        for (int seq = 0; seq < NODE_ORDER.size(); seq++) {
            jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status)"
                + " VALUES (?, ?, ?, ?)", instanceId, NODE_ORDER.get(seq), seq,
                nodeStatuses.get(seq));
        }
    }

    private void givenTask(long instanceId, String nodeType, String taskType, int batchNo,
                           long targetId, String status, Long durationMs, String resultSummary,
                           String externalUrl) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no,"
                + " target_id, status, attempt, external_url, result_summary, duration_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
            instanceId, nodeType, taskType, batchNo, targetId, status, externalUrl,
            resultSummary, durationMs);
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private void givenUser(String userId, String role) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        if (role != null) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
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

    private void givenBinding(long unit, String kind, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, taskId);
    }

    // ------------------------------------------------------------------ SQL statement counting

    /**
     * Registers the counter as a MyBatis plugin so every {@code Executor.query} of the test
     * context is counted. Both {@code query} overloads are intercepted; MyBatis calls the
     * six-argument version internally, so one mapper call is counted once.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCountingConfig {

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return new SqlStatementCounter();
        }
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                CacheKey.class, BoundSql.class})})
    static class SqlStatementCounter implements Interceptor {

        private final AtomicInteger statements = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            statements.incrementAndGet();
            return invocation.proceed();
        }

        void reset() {
            statements.set(0);
        }

        int count() {
            return statements.get();
        }
    }
}