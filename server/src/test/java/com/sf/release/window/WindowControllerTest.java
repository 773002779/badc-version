package com.sf.release.window;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 发布窗口配置 (tech design 4.8): the {@code /api/v1/windows} contract with the real
 * interceptor and the real test schema.
 *
 * <p>Pinned here: an unconfigured platform answers {@code configured:false} (not an error), a save
 * round-trips rules and exceptions including the short-slot warning, validation failures are 40000
 * with the offending field, and only the save needs {@code setting:manage} — reading the window and
 * asking "is this instant inside?" are open to every logged-in user because the submit dialog asks
 * before it sends anything.
 *
 * <p>The test lives in {@code com.sf.release.window}, not in a {@code ..web..} package: the
 * architecture rule forbids {@code web} depending on {@code domain}/{@code infra}, and a test in
 * {@code web} would drag those in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WindowControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "t21-admin";
    private static final String DEV = "t21-dev";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", ADMIN, DEV);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", ADMIN, DEV);
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
    }

    @Test
    void an_unconfigured_platform_answers_configured_false() throws Exception {
        mvc.perform(get("/api/v1/windows").header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(false))
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.rules").isEmpty());
    }

    @Test
    void save_then_read_round_trips_rules_and_exceptions() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"enabled\":true,"
                    + "\"rules\":[{\"dayOfWeek\":1,\"start\":\"22:00\",\"end\":\"02:00\"}],"
                    + "\"exceptions\":[{\"date\":\"2026-12-01\",\"mode\":\"CLOSED\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.data.inherited").value(false))
            .andExpect(jsonPath("$.data.rules[0].dayOfWeek").value(1))
            .andExpect(jsonPath("$.data.rules[0].start").value("22:00:00"))
            .andExpect(jsonPath("$.data.rules[0].end").value("02:00:00"))
            .andExpect(jsonPath("$.data.exceptions[0].date").value("2026-12-01"))
            .andExpect(jsonPath("$.data.exceptions[0].mode").value("CLOSED"))
            .andExpect(jsonPath("$.data.warnings").isEmpty());

        mvc.perform(get("/api/v1/windows").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.rules.length()").value(1))
            .andExpect(jsonPath("$.data.exceptions.length()").value(1));
    }

    @Test
    void a_subsystem_window_inherits_the_global_default_first() throws Exception {
        long subsystemId = givenSubsystem("W-HTTP");

        mvc.perform(get("/api/v1/windows").param("subsystemId", String.valueOf(subsystemId))
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.configured").value(false));

        saveGlobalMondayWindow();

        mvc.perform(get("/api/v1/windows").param("subsystemId", String.valueOf(subsystemId))
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.inherited").value(true))
            .andExpect(jsonPath("$.data.scope").value("GLOBAL"));

        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":" + subsystemId
                    + ",\"at\":\"2026-11-23T17:00:00Z\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.inWindow").value(true))
            .andExpect(jsonPath("$.data.scope").value("GLOBAL"))
            .andExpect(jsonPath("$.data.inherited").value(true))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"));
    }

    @Test
    void a_short_slot_is_saved_with_a_warning() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"rules\":[{\"dayOfWeek\":1,"
                    + "\"start\":\"22:00\",\"end\":\"22:10\"}]}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.rules.length()").value(1))
            .andExpect(jsonPath("$.data.warnings[0]").value(containsString("少于 30 分钟")));
    }

    @Test
    void a_rule_with_equal_start_and_end_returns_40000_with_field_detail() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"rules\":[{\"dayOfWeek\":1,"
                    + "\"start\":\"22:00\",\"end\":\"22:00\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.start").isNotEmpty());

        mvc.perform(get("/api/v1/windows").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test
    void overlapping_rules_return_40000() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"rules\":["
                    + "{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"12:00\"},"
                    + "{\"dayOfWeek\":1,\"start\":\"11:00\",\"end\":\"13:00\"}]}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.rules").isNotEmpty());
    }

    @Test
    void a_subsystem_scope_without_an_existing_subsystem_returns_40000() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"SUBSYSTEM\",\"subsystemId\":999999,"
                    + "\"rules\":[{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"10:00\"}]}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.subsystemId").isNotEmpty());
    }

    @Test
    void saving_requires_setting_manage() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"rules\":[]}"))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("setting:manage"));
    }

    @Test
    void reading_and_checking_are_open_to_any_logged_in_user() throws Exception {
        mvc.perform(get("/api/v1/windows").header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":0}"))
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void a_check_without_a_window_answers_outside_and_not_configured() throws Exception {
        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":0,\"at\":\"2026-11-23T14:00:00Z\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.inWindow").value(false))
            .andExpect(jsonPath("$.data.configured").value(false))
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.windowId").doesNotExist())
            .andExpect(jsonPath("$.data.span").doesNotExist())
            .andExpect(jsonPath("$.data.timezone").doesNotExist())
            .andExpect(jsonPath("$.data.scope").doesNotExist())
            .andExpect(jsonPath("$.data.inherited").value(false));
    }

    @Test
    void a_check_inside_the_window_reports_the_span_and_the_next_one() throws Exception {
        saveGlobalMondayWindow();

        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":0,\"at\":\"2026-11-23T17:00:00Z\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.inWindow").value(true))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.windowId").isNumber())
            .andExpect(jsonPath("$.data.scope").value("GLOBAL"))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.data.inherited").value(false))
            .andExpect(jsonPath("$.data.span.start")
                .value(startsWith("2026-11-23T22:00")))
            .andExpect(jsonPath("$.data.span.end")
                .value(startsWith("2026-11-24T02:00")))
            .andExpect(jsonPath("$.data.next.start")
                .value(startsWith("2026-11-30T22:00")));
    }

    @Test
    void a_negative_subsystem_id_is_40000() throws Exception {
        mvc.perform(get("/api/v1/windows").param("subsystemId", "-1")
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.subsystemId").isNotEmpty());

        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":-1,\"at\":\"2026-11-23T14:00:00Z\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.subsystemId").isNotEmpty());
    }

    @Test
    void a_check_without_an_at_uses_now_and_still_answers() throws Exception {
        saveGlobalMondayWindow();

        mvc.perform(post("/api/v1/windows/check")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subsystemId\":0}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.windowId").isNumber());
    }

    @Test
    void a_missing_request_header_is_40009() throws Exception {
        mvc.perform(get("/api/v1/windows"))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ fixtures

    private void saveGlobalMondayWindow() throws Exception {
        mvc.perform(put("/api/v1/windows")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"enabled\":true,"
                    + "\"rules\":[{\"dayOfWeek\":1,\"start\":\"22:00\",\"end\":\"02:00\"}]}"))
            .andExpect(jsonPath("$.code").value(0));
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, 't21-owner', 'ACTIVE', NOW(3), NOW(3))", name);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}