package com.sf.release.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 发布任务与成员: authentication and the {@code task:create} gate (40009), the
 * create/add-members JSON mapping, the zero progress of a task without items and the
 * product-recipient read used by freeze notifications (C22).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "t7-http-dev";
    private static final String TESTER = "t7-http-test";
    private static final String CREATOR = "01388007";
    private static final String PRODUCT = "01380484";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?)", DEV, TESTER);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", DEV, TESTER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)", DEV, TESTER, CREATOR, PRODUCT);
        givenUser(DEV, "DEV");
        givenUser(TESTER, "TEST");
        givenUser(CREATOR);
        givenUser(PRODUCT);
        subsystemId = givenSubsystem("SCC-QUOTA");
    }

    @Test
    void task_mutations_require_authentication_and_task_create() throws Exception {
        mvc.perform(post("/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(taskBody("202609-V6.10.12")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        // TEST holds no task:create (V2 seed), so the permission gate rejects it.
        mvc.perform(post("/tasks")
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(taskBody("202609-V6.10.12")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(taskCount()).isZero();
    }

    @Test
    void create_members_progress_and_products_flow() throws Exception {
        MvcResult result = mvc.perform(post("/tasks")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(taskBody(" 202609-V6.10.12 ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        long taskId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").asLong();
        assertThat(taskId).isPositive();
        assertThat(jdbc.queryForObject(
            "SELECT name FROM release_task WHERE id = ?", String.class, taskId))
            .isEqualTo("202609-V6.10.12");
        // The creator is the authenticated caller, not a request-supplied value.
        assertThat(jdbc.queryForObject(
            "SELECT creator FROM release_task WHERE id = ?", String.class, taskId))
            .isEqualTo(DEV);

        mvc.perform(post("/tasks/{id}/members", taskId)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"members\":["
                    + "{\"userId\":\"" + CREATOR + "\",\"role\":\"DEV\"},"
                    + "{\"userId\":\"" + PRODUCT + "\",\"role\":\"PRODUCT\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/tasks/{id}/progress", taskId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.done").value(0))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.rate").value(0.0))
            .andExpect(jsonPath("$.data.hasBlocking").value(false));

        mvc.perform(get("/tasks/{id}/products", taskId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0]").value(PRODUCT));

        // Reads are authenticated as well: no identity means no read.
        mvc.perform(get("/tasks/{id}/products", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        // Unknown task ids are parameter errors (the catalogue has no NOT_FOUND).
        mvc.perform(get("/tasks/{id}/progress", 999_999L).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));
    }

    private String taskBody(String name) {
        return "{\"name\":\"" + name + "\",\"subsystemId\":" + subsystemId
            + ",\"planFinishDate\":\"2026-10-12\",\"purpose\":\"新功能\"}";
    }

    private int taskCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_task", Integer.class);
        return count == null ? 0 : count;
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, CREATOR);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}