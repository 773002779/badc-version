package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.TodoRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only SQL of the 待办中心 (P5-C design sections 1 and 2). One statement per group answers the
 * rows, one more answers the group's full count: four groups, eight statements, and the detail
 * statement carries the {@code LIMIT} — there is no per-row follow-up query (no N+1).
 *
 * <p><strong>Scope is SQL and fail-closed.</strong> Every group joins the deployment unit through
 * {@code release_item.unit_id} (a task or an order has no unit of its own). {@code allUnits} skips the
 * predicate; otherwise the caller's unit ids are ANDed in. When the scope list is empty the predicate
 * degenerates to {@code 1 = 0}, so even a caller that forgot to short-circuit in the service cannot
 * read a row.
 *
 * <p><strong>Group rules.</strong>
 * <ul>
 *   <li>{@code test}: {@code TESTING} item, the caller is a {@code TEST} member of its task, and the
 *       <em>newest</em> instance of the item's newest order has {@code test_ready_at} set (V9 puts the
 *       readiness on the instance, not on the item); the test dimension must not be
 *       {@code TEST_PASS}. {@code waitingSince = test_ready_at}.</li>
 *   <li>{@code approval}: {@code PENDING_APPROVAL} order whose newest snapshot's <em>lowest pending
 *       step</em> role is one of the caller's roles, and the snapshot was not submitted by the caller
 *       (C20). {@code waitingSince = snapshot.created_at}.</li>
 *   <li>{@code execute}: {@code READY} order, the caller is an {@code OPS} member of its task or holds
 *       {@code control:execute-prod}. {@code waitingSince = order.created_at} (see the service note:
 *       {@code release_order} has no {@code updated_at} column in this schema).</li>
 *   <li>{@code mine}: a task created by the caller that still has a blocking item
 *       ({@code !isDone() && !isTerminal()}). The group count is the number of those items
 *       (design section 2); the rows are the tasks, at most {@code limit}.</li>
 * </ul>
 *
 * <p>Rows are oldest-waiting first so the centre is a queue, not a feed; the id breaks a
 * same-millisecond tie deterministically.
 */
@Mapper
public interface TodoQueryMapper {

    // ------------------------------------------------------------------ 待测试

    /** At most {@code limit} 待测试 rows, oldest wait first. */
    @Select("""
        <script>
        SELECT i.id             AS objectId,
               i.crid           AS label,
               i.status         AS status,
               i.unit_id        AS unitId,
               pi.test_ready_at AS waitingSince,
               t.id             AS taskId,
               i.id             AS itemId
        FROM release_item i
        JOIN release_task t ON t.id = i.task_id
        JOIN release_task_member m
          ON m.task_id = i.task_id AND m.user_id = #{me} AND m.role = 'TEST'
        JOIN release_order o
          ON o.id = (SELECT MAX(o2.id) FROM release_order o2 WHERE o2.item_id = i.id)
        JOIN pipeline_instance pi
          ON pi.id = (SELECT MAX(p2.id) FROM pipeline_instance p2 WHERE p2.order_id = o.id)
        WHERE i.status = 'TESTING'
          AND i.test_status != 'TEST_PASS'
          AND pi.test_ready_at IS NOT NULL
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        ORDER BY pi.test_ready_at ASC, i.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<TodoRow> selectTestItems(@Param("me") String me,
                                  @Param("allUnits") boolean allUnits,
                                  @Param("scopeUnitIds") List<Long> scopeUnitIds,
                                  @Param("limit") int limit);

    /** The full 待测试 count under the same predicate as {@link #selectTestItems}. */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM release_item i
        JOIN release_task t ON t.id = i.task_id
        JOIN release_task_member m
          ON m.task_id = i.task_id AND m.user_id = #{me} AND m.role = 'TEST'
        JOIN release_order o
          ON o.id = (SELECT MAX(o2.id) FROM release_order o2 WHERE o2.item_id = i.id)
        JOIN pipeline_instance pi
          ON pi.id = (SELECT MAX(p2.id) FROM pipeline_instance p2 WHERE p2.order_id = o.id)
        WHERE i.status = 'TESTING'
          AND i.test_status != 'TEST_PASS'
          AND pi.test_ready_at IS NOT NULL
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        </script>
        """)
    long countTestItems(@Param("me") String me,
                        @Param("allUnits") boolean allUnits,
                        @Param("scopeUnitIds") List<Long> scopeUnitIds);

    // ------------------------------------------------------------------ 待审批

