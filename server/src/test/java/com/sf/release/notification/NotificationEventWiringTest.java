package com.sf.release.notification;

import com.sf.release.application.ApprovalService;
import com.sf.release.application.FreezeService;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.job.UnitLeaseRenewJob;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event wiring of the design's trigger table (test plan item 7): at least six representative
 * events are produced by their real trigger point, not by a direct {@code notify} call, so the test
 * proves the trigger point actually invokes the notification service.
 *
 * <p>Covered: {@code BUILD_FAILED} (pipeline node-failure listener), {@code APPROVAL_PENDING}
 * ({@code ApprovalService.ensureChain}), {@code FREEZE_APPLIED} / {@code FREEZE_RELEASED}
 * ({@code FreezeService}), {@code PROD_DONE} (instance-finished listener), {@code ROLLBACK_DONE}
 * ({@code RollbackCompletionService}) and {@code LOCK_TIMEOUT_RELEASED} ({@code UnitLeaseRenewJob}).
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationEventWiringTest {

    private static final String ACTOR = "wire-freeze-actor";

    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApprovalService approvals;
    @Autowired FreezeService freezes;
    @Autowired NotificationService notifications;
    @Autowired DeployUnitLockMapper lockMapper;
    @Autowired UnitLease unitLease;
    @Autowired JdbcTemplate jdbc;

    private long subsystem;
    private long unit;
    private long task;
    private long item;
    private long order;

    @BeforeEach
    void setUp() {
        NotificationFixtures.clean(jdbc);
        subsystem = NotificationFixtures.givenSubsystem(jdbc, "WIRE-SYS", "wire-sys-owner");
        unit = NotificationFixtures.givenUnit(jdbc, subsystem, "wire-unit", "wire-unit-owner");
        task = NotificationFixtures.givenTask(jdbc, subsystem, "WIRE-TASK", "wire-creator");
        NotificationFixtures.givenMember(jdbc, task, "wire-dev", "DEV");
        NotificationFixtures.givenMember(jdbc, task, "wire-test", "TEST");
        NotificationFixtures.givenMember(jdbc, task, "wire-ops", "OPS");
        NotificationFixtures.givenMember(jdbc, task, "wire-product", "PRODUCT");
        item = NotificationFixtures.givenItem(jdbc, task, unit, "CR-WIRE-1", "READY");
        order = NotificationFixtures.givenOrder(jdbc, item, "PO-WIRE-1", "READY");
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ pipeline events

    @Test
    void a_failed_build_node_writes_build_failed() {
        long instance = NotificationFixtures.givenInstance(jdbc, order, "PL-WIRE-BUILD", "INITIAL",
            "RUNNING", null);

        publisher.publishEvent(new NodeCompletedEvent(instance, order, NodeType.BUILD,
            NodeStatus.FAILED, "compile failed", Instant.now()));

        List<String> recipients = recipientsOf("BUILD_FAILED", "PO-WIRE-1");
        assertThat(recipients).contains("wire-dev", "wire-test", "wire-creator");
        assertThat(objectTypeOf("BUILD_FAILED", "PO-WIRE-1")).isEqualTo("ORDER");
    }

    @Test
    void a_finished_forward_instance_writes_prod_done() {
        long instance = NotificationFixtures.givenInstance(jdbc, order, "PL-WIRE-DONE", "INITIAL",
            "DONE", null);

        publisher.publishEvent(new InstanceFinishedEvent(instance, order, InstanceStatus.DONE, null,
            Instant.now()));

        assertThat(recipientsOf("PROD_DONE", "PO-WIRE-1"))
            .contains("wire-ops", "wire-dev", "wire-creator");
    }

    @Test
    void a_rollback_completion_writes_rollback_done() {
        long instance = NotificationFixtures.givenInstance(jdbc, order, "PL-WIRE-RB", "ROLLBACK",
            "DONE", null);
        NotificationFixtures.givenRollbackRecord(jdbc, unit, instance, order, "RUNNING",
            "wire-requester");

        publisher.publishEvent(new InstanceFinishedEvent(instance, order, InstanceStatus.DONE, null,
            Instant.now()));

        List<String> recipients = recipientsOf("ROLLBACK_DONE", String.valueOf(unit));
        assertThat(recipients)
            .as("task OPS/DEV members, the requester and the unit owner")
            .contains("wire-ops", "wire-dev", "wire-requester", "wire-unit-owner")
            .doesNotContain("wire-creator");
        assertThat(jdbc.queryForObject("SELECT status FROM rollback_record WHERE instance_id = ?",
            String.class, instance)).isEqualTo("SUCCESS");
    }

    // ------------------------------------------------------------------ approval

    @Test
    void growing_the_approval_chain_writes_approval_pending() {
        long snapshot = NotificationFixtures.givenSnapshot(jdbc, order, item, "wire-submitter");
        NotificationFixtures.givenUser(jdbc, "wire-ops-lead", "OPS_LEAD");

        approvals.ensureChain(order, snapshot, List.of(ApprovalRole.OPS_LEAD));

        assertThat(recipientsOf("APPROVAL_PENDING", "PO-WIRE-1")).containsExactly("wire-ops-lead");
    }

    @Test
    void an_approval_role_without_a_holder_falls_back_to_the_system_owner() {
        long snapshot = NotificationFixtures.givenSnapshot(jdbc, order, item, "wire-submitter");

        approvals.ensureChain(order, snapshot, List.of(ApprovalRole.DEPT_MANAGER));

        assertThat(recipientsOf("APPROVAL_PENDING", "PO-WIRE-1")).containsExactly("wire-sys-owner");
        assertThat(jdbc.queryForObject("SELECT body FROM notification WHERE event_key ="
            + " 'APPROVAL_PENDING'", String.class)).contains("（角色无人，已转系统负责人）");
    }

    // ------------------------------------------------------------------ freeze

    @Test
    void declaring_and_lifting_a_freeze_write_both_freeze_events() {
        TestUserContext.as(ACTOR, Set.of("DEPT_MANAGER", "OPS_LEAD"),
            Set.of(Permissions.CONTROL_FREEZE, Permissions.CONTROL_UNFREEZE));
        Instant now = Instant.now();

        long freezeId = freezes.create("GLOBAL", null, "大促封板", null, now.minusSeconds(60),
            now.plusSeconds(3600), ACTOR).id();

        List<String> applied = recipientsOf("FREEZE_APPLIED", String.valueOf(freezeId));
        assertThat(applied).as("the four member roles of the affected release")
            .contains("wire-dev", "wire-test", "wire-ops", "wire-product");

        freezes.release(freezeId, ACTOR, "解除");

        List<String> released = recipientsOf("FREEZE_RELEASED", String.valueOf(freezeId));
        assertThat(released).contains("wire-dev");
    }

    // ------------------------------------------------------------------ lock timeout

    @Test
    void an_abandoned_pause_writes_lock_timeout_released() {
        Instant paused = Instant.now().minus(Duration.ofHours(2));
        long instance = NotificationFixtures.givenInstance(jdbc, order, "PL-WIRE-LOCK", "INITIAL",
            "PAUSED", paused);
        NotificationFixtures.givenLock(jdbc, unit, instance, Instant.now().plusSeconds(60));
        UnitLeaseRenewJob job = new UnitLeaseRenewJob(lockMapper, unitLease, notifications,
            Duration.ofHours(1));

        job.renewLeases();

        assertThat(recipientsOf("LOCK_TIMEOUT_RELEASED", String.valueOf(unit)))
            .contains("wire-unit-owner", "wire-creator");
    }

    // ------------------------------------------------------------------ helpers

    private List<String> recipientsOf(String eventKey, String objectId) {
        return jdbc.queryForList("SELECT recipient FROM notification WHERE event_key = ?"
            + " AND object_id = ? AND channel = 'INAPP' ORDER BY recipient", String.class,
            eventKey, objectId);
    }

    private String objectTypeOf(String eventKey, String objectId) {
        return jdbc.queryForObject("SELECT object_type FROM notification WHERE event_key = ?"
            + " AND object_id = ? LIMIT 1", String.class, eventKey, objectId);
    }
}