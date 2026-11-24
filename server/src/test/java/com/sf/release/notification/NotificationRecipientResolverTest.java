package com.sf.release.notification;

import com.sf.release.application.notification.NotificationRecipientResolver;
import com.sf.release.application.notification.NotificationTarget;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.task.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipient resolution (notification design section 4 and test plan item 1): the task members by
 * role plus the creator, {@code OWNER} counting as 研发; the system-owner fallback when a required
 * role has nobody; the approval-step role holders; the waiver approvers; the freeze aggregation; the
 * rollback and lock-timeout audiences.
 *
 * <p>The fixtures are the real schema rows (membership, roles, orders) the resolver reads, so this
 * test pins the SQL joins, not a mock.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationRecipientResolverTest {

    private static final String SYS_OWNER_A = "res-sys-owner-a";
    private static final String SYS_OWNER_B = "res-sys-owner-b";

    @Autowired NotificationRecipientResolver resolver;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    private long subsystemA;
    private long subsystemB;
    private long unitA;
    private long unitB;
    private long taskA;
    private long taskB;
    private long itemA;
    private long orderA;
    private long itemB;
    private long orderB;

    @BeforeEach
    void setUp() {
        NotificationFixtures.clean(jdbc);

        subsystemA = NotificationFixtures.givenSubsystem(jdbc, "RES-SYS-A", SYS_OWNER_A);
        subsystemB = NotificationFixtures.givenSubsystem(jdbc, "RES-SYS-B", SYS_OWNER_B);
        unitA = NotificationFixtures.givenUnit(jdbc, subsystemA, "res-unit-a", "unit-owner-a");
        unitB = NotificationFixtures.givenUnit(jdbc, subsystemB, "res-unit-b", "unit-owner-b");
        taskA = NotificationFixtures.givenTask(jdbc, subsystemA, "RES-TASK-A", "creator-a");
        taskB = NotificationFixtures.givenTask(jdbc, subsystemB, "RES-TASK-B", "creator-b");

        NotificationFixtures.givenMember(jdbc, taskA, "dev-a", "DEV");
        NotificationFixtures.givenMember(jdbc, taskA, "test-a", "TEST");
        NotificationFixtures.givenMember(jdbc, taskA, "ops-a", "OPS");
        NotificationFixtures.givenMember(jdbc, taskA, "product-a", "PRODUCT");
        NotificationFixtures.givenMember(jdbc, taskA, "owner-a", "OWNER");
        NotificationFixtures.givenMember(jdbc, taskB, "dev-b", "DEV");

        itemA = NotificationFixtures.givenItem(jdbc, taskA, unitA, "CR-RES-A", "READY");
        orderA = NotificationFixtures.givenOrder(jdbc, itemA, "PO-RES-A", "READY");
        itemB = NotificationFixtures.givenItem(jdbc, taskB, unitB, "CR-RES-B", "READY");
        orderB = NotificationFixtures.givenOrder(jdbc, itemB, "PO-RES-B", "READY");
    }

    // ------------------------------------------------------------------ task members (4.1)

    @Test
    void task_recipients_are_the_requested_roles_plus_the_creator_sorted() {
        List<String> all = resolver.taskRecipients(orderA,
            EnumSet.of(MemberRole.DEV, MemberRole.TEST, MemberRole.OPS, MemberRole.PRODUCT));

        assertThat(all).containsExactly("creator-a", "dev-a", "ops-a", "owner-a", "product-a",
            "test-a");
    }

    @Test
    void owner_counts_as_development() {
        assertThat(resolver.taskRecipients(orderA, EnumSet.of(MemberRole.DEV)))
            .containsExactly("creator-a", "dev-a", "owner-a");
        assertThat(resolver.taskRecipients(orderA, EnumSet.of(MemberRole.TEST)))
            .containsExactly("creator-a", "test-a");
    }

    @Test
    void an_empty_or_unknown_order_resolves_nobody() {
        assertThat(resolver.taskRecipients(999_999L, EnumSet.of(MemberRole.DEV))).isEmpty();
        assertThat(resolver.taskRecipients(orderA, Set.of())).as("only the creator remains")
            .containsExactly("creator-a");
    }

    // ------------------------------------------------------------------ approval (4.2)

    @Test
    void an_approval_step_without_a_role_holder_falls_back_to_the_system_owner() {
        List<NotificationTarget> targets = resolver.approvalPendingTargets(orderA,
            ApprovalRole.DEPT_MANAGER, "发布单 PO-RES-A 第 1 步需要「部门经理」审批。");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).userId()).isEqualTo(SYS_OWNER_A);
        assertThat(targets.get(0).body())
            .contains("发布单 PO-RES-A")
            .contains("（角色无人，已转系统负责人）");
    }

    @Test
    void an_approval_step_notifies_the_users_holding_the_role() {
        NotificationFixtures.givenUser(jdbc, "mgr-1", "DEPT_MANAGER");
        NotificationFixtures.givenUser(jdbc, "mgr-2", "DEPT_MANAGER");

        List<NotificationTarget> targets = resolver.approvalPendingTargets(orderA,
            ApprovalRole.DEPT_MANAGER, "body");

        assertThat(targets).extracting(NotificationTarget::userId)
            .containsExactly("mgr-1", "mgr-2");
        assertThat(targets).allMatch(target -> target.body() == null);
    }

    @Test
    void waiver_recipients_are_the_approvers_plus_the_system_owner() {
        NotificationFixtures.givenUser(jdbc, "ops-lead", "OPS_LEAD");
        NotificationFixtures.givenUser(jdbc, "mgr-1", "DEPT_MANAGER");
        NotificationFixtures.givenUser(jdbc, "dev-a", "DEV");

        assertThat(resolver.waiverRecipients(itemA))
            .containsExactly("mgr-1", "ops-lead", SYS_OWNER_A);
    }

    // ------------------------------------------------------------------ freeze impact (4.3)

    @Test
    void the_freeze_audience_is_aggregated_per_person_with_the_release_and_its_stage() {
        List<NotificationTarget> targets = resolver.freezeTargets(0, "封板已生效。");

        assertThat(targets).extracting(NotificationTarget::userId)
            .containsExactlyInAnyOrder("dev-a", "test-a", "ops-a", "product-a", "owner-a", "dev-b",
                SYS_OWNER_B);
        assertThat(bodyOf(targets, "dev-a"))
            .contains("封板已生效。")
            .contains("PO-RES-A（待执行）");
        assertThat(bodyOf(targets, SYS_OWNER_B))
            .as("the second system has no PRODUCT/TEST/OPS member: its owner is the fallback")
            .contains("系统「RES-SYS-B」缺少产品角色成员，已转系统负责人。")
            .contains("PO-RES-B（待执行）");
    }

    @Test
    void a_subsystem_freeze_only_addresses_that_system() {
        List<NotificationTarget> targets = resolver.freezeTargets(subsystemB, "封板已生效。");

        assertThat(targets).extracting(NotificationTarget::userId)
            .containsExactlyInAnyOrder("dev-b", SYS_OWNER_B);
    }

    @Test
    void the_freeze_body_lists_at_most_ten_releases_and_says_how_many_more() {
        for (int i = 0; i < 11; i++) {
            int n = seq.incrementAndGet();
            long item = NotificationFixtures.givenItem(jdbc, taskA, unitA, "CR-RES-X" + n, "READY");
            NotificationFixtures.givenOrder(jdbc, item, "PO-RES-X" + n, "READY");
        }

        List<NotificationTarget> targets = resolver.freezeTargets(0, "封板已生效。");

        assertThat(bodyOf(targets, "dev-a"))
            .contains("PO-RES-X1")
            .doesNotContain("PO-RES-X11")
            .contains("等 2 单");
    }

    // ------------------------------------------------------------------ rollback and lock (3, 4.4)

    @Test
    void rollback_recipients_are_the_task_development_and_operations_the_requester_and_the_unit_owner() {
        assertThat(resolver.rollbackRecipients(unitA, orderA, "requester-a"))
            .containsExactly("dev-a", "ops-a", "owner-a", "requester-a", "unit-owner-a");
    }

    @Test
    void lock_timeout_recipients_are_the_unit_owner_and_the_live_order_creators() {
        NotificationFixtures.givenInstance(jdbc, orderA, "PL-RES-LOCK", "INITIAL", "PAUSED",
            Instant.now().minusSeconds(3600));

        assertThat(resolver.lockTimeoutRecipients(unitA))
            .containsExactly("creator-a", "unit-owner-a");
    }

    @Test
    void lock_timeout_falls_back_to_the_system_owner_when_the_unit_has_no_owner_or_creator() {
        long bareSubsystem = NotificationFixtures.givenSubsystem(jdbc, "RES-SYS-C", SYS_OWNER_A);
        long bareUnit = NotificationFixtures.givenUnit(jdbc, bareSubsystem, "res-unit-c", "");

        assertThat(resolver.lockTimeoutRecipients(bareUnit)).containsExactly(SYS_OWNER_A);
    }

    private static String bodyOf(List<NotificationTarget> targets, String userId) {
        return targets.stream()
            .filter(target -> userId.equals(target.userId()))
            .map(NotificationTarget::body)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no target for " + userId));
    }
}