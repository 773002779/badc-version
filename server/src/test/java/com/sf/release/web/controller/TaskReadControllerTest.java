package com.sf.release.web.controller;

import com.sf.release.ReleaseApplication;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the P1 task read API: the task list ({@code GET /tasks}), the task detail with
 * its members ({@code GET /tasks/{id}}) and the item list with the deployment unit name
 * ({@code GET /tasks/{id}/items}).
 *
 * <p><strong>Read-only.</strong> Every endpoint authenticates through the regular
 * {@code X-User-Id} header but carries no {@code @RequirePerm}; the tests therefore seed only
 * the fixture rows (subsystems, tasks, members, items, units) and count the statements of the
 * page so an N+1 regression fails here. The seeded item statuses cover the progress rule:
 * {@code DONE} counts as done, a closed-out side end is not blocking, every other status is.
 */
@SpringBootTest(classes = {ReleaseApplication.class, TaskReadControllerTest.SqlCountingConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskReadControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String READER = "t-read-http-user";
    private static final String MEMBER_DEV = "t-read-member-1";
    private static final String MEMBER_TEST = "t-read-member-2";
    private static final String MEMBER_GONE = "t-read-member-3";
    private static final String UNKNOWN_TASK = "/tasks/{id}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlStatementCounter sqlCounter;

    private long subsystemA;
    private long subsystemB;
    private long taskAlpha;
    private long taskBeta;
    private long taskGamma;
    private long unitOne;
    private long unitTwo;
    private long unitThree;

    @BeforeEach
    void seedFixtures() {
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?, ?, ?)",
            READER, MEMBER_DEV, MEMBER_TEST, MEMBER_GONE);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?, ?)",
            READER, MEMBER_DEV, MEMBER_TEST, MEMBER_GONE);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)",
            READER, MEMBER_DEV, MEMBER_TEST, MEMBER_GONE);

        givenUser(READER);
        givenUser(MEMBER_DEV, "Member One");
        givenUser(MEMBER_TEST, "Member Two");

        subsystemA = givenSubsystem("SCC-READ-A");
        subsystemB = givenSubsystem("SCC-READ-B");
        unitOne = givenUnit(subsystemA, "unit-one");
        unitTwo = givenUnit(subsystemA, "unit-two");
        unitThree = givenUnit(subsystemB, "unit-three");

        taskAlpha = givenTask(subsystemA, "ALPHA release", "alpha purpose", "2026-11-15",
            "ACTIVE", "2026-11-01 08:00:00");
        taskBeta = givenTask(subsystemA, "BETA release", null, null, "ARCHIVED",
            "2026-11-02 08:00:00");
        taskGamma = givenTask(subsystemB, "GAMMA release", "gamma purpose", "2026-12-01",
            "ACTIVE", "2026-11-03 08:00:00");

        // Inserted out of user-id order on purpose: the member list is ordered by user_id.
        givenMember(taskAlpha, MEMBER_TEST, "TEST");
        givenMember(taskAlpha, MEMBER_GONE, "OPS");
        givenMember(taskAlpha, MEMBER_DEV, "DEV");

        // alpha: 1 DONE of 4, so rate 0.25; PENDING/BUILDING block, CLOSED does not.
        givenItem(taskAlpha, unitOne, "CR-ALPHA-1", "UNIT", "v1", "release/alpha",
            "2026-11-20", "DONE", "CHECK_PASS", "2026-11-20 10:00:00", "2026-11-20 12:00:00");
        givenItem(taskAlpha, unitTwo, "CR-ALPHA-2", "UNIT", "v2", "release/alpha2",
            "2026-11-21", "PENDING", "CHECK_RUNNING", "2026-11-21 10:00:00", "2026-11-21 10:00:00");
        givenItem(taskAlpha, unitOne, "CR-ALPHA-3", "LIB", "v3", "release/alpha3",
            "2026-11-22", "CLOSED", "CHECK_RUNNING", "2026-11-22 10:00:00", "2026-11-22 10:00:00");
        givenItem(taskAlpha, unitThree, "CR-ALPHA-4", "UNIT", "v4", "release/alpha4",
            "2026-11-23", "BUILDING", "CHECK_RUNNING", "2026-11-23 10:00:00",
            "2026-11-23 11:00:00");
        // gamma: one DONE item, i.e. rate 1.0 and no blocking item.
        givenItem(taskGamma, unitThree, "CR-GAMMA-1", "UNIT", "v9", "release/gamma",
            "2026-12-02", "DONE", "CHECK_PASS", "2026-11-24 10:00:00", "2026-11-24 10:00:00");
        // beta has no item: progress is zero and not blocking.
    }

    // ------------------------------------------------------------------ GET /tasks

    @Test
    void task_page_returns_every_task_view_field_newest_first() throws Exception {
        mvc.perform(get("/tasks").header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.records.length()").value(3))
            .andExpect(jsonPath("$.data.records[0].id").value(taskGamma))
            .andExpect(jsonPath("$.data.records[0].name").value("GAMMA release"))
            .andExpect(jsonPath("$.data.records[0].subsystemId").value(subsystemB))
            .andExpect(jsonPath("$.data.records[0].subsystemName").value("SCC-READ-B"))
            .andExpect(jsonPath("$.data.records[0].creator").value(READER))
            .andExpect(jsonPath("$.data.records[0].planFinishDate").value("2026-12-01"))
            .andExpect(jsonPath("$.data.records[0].purpose").value("gamma purpose"))
            .andExpect(jsonPath("$.data.records[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.records[0].createdAt")
                .value(containsString("2026-11-03T08:00")))
            .andExpect(jsonPath("$.data.records[0].done").value(1))
            .andExpect(jsonPath("$.data.records[0].total").value(1))
            .andExpect(jsonPath("$.data.records[0].rate").value(1.0))
            .andExpect(jsonPath("$.data.records[0].hasBlocking").value(false))
            .andExpect(jsonPath("$.data.records[1].id").value(taskBeta))
            .andExpect(jsonPath("$.data.records[1].done").value(0))
            .andExpect(jsonPath("$.data.records[1].total").value(0))
            .andExpect(jsonPath("$.data.records[1].rate").value(0.0))
            .andExpect(jsonPath("$.data.records[1].hasBlocking").value(false))
            .andExpect(jsonPath("$.data.records[2].id").value(taskAlpha));
    }

    @Test
    void task_page_paginates_and_reports_the_requested_page() throws Exception {
        mvc.perform(get("/tasks?pageNo=1&pageSize=2").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(2))
            .andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(taskGamma))
            .andExpect(jsonPath("$.data.records[1].id").value(taskBeta));

        mvc.perform(get("/tasks?pageNo=2&pageSize=2").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.pageNo").value(2))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(taskAlpha));
    }

    @Test
    void the_frontend_url_with_empty_filter_values_is_the_unfiltered_first_page() throws Exception {
        mvc.perform(get("/tasks?pageNo=1&pageSize=10&subsystemId=&status=&keyword=")
                .header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.records.length()").value(3))
            .andExpect(jsonPath("$.data.records[0].id").value(taskGamma));
    }

    @Test
    void task_page_filters_by_subsystem_status_and_keyword() throws Exception {
        mvc.perform(get("/tasks?subsystemId=" + subsystemA).header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(taskBeta))
            .andExpect(jsonPath("$.data.records[1].id").value(taskAlpha));

        mvc.perform(get("/tasks?status=ACTIVE").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(taskGamma))
            .andExpect(jsonPath("$.data.records[1].id").value(taskAlpha));

        mvc.perform(get("/tasks?keyword=beta").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(taskBeta));

        mvc.perform(get("/tasks?subsystemId=" + subsystemA + "&status=ACTIVE&keyword=ALPHA")
                .header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(taskAlpha));
    }

    @Test
    void task_page_keyword_is_a_parameter_and_an_unknown_subsystem_is_an_empty_page()
            throws Exception {
        mvc.perform(get("/tasks").param("keyword", "' OR 1=1 --").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(get("/tasks?subsystemId=999999").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void task_page_rejects_an_invalid_status_filter() throws Exception {
        mvc.perform(get("/tasks?status=NOPE").header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.status").value("NOPE"));
    }

    @Test
    void task_page_aggregates_the_progress_of_the_page_without_n_plus_one() throws Exception {
        sqlCounter.reset();

        mvc.perform(get("/tasks").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.data.records[2].done").value(1))
            .andExpect(jsonPath("$.data.records[2].total").value(4))
            .andExpect(jsonPath("$.data.records[2].rate").value(0.25))
            .andExpect(jsonPath("$.data.records[2].hasBlocking").value(true));

        assertThat(sqlCounter.count())
            .as("count + page + subsystem names + one grouped progress query")
            .isEqualTo(4);

        sqlCounter.reset();
        mvc.perform(get("/tasks?pageNo=1&pageSize=1").header(USER_HEADER, READER))
            .andExpect(jsonPath("$.data.records.length()").value(1));
        assertThat(sqlCounter.count())
            .as("the statement count does not grow with the number of tasks on the page")
            .isEqualTo(4);
    }

    // ------------------------------------------------------------------ GET /tasks/{id}

    @Test
    void task_detail_returns_the_task_progress_members_and_item_count() throws Exception {
        mvc.perform(get(UNKNOWN_TASK, taskAlpha).header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.task.id").value(taskAlpha))
            .andExpect(jsonPath("$.data.task.name").value("ALPHA release"))
            .andExpect(jsonPath("$.data.task.subsystemId").value(subsystemA))
            .andExpect(jsonPath("$.data.task.subsystemName").value("SCC-READ-A"))
            .andExpect(jsonPath("$.data.task.creator").value(READER))
            .andExpect(jsonPath("$.data.task.planFinishDate").value("2026-11-15"))
            .andExpect(jsonPath("$.data.task.purpose").value("alpha purpose"))
            .andExpect(jsonPath("$.data.task.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.task.done").value(1))
            .andExpect(jsonPath("$.data.task.total").value(4))
            .andExpect(jsonPath("$.data.task.rate").value(0.25))
            .andExpect(jsonPath("$.data.task.hasBlocking").value(true))
            .andExpect(jsonPath("$.data.itemCount").value(4))
            .andExpect(jsonPath("$.data.members.length()").value(3))
            .andExpect(jsonPath("$.data.members[0].userId").value(MEMBER_DEV))
            .andExpect(jsonPath("$.data.members[0].name").value("Member One"))
            .andExpect(jsonPath("$.data.members[0].role").value("DEV"))
            .andExpect(jsonPath("$.data.members[1].userId").value(MEMBER_TEST))
            .andExpect(jsonPath("$.data.members[1].name").value("Member Two"))
            .andExpect(jsonPath("$.data.members[1].role").value("TEST"))
            .andExpect(jsonPath("$.data.members[2].userId").value(MEMBER_GONE))
            .andExpect(jsonPath("$.data.members[2].role").value("OPS"));
    }

    @Test
    void a_member_without_an_app_user_row_keeps_a_null_name() throws Exception {
        MvcResult result = mvc.perform(get(UNKNOWN_TASK, taskAlpha).header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.members[2].name").doesNotExist())
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"name\":null");
    }

    // ------------------------------------------------------------------ GET /tasks/{id}/items

    @Test
    void task_items_join_the_unit_name_and_are_newest_first() throws Exception {
        mvc.perform(get("/tasks/{id}/items", taskAlpha).header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(4))
            .andExpect(jsonPath("$.data[0].crid").value("CR-ALPHA-4"))
            .andExpect(jsonPath("$.data[0].unitId").value(unitThree))
            .andExpect(jsonPath("$.data[0].unitName").value("unit-three"))
            .andExpect(jsonPath("$.data[0].type").value("UNIT"))
            .andExpect(jsonPath("$.data[0].version").value("v4"))
            .andExpect(jsonPath("$.data[0].sourceBranch").value("release/alpha4"))
            .andExpect(jsonPath("$.data[0].planReleaseDate").value("2026-11-23"))
            .andExpect(jsonPath("$.data[0].status").value("BUILDING"))
            .andExpect(jsonPath("$.data[0].gateStatus").value("CHECK_RUNNING"))
            .andExpect(jsonPath("$.data[0].createdAt")
                .value(containsString("2026-11-23T10:00")))
            .andExpect(jsonPath("$.data[0].updatedAt")
                .value(containsString("2026-11-23T11:00")))
            .andExpect(jsonPath("$.data[1].crid").value("CR-ALPHA-3"))
            .andExpect(jsonPath("$.data[2].crid").value("CR-ALPHA-2"))
            .andExpect(jsonPath("$.data[3].crid").value("CR-ALPHA-1"))
            .andExpect(jsonPath("$.data[3].unitName").value("unit-one"))
            .andExpect(jsonPath("$.data[3].status").value("DONE"))
            .andExpect(jsonPath("$.data[3].gateStatus").value("CHECK_PASS"));
    }

    @Test
    void task_items_of_a_task_without_items_are_an_empty_list() throws Exception {
        mvc.perform(get("/tasks/{id}/items", taskBeta).header(USER_HEADER, READER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------ errors and authentication

    @Test
    void unknown_task_is_a_parameter_error_naming_the_task_id() throws Exception {
        mvc.perform(get(UNKNOWN_TASK, 999_999L).header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.taskId").value(999_999));

        mvc.perform(get("/tasks/{id}/items", 999_999L).header(USER_HEADER, READER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.taskId").value(999_999));
    }

    @Test
    void all_three_reads_require_authentication() throws Exception {
        mvc.perform(get("/tasks"))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(get(UNKNOWN_TASK, taskAlpha))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(get("/tasks/{id}/items", taskAlpha))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ fixtures

    private void givenUser(String userId, String... name) {
        String displayName = name.length == 0 ? "用户" + userId : name[0];
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, displayName);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, READER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?, NOW(3), NOW(3))",
            subsystemId, name, READER, "module/" + name);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystemId, String name, String purpose, String planFinishDate,
                           String status, String createdAt) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            subsystemId, name, READER, purpose, planFinishDate, status, createdAt, createdAt);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystemId, name);
        return id == null ? 0L : id;
    }

    private void givenMember(long taskId, String userId, String role) {
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    private void givenItem(long taskId, long unitId, String crid, String type, String version,
                           String sourceBranch, String planReleaseDate, String status,
                           String gateStatus, String createdAt, String updatedAt) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'P-BUILD-READ', ?, NULL, ?, ?, ?, ?, 0, ?, ?)",
            taskId, crid, type, unitId, sourceBranch, version, planReleaseDate, status,
            gateStatus, createdAt, updatedAt);
    }

    // ------------------------------------------------------------------ SQL statement counting

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