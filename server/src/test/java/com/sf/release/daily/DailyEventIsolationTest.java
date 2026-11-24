package com.sf.release.daily;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure isolation and the item-state regression (P5-A design §7 item 3): a daily instance has
 * {@code orderId = 0}, and {@code PipelineEventListener} returns before mapping any item event for
 * it. Publishing the daily node/finish events must therefore never move the 变更项, never record a
 * {@code pipeline_item_failure} row and never send a notification (a daily release has no
 * notification by design §1).
 *
 * <p>The events are published directly through the application bus: the real listener chain runs,
 * which is exactly what the regression is about, without needing a real engine.
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyEventIsolationTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";

    @Autowired DailyReleaseService dailyService;
    @Autowired ItemAppService itemService;
    @Autowired ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;

    private long itemId;

    @BeforeEach
    void setUp() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_DAILY));
        clearSchema();

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-DAILY-EVENT");
        long taskId = givenTask(subsystemId, "202611-DAILY-E");
        long unitId = givenUnit(subsystemId, "daily-event-api");
        givenBinding(unitId, "BUILD", "P-BUILD-DAILY");
        givenBinding(unitId, "DAILY_DEPLOY", "P-DAILY-DEPLOY");
        givenDe(unitId, "DAILY", "daily-event-sit", 0);
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "日常事件",
            PLAN_DATE));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    @Test
    void daily_node_and_finish_events_never_move_the_item_or_fail_or_notify() {
        long instanceId = dailyService.start(itemId).instanceId();
        String statusBefore = itemStatus();

        // The whole vocabulary a daily pipeline can produce: a successful node, a failed node and
        // a failed instance. All of them carry orderId = 0.
        events.publishEvent(new NodeCompletedEvent(instanceId, 0L, NodeType.BUILD,
            NodeStatus.SUCCESS, null, Instant.now()));
        events.publishEvent(new NodeCompletedEvent(instanceId, 0L, NodeType.DAILY_DEPLOY,
            NodeStatus.SUCCESS, null, Instant.now()));
        events.publishEvent(new NodeCompletedEvent(instanceId, 0L, NodeType.DAILY_DEPLOY,
            NodeStatus.FAILED, "日常部署失败", Instant.now()));
        events.publishEvent(new InstanceFinishedEvent(instanceId, 0L, InstanceStatus.FAILED,
            "日常部署失败", Instant.now()));
        events.publishEvent(new InstanceFinishedEvent(instanceId, 0L, InstanceStatus.DONE,
            "日常部署完成", Instant.now()));

        assertThat(itemStatus())
            .as("no daily event may change the 变更项 main state")
            .isEqualTo(statusBefore);
        assertThat(count("pipeline_item_failure"))
            .as("the listener returns before the item transition, so nothing is recorded for repair")
            .isZero();
        assertThat(count("notification"))
            .as("a daily release sends no notification (design §1)")
            .isZero();
        assertThat(count("release_order"))
            .as("and it never creates a release order")
            .isZero();
    }

    @Test
    void a_daily_start_alone_never_moves_the_item() {
        String statusBefore = itemStatus();
        long instanceId = dailyService.start(itemId).instanceId();

        assertThat(instanceId).isPositive();
        assertThat(itemStatus()).isEqualTo(statusBefore);
        assertThat(count("pipeline_item_failure")).isZero();
    }

    // ------------------------------------------------------------------ row read-back

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
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
}