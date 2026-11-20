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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 子系统管理: JSON mapping, validation envelope, permission enforcement and
 * the {@code /subsystems} contract, using the real interceptor and test schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubsystemControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "t4-admin";
    private static final String DEV = "t4-dev";
    private static final String OWNER = "01388007";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", ADMIN, DEV);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", ADMIN, DEV, OWNER);
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        givenUser(OWNER);
    }

    @Test
    void create_read_update_and_page_through_http() throws Exception {
        MvcResult created = mvc.perform(post("/subsystems")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" SCC-PORTAL-CORE-OS \",\"ownerId\":\"" + OWNER + "\","
                    + "\"description\":\"核心门户\",\"gitUrl\":\"git@git.sf:scc/portal.git\","
                    + "\"codingProject\":\"SCC-PORTAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
            .path("data").asLong();

        mvc.perform(get("/subsystems/{id}", id).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("SCC-PORTAL-CORE-OS"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.description").value("核心门户"));

        mvc.perform(put("/subsystems/{id}", id)
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SCC-PORTAL-CORE-OS-V2\",\"ownerId\":\"" + OWNER + "\","
                    + "\"status\":\"INACTIVE\",\"description\":\"新版\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/subsystems/{id}", id).header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.name").value("SCC-PORTAL-CORE-OS-V2"))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mvc.perform(get("/subsystems")
                .header(USER_HEADER, ADMIN)
                .param("keyword", "PORTAL")
                .param("pageNo", "1")
                .param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].name").value("SCC-PORTAL-CORE-OS-V2"));
    }

    @Test
    void mutation_requires_setting_manage() throws Exception {
        mvc.perform(post("/subsystems")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SCC-DENIED\",\"ownerId\":\"" + OWNER + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.traceId").isNotEmpty());

        mvc.perform(get("/subsystems").header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void blank_name_returns_40000_with_field_detail() throws Exception {
        mvc.perform(post("/subsystems")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \",\"ownerId\":\"" + OWNER + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.name").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}