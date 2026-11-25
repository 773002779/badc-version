package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.ApprovalReminderRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * The P6-E reminder queries (design §3): the due pending steps of each order's newest admission
 * generation, and the ADMIN users an escalation addresses.
 *
 * <p><strong>The newest generation is the admission snapshot, not the newest approval row.</strong>
 * {@code MAX(admission_snapshot.id)} per order is the authoritative generation: a resubmission that
 * required no signature creates a snapshot with no approval row, and the earlier generation's
 * still-{@code PENDING} rows are dead history that must not be reminded (design §1/§5). Taking
 * {@code MAX(approval.snapshot_id)} instead would resurrect exactly those rows.
 *
 * <p><strong>What is due.</strong> Only {@code PENDING} steps of an item that is still
 * {@code PENDING_APPROVAL}: a step that was approved/cancelled, or a release that moved to
 * {@code READY}/{@code REJECTED}, is no longer waiting for a person. The {@code created_at} cut-off
 * is the reminder threshold; the escalation threshold is applied by the service on the same rows
 * (it is always later, so one query serves both levels).
 */
@Mapper
public interface ApprovalReminderMapper {

    /**
     * The steps at least as old as {@code threshold}, oldest first, at most {@code limit} rows.
     *
     * @param threshold {@code now - remindHours}; the escalation cutoff is checked against the same
     *                  rows by the service
     * @param limit     rows per scan round, so one round stays cheap on a large backlog
     */
    @Select("""
        SELECT a.snapshot_id  AS snapshotId,
               a.step_no      AS stepNo,
               a.order_id     AS orderId,
               o.publish_no   AS publishNo,
               a.role         AS role,
               a.created_at   AS waitingSince,
               u.subsystem_id AS subsystemId,
               s.owner_id     AS ownerId,
               t.creator      AS creator
        FROM approval a
        JOIN release_order o ON o.id = a.order_id
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        JOIN subsystem s ON s.id = u.subsystem_id
        LEFT JOIN release_task t ON t.id = i.task_id
        WHERE a.status = 'PENDING'
          AND i.status = 'PENDING_APPROVAL'
          AND a.created_at <= #{threshold}
          AND a.snapshot_id = (
                SELECT MAX(snap.id) FROM admission_snapshot snap
                WHERE snap.order_id = a.order_id)
        ORDER BY a.created_at, a.order_id, a.step_no
        LIMIT #{limit}
        """)
    List<ApprovalReminderRow> selectDue(@Param("threshold") Instant threshold,
                                        @Param("limit") int limit);

    /**
     * The users holding {@code ADMIN} (P6-E design §3: the escalation's "higher level"). Any
     * {@code user_role} holder of the role is an administrator — {@code JdbcAuthProvider} reads the
     * same table on every request, so there is no second source of truth to consult.
     */
    @Select("SELECT DISTINCT user_id FROM user_role WHERE role = 'ADMIN' ORDER BY user_id")
    List<String> selectAdminUserIds();
}
