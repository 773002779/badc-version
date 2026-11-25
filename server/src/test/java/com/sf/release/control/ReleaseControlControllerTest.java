package com.sf.release.control;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.common.security.Permissions;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the 停止/退出/关闭/归档 endpoints (P5-B design §4, §7 items 6 and 8): the real
 * interceptor, the real permission mapping, the real schema.
 *
 * <p>Pinned here: stop needs {@code control:execute-prod} (operations may stop, a developer may
 * not), exit accepts either {@code release:publish} or {@code control:execute-prod} (operations
 * cannot carry {@code release:publish}, C20, so the endpoint pins no single permission), close needs
 * {@code release:publish} and archive needs {@code task:create} plus the task creator/administrator;
 * the unit data scope is enforced by the service; the body is always {@code {reason}}; the answers
 * echo the statuses the page refreshes to.
 *
 * <p>Package {@code com.sf.release.control}, not {@code ..web..} (design §7 item 8).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseControlControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "ctrlh-dev";
    private static final String OPS = "ctrlh-ops";
    private static final String TESTER = "ctrlh-tester";
    private static final String OUT_OF_SCOPE = "ctrlh-outside";
    private static final String CREATOR = "ctrlh-creator";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String REASON = "页面动作原因";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;

    private long subsystemId;
    private long unitId;
    private long taskId;

    @BeforeEach
    void setUp() {
        InMemoryDistributedLock.clearAllLeases();
        ControlFixtures.clean(jdbc);

        ControlFixtures.givenUser(jdbc, DEV, "DEV");
        ControlFixtures.givenUser(jdbc, OPS, "OPS");
        ControlFixtures.givenUser(jdbc, TESTER, "TEST");
        ControlFixtures.givenUser(jdbc, OUT_OF_SCOPE, "OPS");
        ControlFixtures.givenUser(jdbc, CREATOR, "DEV");
        ControlFixtures.givenUser(jdbc, "ctrlh-owner");

        subsystemId = ControlFixtures.givenSubsystem(jdbc, "SCC-CONTROL-HTTP", "ctrlh-owner");
        taskId = ControlFixtures.givenTask(jdbc, subsystemId, "202611-CONTROL-HTTP", CREATOR);
        unitId = ControlFixtures.givenUnit(jdbc, subsystemId, "control-http-api", "ctrlh-owner");
        long otherUnit = ControlFixtures.givenUnit(jdbc, subsystemId, "control-http-other",
            "ctrlh-owner");
        for (long unit : new long[] {unitId, otherUnit}) {
            ControlFixtures.givenBinding(jdbc, unit, "BUILD", "P-BUILD-CONTROL");
            ControlFixtures.givenBinding(jdbc, unit, "TEST_DEPLOY", "P-TEST-CONTROL");
            ControlFixtures.givenBinding(jdbc, unit, "PROD_DEPLOY", "P-PROD-CONTROL");
            ControlFixtures.givenDe(jdbc, unit, "TEST", "http-sit-" + unit, 0);
            ControlFixtures.givenDe(jdbc, unit, "PROD", "http-prod-" + unit, 1);
        }
        ControlFixtures.givenScope(jdbc, DEV, unitId);
        ControlFixtures.givenScope(jdbc, OPS, unitId);
        ControlFixtures.givenScope(jdbc, TESTER, unitId);
        ControlFixtures.givenScope(jdbc, CREATOR, unitId);
        ControlFixtures.givenScope(jdbc, OUT_OF_SCOPE, otherUnit);
        TestUserContext.clear();
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ stop

    @Test
    void stop_is_allowed_for_operations_and_answers_the_new_statuses() throws Exception {
        Published published = publish("release/http-stop");
        runningProdDeploy(published.itemId(), published.instanceId());

        mvc.perform(post("/api/v1/releases/{publishNo}/stop", published.publishNo())
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("TERMINATED"))
            .andExpect(jsonPath("$.data.itemStatus").value("TERMINATED"))
            .andExpect(jsonPath("$.data.terminatedPipelineNo").value(published.pipelineNo()));

        assertThat(itemStatus(published.itemId())).isEqualTo("TERMINATED");
    }

    @Test
    void stop_is_refused_for_a_developer() throws Exception {
        Published published = publish("release/http-stop-dev");

        mvc.perform(post("/api/v1/releases/{publishNo}/stop", published.publishNo())
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void stop_is_data_scoped() throws Exception {
        Published published = publish("release/http-stop-scope");

        mvc.perform(post("/api/v1/releases/{publishNo}/stop", published.publishNo())
                .header(USER_HEADER, OUT_OF_SCOPE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.message").value(containsString("部署单元")));
    }

    // ------------------------------------------------------------------ exit

    @Test
    void exit_is_allowed_for_a_developer_and_for_operations() throws Exception {
        Published mine = publish("release/http-exit-dev");
        setItemStatus(mine.itemId(), "TESTING");
        mvc.perform(post("/api/v1/releases/{publishNo}/exit", mine.publishNo())
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.itemStatus").value("TERMINATED"));

        Published ops = publish("release/http-exit-ops");
        setItemStatus(ops.itemId(), "GATE_PENDING");
        mvc.perform(post("/api/v1/releases/{publishNo}/exit", ops.publishNo())
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.itemStatus").value("TERMINATED"));
    }

    @Test
    void exit_is_refused_without_either_permission() throws Exception {
        Published published = publish("release/http-exit-tester");

        mvc.perform(post("/api/v1/releases/{publishNo}/exit", published.publishNo())
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ close

    @Test
    void close_is_allowed_for_a_developer_and_refused_for_operations() throws Exception {
        long item = createItem("release/http-close");

        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.itemStatus").value("CLOSED"))
            .andExpect(jsonPath("$.data.status").value(nullValue()))
            .andExpect(jsonPath("$.data.terminatedPipelineNo").value(nullValue()));

        long other = createItem("release/http-close-ops");
        mvc.perform(post("/api/v1/items/{itemId}/close", other)
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void close_answers_40001_for_an_already_closed_item() throws Exception {
        long item = createItem("release/http-close-twice");
        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.currentStatus").value("CLOSED"));
    }

    // ------------------------------------------------------------------ archive

    @Test
    void archive_is_allowed_for_the_creator_and_answers_the_cascade_counts() throws Exception {
        long ready = publish("release/http-archive-ready").itemId();
        setItemStatus(ready, "READY");

        mvc.perform(post("/api/v1/tasks/{taskId}/archive", taskId)
                .header(USER_HEADER, CREATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.taskStatus").value("ARCHIVED"))
            .andExpect(jsonPath("$.data.closedItems").value(1))
            .andExpect(jsonPath("$.data.terminatedInstances").value(1));

        assertThat(jdbc.queryForObject("SELECT status FROM release_task WHERE id = ?",
            String.class, taskId)).isEqualTo("ARCHIVED");
    }

    @Test
    void archive_is_refused_for_a_non_creator_and_for_a_role_without_task_create() throws Exception {
        mvc.perform(post("/api/v1/tasks/{taskId}/archive", taskId)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40009));

        mvc.perform(post("/api/v1/tasks/{taskId}/archive", taskId)
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ body and unknown objects

    @Test
    void the_reason_is_required_and_bounded() throws Exception {
        long item = createItem("release/http-reason");

        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("x".repeat(201))))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/items/{itemId}/close", item)
                .header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40000));
        assertThat(itemStatus(item)).as("a rejected request changed nothing").isEqualTo("PENDING");
    }

    @Test
    void unknown_objects_are_business_40000() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/stop", "PO-UNKNOWN")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/items/{itemId}/close", 9_999_999L)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/api/v1/tasks/{taskId}/archive", 9_999_999L)
                .header(USER_HEADER, CREATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void an_unauthenticated_call_is_forbidden() throws Exception {
        mvc.perform(post("/api/v1/items/{itemId}/close", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(REASON)))
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void the_controller_test_lives_outside_the_web_package() {
        assertThat(getClass().getPackageName()).isEqualTo("com.sf.release.control");
    }

    // ------------------------------------------------------------------ fixtures

    private record Published(long itemId, String publishNo, String pipelineNo, long instanceId) {
    }

    private Published publish(String branch) {
        long itemId = createItem(branch);
        TestUserContext.as("ctrlh-owner", Set.of("ADMIN"), Set.of(Permissions.RELEASE_PUBLISH));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        TestUserContext.clear();
        Long instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        return new Published(itemId, published.publishNo(), published.pipelineNo(),
            instanceId == null ? 0L : instanceId);
    }

    private long createItem(String branch) {
        TestUserContext.as("ctrlh-owner", Set.of("ADMIN"), Set.of(Permissions.ITEM_CREATE));
        long itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, branch, "变更 " + branch, PLAN_DATE));
        TestUserContext.clear();
        return itemId;
    }

    private void runningProdDeploy(long itemId, long instanceId) {
        setItemStatus(itemId, "PROD_DEPLOYING");
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING', current_node ="
            + " 'PROD_DEPLOY', start_at = NOW(3) WHERE id = ?", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = NOW(3)"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", instanceId);
    }

    private void setItemStatus(long itemId, String status) {
        jdbc.update("UPDATE release_item SET status = ?, row_version = row_version + 1"
            + " WHERE id = ?", status, itemId);
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }
}