    /** At most {@code limit} 待审批 rows (first unsigned step only), oldest wait first. */
    @Select("""
        <script>
        SELECT o.id              AS objectId,
               o.publish_no      AS label,
               o.status          AS status,
               i.unit_id         AS unitId,
               s.created_at      AS waitingSince,
               o.publish_no      AS publishNo,
               o.item_id         AS itemId
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN admission_snapshot s
          ON s.id = (SELECT MAX(s2.id) FROM admission_snapshot s2 WHERE s2.order_id = o.id)
        JOIN approval a
          ON a.snapshot_id = s.id
         AND a.status = 'PENDING'
         AND a.step_no = (SELECT MIN(a2.step_no) FROM approval a2
                          WHERE a2.snapshot_id = s.id AND a2.status = 'PENDING')
        WHERE o.status = 'PENDING_APPROVAL'
          AND s.submitted_by != #{me}
        <choose>
          <when test="roles != null and roles.size() > 0">
            AND a.role IN
            <foreach collection="roles" item="role" open="(" separator="," close=")">
              #{role}
            </foreach>
          </when>
          <otherwise>AND 1 = 0</otherwise>
        </choose>
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        ORDER BY s.created_at ASC, o.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<TodoRow> selectApprovalItems(@Param("me") String me,
                                      @Param("roles") List<String> roles,
                                      @Param("allUnits") boolean allUnits,
                                      @Param("scopeUnitIds") List<Long> scopeUnitIds,
                                      @Param("limit") int limit);

    /** The full 待审批 count under the same predicate as {@link #selectApprovalItems}. */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN admission_snapshot s
          ON s.id = (SELECT MAX(s2.id) FROM admission_snapshot s2 WHERE s2.order_id = o.id)
        JOIN approval a
          ON a.snapshot_id = s.id
         AND a.status = 'PENDING'
         AND a.step_no = (SELECT MIN(a2.step_no) FROM approval a2
                          WHERE a2.snapshot_id = s.id AND a2.status = 'PENDING')
        WHERE o.status = 'PENDING_APPROVAL'
          AND s.submitted_by != #{me}
        <choose>
          <when test="roles != null and roles.size() > 0">
            AND a.role IN
            <foreach collection="roles" item="role" open="(" separator="," close=")">
              #{role}
            </foreach>
          </when>
          <otherwise>AND 1 = 0</otherwise>
        </choose>
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        </script>
        """)
    long countApprovalItems(@Param("me") String me,
                            @Param("roles") List<String> roles,
                            @Param("allUnits") boolean allUnits,
                            @Param("scopeUnitIds") List<Long> scopeUnitIds);

    // ------------------------------------------------------------------ 待执行

    /** At most {@code limit} 待执行 rows, oldest wait first. */
    @Select("""
        <script>
        SELECT o.id            AS objectId,
               o.publish_no    AS label,
               o.status        AS status,
               i.unit_id       AS unitId,
               o.created_at    AS waitingSince,
               o.publish_no    AS publishNo,
               o.item_id       AS itemId,
               t.id            AS taskId
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN release_task t ON t.id = i.task_id
        LEFT JOIN release_task_member m
          ON m.task_id = i.task_id AND m.user_id = #{me} AND m.role = 'OPS'
        WHERE o.status = 'READY'
          AND (m.id IS NOT NULL <if test="executePerm">OR 1 = 1</if>)
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        ORDER BY o.created_at ASC, o.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<TodoRow> selectExecuteItems(@Param("me") String me,
                                     @Param("executePerm") boolean executePerm,
                                     @Param("allUnits") boolean allUnits,
                                     @Param("scopeUnitIds") List<Long> scopeUnitIds,
                                     @Param("limit") int limit);

    /** The full 待执行 count under the same predicate as {@link #selectExecuteItems}. */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN release_task t ON t.id = i.task_id
        LEFT JOIN release_task_member m
          ON m.task_id = i.task_id AND m.user_id = #{me} AND m.role = 'OPS'
        WHERE o.status = 'READY'
          AND (m.id IS NOT NULL <if test="executePerm">OR 1 = 1</if>)
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        </script>
        """)
    long countExecuteItems(@Param("me") String me,
                           @Param("executePerm") boolean executePerm,
                           @Param("allUnits") boolean allUnits,
                           @Param("scopeUnitIds") List<Long> scopeUnitIds);

    // ------------------------------------------------------------------ 我发起的

    /** At most {@code limit} tasks created by the caller that still have a blocking item. */
    @Select("""
        <script>
        SELECT t.id         AS objectId,
               t.name       AS label,
               t.status     AS status,
               MIN(i.unit_id) AS unitId,
               t.updated_at AS waitingSince
        FROM release_task t
        JOIN release_item i ON i.task_id = t.id
        WHERE t.creator = #{me}
          AND i.status NOT IN ('DONE', 'GATE_FAILED', 'FAILED', 'REJECTED', 'CLOSED',
                               'TERMINATED', 'ROLLED_BACK')
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        GROUP BY t.id, t.name, t.status, t.updated_at
        ORDER BY t.updated_at ASC, t.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<TodoRow> selectMineTasks(@Param("me") String me,
                                  @Param("allUnits") boolean allUnits,
                                  @Param("scopeUnitIds") List<Long> scopeUnitIds,
                                  @Param("limit") int limit);

    /**
     * The full 我发起的 count: the design defines it as the number of blocking items of the caller's
     * tasks, not the number of tasks (the tasks are the displayed rows).
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM release_task t
        JOIN release_item i ON i.task_id = t.id
        WHERE t.creator = #{me}
          AND i.status NOT IN ('DONE', 'GATE_FAILED', 'FAILED', 'REJECTED', 'CLOSED',
                               'TERMINATED', 'ROLLED_BACK')
        <if test="!allUnits">
          <choose>
            <when test="scopeUnitIds != null and scopeUnitIds.size() > 0">
              AND i.unit_id IN
              <foreach collection="scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </if>
        </script>
        """)
    long countMineItems(@Param("me") String me,
                        @Param("allUnits") boolean allUnits,
                        @Param("scopeUnitIds") List<Long> scopeUnitIds);
}