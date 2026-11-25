package com.sf.release.partfill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6-F design §2/§5.1: the 测试人员 / 部署人员 columns of the 发布内容 page.
 *
 * <p>Pinned here: the detail response carries {@code testers} (task members with {@code role=TEST})
 * and {@code deployers} ({@code role=OPS}), each with the joined {@code app_user.name}, ordered by
 * {@code user_id}; other member roles never appear; a task without such members yields two empty
 * arrays (not a missing field); and an archived task stays readable with its people.
 *
 * <p>Package {@code com.sf.release.partfill}, not {@code ..web..}: the web package keeps production
 * classes only. The rows are written with SQL, like {@code ReleaseQueryTest}, so the test pins the
 * read model and the HTTP contract instead of the publish flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleasePeopleListsTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "p6f-pl-admin";
    private static final String SUBSYSTEM = "SCC-P6F-PL";
    private static final String TASK = "P6F-PL-TASK";
    private static final String UNIT = "p6f-pl-unit";
    private static final String CRID = "CR-P6F-PL-1";
    private static final String PUBLISH_NO = "PO-P6F-PL-1";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);
    private static final String PATH = "/api/v1/releases/{publishNo}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long taskId;
    private long itemId;

    @BeforeEach
    void givenAnItemOfATask() {
        cleanFixtures();

        givenUser(ADMIN, "测试管理员");
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')", ADMIN);
        long subsystemId = givenSubsystem();
        taskId = givenTask(subsystemId, "ACTIVE");
        long unitId = givenUnit(subsystemId);
        itemId = givenItem(unitId);
        jdbc.update("INSERT INTO release_order (publish_no, item_id, oa_ticket_no,"
                + " planned_release_time, status, row_version, created_at)"
                + " VALUES (?, ?, 'OA-P6F-PL', '2026-11-26 10:00:00', 'INIT', 0, NOW(3))",
            PUBLISH_NO, itemId);
    }

    // ------------------------------------------------------------------ people

    @Test
    void the_detail_lists_the_test_and_ops_members_with_their_names_in_user_id_order()
            throws Exception {
        givenMember("p6f-pl-test-b", "TEST", "测试乙");
        givenMember("p6f-pl-test-a", "TEST", "测试甲");
        givenMember("p6f-pl-ops", "OPS", "部署甲");
        givenMember("p6f-pl-dev", "DEV", "开发甲");
        givenMember("p6f-pl-owner", "OWNER", "负责人甲");

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.testers.length()").value(2))
            .andExpect(jsonPath("$.data.testers[0].userId").value("p6f-pl-test-a"))
            .andExpect(jsonPath("$.data.testers[0].name").value("测试甲"))
            .andExpect(jsonPath("$.data.testers[1].userId").value("p6f-pl-test-b"))
            .andExpect(jsonPath("$.data.testers[1].name").value("测试乙"))
            .andExpect(jsonPath("$.data.deployers.length()").value(1))
            .andExpect(jsonPath("$.data.deployers[0].userId").value("p6f-pl-ops"))
            .andExpect(jsonPath("$.data.deployers[0].name").value("部署甲"));
    }

    @Test
    void a_task_without_test_or_ops_members_has_two_empty_arrays() throws Exception {
        givenMember("p6f-pl-dev", "DEV", "开发甲");

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.testers.length()").value(0))
            .andExpect(jsonPath("$.data.deployers.length()").value(0))
            .andExpect(jsonPath("$.data.testers").isArray())
            .andExpect(jsonPath("$.data.deployers").isArray());
    }

    @Test
    void an_archived_task_still_exposes_its_test_and_ops_members() throws Exception {
        jdbc.update("UPDATE release_task SET status = 'ARCHIVED' WHERE id = ?", taskId);
        givenMember("p6f-pl-test-a", "TEST", "测试甲");
        givenMember("p6f-pl-ops", "OPS", "部署甲");

        mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.testers.length()").value(1))
            .andExpect(jsonPath("$.data.testers[0].name").value("测试甲"))
            .andExpect(jsonPath("$.data.deployers.length()").value(1))
            .andExpect(jsonPath("$.data.deployers[0].name").value("部署甲"));
    }

    @Test
    void a_member_whose_user_row_is_gone_keeps_its_place_with_a_null_name() throws Exception {
        givenMember("p6f-pl-test-gone", "TEST", null);

        String body = mvc.perform(get(PATH, PUBLISH_NO).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.testers.length()").value(1))
            .andExpect(jsonPath("$.data.testers[0].userId").value("p6f-pl-test-gone"))
            .andReturn().getResponse().getContentAsString();

        // The LEFT JOIN keeps the stale member row; its name is serialized as an explicit null.
        assertThat(body).contains("\"name\":null");
    }

    // ------------------------------------------------------------------ fixtures

    private void cleanFixtures() {
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order WHERE publish_no = ?", PUBLISH_NO);
        jdbc.update("DELETE FROM release_item WHERE crid = ?", CRID);
        jdbc.update("DELETE FROM release_task_member WHERE task_id IN"
            + " (SELECT id FROM release_task WHERE name = ?)", TASK);
        jdbc.update("DELETE FROM release_task WHERE name = ?", TASK);
        jdbc.update("DELETE FROM deploy_unit WHERE name = ?", UNIT);
        jdbc.update("DELETE FROM subsystem WHERE name = ?", SUBSYSTEM);
        jdbc.update("DELETE FROM user_scope WHERE user_id LIKE 'p6f-pl-%'");
        jdbc.update("DELETE FROM user_role WHERE user_id LIKE 'p6f-pl-%'");
        jdbc.update("DELETE FROM app_user WHERE id LIKE 'p6f-pl-%'");
    }

    private void givenUser(String userId, String name) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, name);
    }

    private void givenMember(String userId, String role, String name) {
        if (name != null) {
            givenUser(userId, name);
        }
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, ?, NOW(3), NOW(3))", taskId, userId, role);
    }

    private long givenSubsystem() {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", SUBSYSTEM, ADMIN);
        return idOf("SELECT id FROM subsystem WHERE name = ?", SUBSYSTEM);
    }

    private long givenTask(long subsystemId, String status) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'P6-F 人员列', ?, ?, NOW(3), NOW(3))",
            subsystemId, TASK, ADMIN, PLAN_DATE, status);
        return idOf("SELECT id FROM release_task WHERE name = ?", TASK);
    }

    private long givenUnit(long subsystemId) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/p6f', NOW(3), NOW(3))",
            subsystemId, UNIT, ADMIN);
        return idOf("SELECT id FROM deploy_unit WHERE name = ?", UNIT);
    }

    private long givenItem(long unitId) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD-P6F', 'release/p6f', 'v1', ?, 'TESTING',"
                + " 'CHECK_FAIL', 'TEST_PENDING', 0, NOW(3), NOW(3))",
            taskId, CRID, unitId, PLAN_DATE);
        return idOf("SELECT id FROM release_item WHERE crid = ?", CRID);
    }

    private long idOf(String sql, Object arg) {
        Long id = jdbc.queryForObject(sql, Long.class, arg);
        return id == null ? 0L : id;
    }
}