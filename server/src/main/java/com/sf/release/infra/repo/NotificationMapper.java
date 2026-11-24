package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.NotificationOrderRow;
import com.sf.release.infra.repo.po.NotificationPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Plus mapper for {@code notification} plus the small audience queries the recipient
 * resolver needs (V26, notification design sections 4 and 5).
 *
 * <p><strong>Dedupe is the unique key.</strong> {@link #insertIgnore} is an {@code INSERT IGNORE}:
 * a repeated trigger (a replayed pipeline event, a second listener invocation) changes nothing and
 * returns 0. The service therefore does not read-then-write, which would race two triggers of the
 * same event.
 *
 * <p><strong>Delivery is a plain status write.</strong> {@link #updateSendOutcome} is not a
 * compare-and-set: the design accepts at-least-once delivery on a multi-instance deployment
 * (section 5/9), and a single instance takes the rows in id order.
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationPO> {

    /**
     * Writes one row unless its {@code dedupe_key} already exists.
     *
     * @return 1 when the row was written, 0 when the event/recipient/channel/ref was already sent
     */
    @Insert("""
        INSERT IGNORE INTO notification (event_key, object_type, object_id, object_label, recipient,
            channel, title, body, status, read_at, attempts, failure_reason, dedupe_key, created_at,
            sent_at)
        VALUES (#{eventKey}, #{objectType}, #{objectId}, #{objectLabel}, #{recipient}, #{channel},
            #{title}, #{body}, #{status}, #{readAt}, #{attempts}, #{failureReason}, #{dedupeKey},
            #{createdAt}, #{sentAt})
        """)
    int insertIgnore(NotificationPO po);

    /** One page of the caller's in-app rows, newest first. */
    @Select("""
        SELECT * FROM notification
        WHERE recipient = #{recipient}
          AND channel = 'INAPP'
          AND (#{unreadOnly} = 0 OR read_at IS NULL)
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<NotificationPO> selectInAppPage(@Param("recipient") String recipient,
                                         @Param("unreadOnly") int unreadOnly,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /** The full match count of {@link #selectInAppPage}. */
    @Select("""
        SELECT COUNT(*) FROM notification
        WHERE recipient = #{recipient}
          AND channel = 'INAPP'
          AND (#{unreadOnly} = 0 OR read_at IS NULL)
        """)
    long countInApp(@Param("recipient") String recipient,
                    @Param("unreadOnly") int unreadOnly);

    /** The caller's unread in-app rows. */
    @Select("SELECT COUNT(*) FROM notification"
        + " WHERE recipient = #{recipient} AND channel = 'INAPP' AND read_at IS NULL")
    long countUnread(@Param("recipient") String recipient);

    /**
     * One in-app row of {@code recipient}. Restricting by recipient in the statement is what makes
     * "mark somebody else's notification read" indistinguishable from "no such row".
     */
    @Select("SELECT * FROM notification WHERE id = #{id} AND recipient = #{recipient}"
        + " AND channel = 'INAPP' LIMIT 1")
    NotificationPO selectInApp(@Param("id") long id, @Param("recipient") String recipient);

    /** Marks one row read; the {@code read_at IS NULL} guard keeps a second call a no-op. */
    @Update("UPDATE notification SET read_at = #{readAt}"
        + " WHERE id = #{id} AND recipient = #{recipient} AND channel = 'INAPP'"
        + " AND read_at IS NULL")
    int markRead(@Param("id") long id, @Param("recipient") String recipient,
                 @Param("readAt") Instant readAt);

    /** Marks every unread in-app row of {@code recipient} read; returns how many changed. */
    @Update("UPDATE notification SET read_at = #{readAt}"
        + " WHERE recipient = #{recipient} AND channel = 'INAPP' AND read_at IS NULL")
    int markAllRead(@Param("recipient") String recipient, @Param("readAt") Instant readAt);

    /** The oldest pending WeCom rows; the dispatch job works them in id order. */
    @Select("SELECT * FROM notification WHERE status = 'PENDING' AND channel = 'WECOM'"
        + " ORDER BY id LIMIT #{limit}")
    List<NotificationPO> selectPendingWeCom(@Param("limit") int limit);

    /** Records the delivery outcome of one WeCom row. */
    @Update("UPDATE notification SET status = #{status}, attempts = #{attempts},"
        + " failure_reason = #{failureReason}, sent_at = #{sentAt} WHERE id = #{id}")
    int updateSendOutcome(@Param("id") long id, @Param("status") String status,
                          @Param("attempts") int attempts,
                          @Param("failureReason") String failureReason,
                          @Param("sentAt") Instant sentAt);

    /** Users holding one platform role, for the approval-step audience. */
    @Select("SELECT user_id FROM user_role WHERE role = #{role} ORDER BY user_id")
    List<String> selectUserIdsByRole(@Param("role") String role);

    /** Users whose role mapping grants one permission (the waiver approvers). */
    @Select("SELECT DISTINCT ur.user_id FROM user_role ur"
        + " JOIN role_permission rp ON rp.role = ur.role"
        + " WHERE rp.perm = #{perm} ORDER BY ur.user_id")
    List<String> selectUserIdsByPermission(@Param("perm") String perm);

    /**
     * The releases a freeze of one system affects (design section 4.3): orders inside the production
     * flow when the notification is built. {@code subsystemId = 0} means every system (a GLOBAL
     * freeze).
     */
    @Select("""
        SELECT o.id           AS orderId,
               o.publish_no   AS publishNo,
               i.task_id      AS taskId,
               i.status       AS itemStatus,
               u.subsystem_id AS subsystemId,
               s.name         AS subsystemName,
               u.name         AS unitName
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        JOIN subsystem s ON s.id = u.subsystem_id
        WHERE (o.status IN ('READY', 'PENDING_APPROVAL', 'SUBMITTED', 'EXECUTING')
               OR i.status IN ('PROD_DEPLOYING', 'GATE_PENDING'))
          AND (#{subsystemId} = 0 OR u.subsystem_id = #{subsystemId})
        ORDER BY o.id
        """)
    List<NotificationOrderRow> selectFreezeAudience(@Param("subsystemId") long subsystemId);

    /** The system of a 发布单, for the "role has nobody → system owner" fallback. */
    @Select("""
        SELECT u.subsystem_id
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        WHERE o.id = #{orderId}
        LIMIT 1
        """)
    Long selectSubsystemIdOfOrder(@Param("orderId") long orderId);

    /**
     * Creators of the orders with a live instance on one deployment unit (design section 4.4):
     * "the requester of every in-flight order the unit holds".
     */
    @Select("""
        SELECT DISTINCT t.creator
        FROM pipeline_instance pi
        JOIN release_order o ON o.id = pi.order_id
        JOIN release_item i ON i.id = o.item_id
        JOIN release_task t ON t.id = i.task_id
        WHERE i.unit_id = #{unitId}
          AND pi.status IN ('RUNNING', 'PAUSED')
          AND t.creator IS NOT NULL
        ORDER BY t.creator
        """)
    List<String> selectLiveCreatorIds(@Param("unitId") long unitId);
}