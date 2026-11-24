package com.sf.release.daily;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.common.security.Permissions;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the daily release endpoints (P5-A design §5, §7 item 6): authentication, the
 * {@code release:daily} permission, the unit data scope, the business codes of the refused starts,
 * the paging contract and the detail fields.
 *
 * <p>The test lives in {@code com.sf.release.daily}, not in {@code ..web..}: the web package keeps
 * production classes only (design §7 item 8), and the daily slice owns its own HTTP contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DailyControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "d-dev";
    private static final String TESTER = "d-tester";
    private static final String OTHER_SCOPE = "d-other";
    private static final String OWNER = "01388007";
    private static final String ITEMS_PATH = "/api/v1/items/{itemId}/daily";
    private static final String DAILY_PATH = "/api/v1/daily/{pipelineNo}";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;

    private long unitId;
    private long itemId;
    private long otherUnitId;

    @BeforeEach
    void setUp() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_DAILY));
        clearSchema();

        givenUser(OWNER);
        givenUser(DEV, "DEV");
        givenUser(TESTER, "TEST");
        givenUser(OTHER_SCOPE, "DEV");
        // DEV sees the unit under test; OTHER_SCOPE has the permission but not that unit.
        givenUnitScope(DEV, "ALL", 0L);
        givenUnitScope(OTHER_SCOPE, "UNIT", 8_800_000L);

        long subsystemId = givenSubsystem("SCC-DAILY-HTTP");
        long taskId = givenTask(subsystemId, "202611-DAILY-HTTP");
        unitId = givenUnit(subsystemId, "daily-http-api");
        otherUnitId = givenUnit(subsystemId, "daily-http-other");
        for (long unit : List.of(unitId, otherUnitId)) {
            givenBinding(unit, "BUILD", "P-BUILD-DAILY");
            givenBinding(unit, "DAILY_DEPLOY", "P-DAILY-DEPLOY");
            givenBinding(unit, "TEST_DEPLOY", "P-TEST-DEPLOY");
            givenDe(unit, "DAILY", "daily-http-sit-" + unit, 0);
        }
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "日常发布",
            PLAN_DATE));
        TestUserContext.clear();
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ permission and scope

    @Test
    void start_without_authentication_is_forbidden() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void start_without_release_daily_is_forbidden() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, TESTER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString(Permissions.RELEASE_DAILY)));
    }

    @Test
    void start_outside_the_unit_scope_is_forbidden() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, OTHER_SCOPE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("部署单元")));
    }

    @Test
    void a_permitted_caller_starts_a_daily_release_and_gets_its_numbers() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pipelineNo").isNotEmpty())
            .andExpect(jsonPath("$.data.instanceId").isNumber());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_instance", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT order_id FROM pipeline_instance", Long.class))
            .as("a daily instance has no release order").isZero();
        assertThat(jdbc.queryForObject("SELECT exec_type FROM pipeline_instance", String.class))
            .isEqualTo("DAILY");
        assertThat(jdbc.queryForObject("SELECT requested_by FROM pipeline_instance",
            String.class)).isEqualTo(DEV);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deploy_unit_lock", Integer.class))
            .as("a daily release takes no unit lock").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId)).isEqualTo("PENDING");
    }

    @Test
    void a_library_item_is_refused_with_40001() throws Exception {
        long libraryItem = givenLibraryItem();

        mvc.perform(post(ITEMS_PATH, libraryItem).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("二方库")));
    }

    @Test
    void an_archived_task_is_refused_with_40001() throws Exception {
        jdbc.update("UPDATE release_task SET status = 'ARCHIVED'");

        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("任务已归档")));
    }

    @Test
    void a_unit_without_a_daily_or_test_de_is_refused_with_40001() throws Exception {
        jdbc.update("DELETE FROM env_de WHERE unit_id = ?", unitId);

        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("日常/测试环境 DE")));
    }

    // ------------------------------------------------------------------ records page

    @Test
    void records_are_paged_newest_first() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));
        String newest = jdbc.queryForObject("SELECT pipeline_no FROM pipeline_instance"
            + " ORDER BY id DESC LIMIT 1", String.class);

        mvc.perform(get(ITEMS_PATH, itemId).header(USER_HEADER, DEV)
                .param("page", "1").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(1))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].pipelineNo").value(newest))
            .andExpect(jsonPath("$.data.records[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.records[0].requestedBy").value(DEV))
            .andExpect(jsonPath("$.data.records[0].branch").value(BRANCH))
            .andExpect(jsonPath("$.data.records[0].createdAt").exists())
            .andExpect(jsonPath("$.data.records[0].imageDigest")
                .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void records_are_scoped() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get(ITEMS_PATH, itemId).header(USER_HEADER, OTHER_SCOPE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void an_invalid_page_request_is_a_business_40000() throws Exception {
        mvc.perform(get(ITEMS_PATH, itemId).header(USER_HEADER, DEV).param("size", "101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));
    }

    // ------------------------------------------------------------------ detail

    @Test
    void the_detail_returns_the_nodes_tasks_and_log_links() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));
        String pipelineNo = jdbc.queryForObject(
            "SELECT pipeline_no FROM pipeline_instance", String.class);

        mvc.perform(get(DAILY_PATH, pipelineNo).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pipelineNo").value(pipelineNo))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.requestedBy").value(DEV))
            .andExpect(jsonPath("$.data.branch").value(BRANCH))
            .andExpect(jsonPath("$.data.nodes.length()").value(2))
            .andExpect(jsonPath("$.data.nodes[0].nodeType").value("BUILD"))
            .andExpect(jsonPath("$.data.nodes[1].nodeType").value("DAILY_DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[1].tasks.length()").value(1))
            .andExpect(jsonPath("$.data.nodes[1].tasks[0].taskType").value("DEPLOY"))
            .andExpect(jsonPath("$.data.nodes[1].tasks[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data.nodes[0].tasks.length()").value(3));
    }

    @Test
    void an_unknown_pipeline_number_is_a_business_40000() throws Exception {
        mvc.perform(get(DAILY_PATH, "PL-UNKNOWN").header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void the_detail_is_scoped() throws Exception {
        mvc.perform(post(ITEMS_PATH, itemId).header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));
        String pipelineNo = jdbc.queryForObject(
            "SELECT pipeline_no FROM pipeline_instance", String.class);

        mvc.perform(get(DAILY_PATH, pipelineNo).header(USER_HEADER, OTHER_SCOPE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void the_controller_test_lives_outside_the_web_package() {
        // Design §7 item 8: controller tests must not be production-web classes.
        assertThat(getClass().getPackageName()).isEqualTo("com.sf.release.daily");
    }

    // ------------------------------------------------------------------ fixtures

    private void clearSchema() {
        for (String table : List.of("audit_log", "notification", "notification_setting",
            "coding_conn", "deploy_unit_lock", "rollback_record", "pipeline_item_failure",
            "node_task", "pipeline_node", "pipeline_instance", "env_de", "coding_task_binding",
            "release_order", "release_item", "release_task_member", "release_task", "deploy_unit",
            "subsystem", "user_scope", "user_role", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
    }

    private void givenUser(String userId, String role) {
        givenUser(userId);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
    }

    private void givenUnitScope(String userId, String scopeType, long scopeId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, ?, ?)",
            userId, scopeType, scopeId);
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
                + " 'outbound-os/daily', NOW(3), NOW(3))",
            subsystem, name, OWNER);
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

    private long givenLibraryItem() {
        long taskId = jdbc.queryForObject(
            "SELECT task_id FROM release_item WHERE id = ?", Long.class, itemId);
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " test_status, row_version, created_at, updated_at)"
                + " VALUES (?, 'CRID-LIB-HTTP', 'LIB', ?, 'P-BUILD-DAILY', 'release/lib', NULL,"
                + " '二方库', ?, 'PENDING', 'CHECK_RUNNING', 'TEST_PENDING', 0, NOW(3), NOW(3))",
            taskId, unitId, PLAN_DATE);
        return jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CRID-LIB-HTTP'",
            Long.class);
    }
}