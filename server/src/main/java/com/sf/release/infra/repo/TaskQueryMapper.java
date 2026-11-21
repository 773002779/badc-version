package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.TaskItemRow;
import com.sf.release.infra.repo.po.TaskMemberRow;
import com.sf.release.infra.repo.po.TaskStatusCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only SQL of the task read API (P1 task list/detail/items). It is separate from the
 * write-side {@code BaseMapper}s because these reads join tables and their statement count
 * is part of the contract:
 *
 * <p><strong>Query budget.</strong> The task page aggregates the progress of every task on
 * the page in one grouped query ({@link #selectStatusCounts}), and the item list reads the
 * deployment unit name with a single joined statement ({@link #selectItems}). There is no
 * per-task or per-item follow-up query; {@code TaskReadControllerTest} counts statements.
 */
@Mapper
public interface TaskQueryMapper {

    /**
     * {@code status -> count} of every item of the given tasks. The caller groups by task id
     * in memory and applies {@code TaskProgress.ofCounts}, so the progress rule lives only in
     * the domain.
     */
    @Select("<script>"
        + "SELECT task_id AS taskId, status, COUNT(*) AS cnt"
        + " FROM release_item"
        + " WHERE task_id IN "
        + "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>"
        + "#{taskId}</foreach>"
        + " GROUP BY task_id, status"
        + "</script>")
    List<TaskStatusCountRow> selectStatusCounts(@Param("taskIds") List<Long> taskIds);

    /** The items of one task with the deployment unit name, newest first. */
    @Select("""
        SELECT i.id             AS id,
               i.crid           AS crid,
               i.unit_id        AS unitId,
               u.name           AS unitName,
               i.type           AS type,
               i.version        AS version,
               i.source_branch  AS sourceBranch,
               i.plan_release_date AS planReleaseDate,
               i.status         AS status,
               i.gate_status    AS gateStatus,
               i.created_at     AS createdAt,
               i.updated_at     AS updatedAt
        FROM release_item i
        LEFT JOIN deploy_unit u ON u.id = i.unit_id
        WHERE i.task_id = #{taskId}
        ORDER BY i.id DESC
        """)
    List<TaskItemRow> selectItems(@Param("taskId") long taskId);

    /** The members of one task with the display name, ordered by user id. */
    @Select("""
        SELECT m.user_id AS userId,
               u.name    AS name,
               m.role    AS role
        FROM release_task_member m
        LEFT JOIN app_user u ON u.id = m.user_id
        WHERE m.task_id = #{taskId}
        ORDER BY m.user_id
        """)
    List<TaskMemberRow> selectMembers(@Param("taskId") long taskId);
}