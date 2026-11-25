package com.sf.release.todos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP seam of the 待办中心 (P5-C design section 4): the four group keys, the string-only item DTO,
 * the {@code limit} boundary, the 40009 of an unauthenticated request and the 200-with-empty-groups
 * answer of an empty database.
 *
 * <p>Package {@code com.sf.release.todos}, not {@code ..web..}: the architecture rule forbids the web
 * package from depending on {@code infra}, and these tests write the real schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodoControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "todo-ctrl-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long subsystem;
    private long unit;

    @BeforeEach
    void setUp() {
        TodoFixtures.clean(jdbc);
        TodoFixtures.givenUser(jdbc, ADMIN, "ADMIN");
        subsystem = TodoFixtures.givenSubsystem(jdbc, "TODO-CTRL-SYS", ADMIN);
        unit = TodoFixtures.givenUnit(jdbc, subsystem, "todo-ctrl-unit", ADMIN);
    }

    @Test
    void an_empty_database_answers_200_with_four_zero_groups() throws Exception {
        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.test.count").value(0))
            .andExpect(jsonPath("$.data.approval.count").value(0))
            .andExpect(jsonPath("$.data.execute.count").value(0))
            .andExpect(jsonPath("$.data.mine.count").value(0))
            .andExpect(jsonPath("$.data.test.items.length()").value(0))
            .andExpect(jsonPath("$.data.approval.items.length()").value(0))
            .andExpect(jsonPath("$.data.execute.items.length()").value(0))
            .andExpect(jsonPath("$.data.mine.items.length()").value(0));
    }

    @Test
    void the_items_are_strings_and_carry_the_server_side_label_and_link() throws Exception {
        long task = TodoFixtures.givenTask(jdbc, subsystem, "todo-ctrl-task", ADMIN,
            Instant.now().minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, ADMIN, "TEST");
        long item = TodoFixtures.givenItem(jdbc, task, unit, "CR-CTRL-1", "TESTING", "TEST_FAIL",
            Instant.now().minusSeconds(3600));
        long order = TodoFixtures.givenOrder(jdbc, item, "PO-CTRL-1", "TESTING",
            Instant.now().minusSeconds(3600));
        TodoFixtures.givenInstance(jdbc, order, "PL-CTRL-1", "RUNNING",
            Instant.now().minusSeconds(3 * 3600 + 120));

        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN).param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.test.count").value(1))
            .andExpect(jsonPath("$.data.test.items.length()").value(1))
            .andExpect(jsonPath("$.data.test.items[0].objectType").value("ITEM"))
            .andExpect(jsonPath("$.data.test.items[0].objectId").isString())
            .andExpect(jsonPath("$.data.test.items[0].objectId").value(String.valueOf(item)))
            .andExpect(jsonPath("$.data.test.items[0].label").value("CR-CTRL-1"))
            .andExpect(jsonPath("$.data.test.items[0].status").value("TESTING"))
            .andExpect(jsonPath("$.data.test.items[0].statusLabel").value("测试中"))
            .andExpect(jsonPath("$.data.test.items[0].waitingSince").isString())
            .andExpect(jsonPath("$.data.test.items[0].waitingSince").value(containsString("T")))
            .andExpect(jsonPath("$.data.test.items[0].waitingHours").isString())
            .andExpect(jsonPath("$.data.test.items[0].waitingHours").value("3"))
            .andExpect(jsonPath("$.data.test.items[0].unitId").isString())
            .andExpect(jsonPath("$.data.test.items[0].unitId").value(String.valueOf(unit)))
            .andExpect(jsonPath("$.data.test.items[0].link")
                .value("/tasks/" + task + "?item=" + item))
            .andExpect(jsonPath("$.data.mine.count").value(1))
            .andExpect(jsonPath("$.data.mine.items[0].objectType").value("TASK"))
            .andExpect(jsonPath("$.data.mine.items[0].link").value("/tasks/" + task));
    }

    @Test
    void the_default_limit_is_five_and_twenty_is_the_ceiling() throws Exception {
        long task = TodoFixtures.givenTask(jdbc, subsystem, "todo-ctrl-limit-task", ADMIN,
            Instant.now().minusSeconds(3600));
        TodoFixtures.givenMember(jdbc, task, ADMIN, "TEST");
        for (int index = 1; index <= 6; index++) {
            long item = TodoFixtures.givenItem(jdbc, task, unit, "CR-CTRL-LIMIT-" + index,
                "TESTING", "TEST_FAIL", Instant.now().minusSeconds(index * 3600L));
            long order = TodoFixtures.givenOrder(jdbc, item, "PO-CTRL-LIMIT-" + index, "TESTING",
                Instant.now().minusSeconds(index * 3600L));
            TodoFixtures.givenInstance(jdbc, order, "PL-CTRL-LIMIT-" + index, "RUNNING",
                Instant.now().minusSeconds(index * 3600L));
        }

        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.test.count").value(6))
            .andExpect(jsonPath("$.data.test.items.length()").value(5));

        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN).param("limit", "20"))
            .andExpect(jsonPath("$.data.test.items.length()").value(6));
    }

    @Test
    void a_limit_outside_one_to_twenty_is_40000() throws Exception {
        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN).param("limit", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN).param("limit", "21"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));

        mvc.perform(get("/api/v1/todos").header(USER_HEADER, ADMIN).param("limit", "abc"))
            .andExpect(status().is(HttpStatus.BAD_REQUEST.value()))
            .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void a_request_without_identity_is_forbidden() throws Exception {
        mvc.perform(get("/api/v1/todos"))
            .andExpect(jsonPath("$.code").value(40009));
    }
}