package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** MyBatis-Plus mapper for {@code node_task}. */
@Mapper
public interface NodeTaskMapper extends BaseMapper<NodeTaskPO> {

    /**
     * Skips the still-pending tasks of one node after a sibling failed (tech design §5.2 rule
     * 3). Only {@code PENDING} rows are touched, so an already successful task and a task that
     * is currently running keep their state.
     *
     * @return the number of tasks skipped
     */
    @Update("UPDATE node_task SET status = 'SKIPPED', finished_at = #{now}, updated_at = #{now}"
        + " WHERE instance_id = #{instanceId} AND node_type = #{nodeType}"
        + " AND status = 'PENDING'")
    int skipPending(@Param("instanceId") long instanceId,
                    @Param("nodeType") String nodeType,
                    @Param("now") Instant now);

    /**
     * The {@code WAITING_EXTERNAL} tasks the reconcile loop must poll (tech design §6.3):
     * external jobs that have not been observed for {@code 90s}, plus every external job whose
     * deadline passed (a timeout must not be delayed by a recent observation).
     *
     * @param staleBefore {@code now - 90s}
     */
    @Select("SELECT * FROM node_task WHERE status = 'WAITING_EXTERNAL'"
        + " AND external_id IS NOT NULL"
        + " AND (updated_at IS NULL OR updated_at < #{staleBefore}"
        + "   OR (deadline_at IS NOT NULL AND deadline_at < #{now}))"
        + " ORDER BY id LIMIT #{limit}")
    List<NodeTaskPO> selectStaleWaiting(@Param("staleBefore") Instant staleBefore,
                                        @Param("now") Instant now,
                                        @Param("limit") int limit);

    /**
     * The most recent task row carrying {@code externalId} <em>of one engine job family</em>.
     * The column is indexed but not unique (a retried task keeps the old row), so the callback
     * resolves the latest row and the completion service's CAS makes a late callback a no-op.
     *
     * <p><strong>Kind-scoped on purpose.</strong> Build and deploy jobs are different CODING
     * namespaces and their ids may collide; a build callback must never complete a deploy task
     * (or the other way round). {@code taskTypes} is the family's task-type vocabulary, so a
     * callback for the wrong kind simply finds no row.
     *
     * @param taskTypes the task types of the callback kind; must not be empty
     */
    @Select("<script>SELECT * FROM node_task WHERE external_id = #{externalId}"
        + " AND task_type IN"
        + " <foreach collection='taskTypes' item='taskType' open='(' separator=',' close=')'>"
        + "#{taskType}</foreach>"
        + " ORDER BY id DESC LIMIT 1</script>")
    NodeTaskPO selectLatestByExternalId(@Param("externalId") String externalId,
                                       @Param("taskTypes") List<String> taskTypes);

    /** The current consecutive-failure counter of one task, for the reconcile escalation. */
    @Select("SELECT query_failures FROM node_task WHERE id = #{taskId}")
    Integer selectQueryFailures(@Param("taskId") long taskId);

    /** Records a successful non-terminal observation (RUNNING/QUEUED): reset failures, move poll time. */
    @Update("UPDATE node_task SET query_failures = 0, updated_at = #{now}"
        + " WHERE id = #{taskId} AND status = 'WAITING_EXTERNAL'")
    int recordQuerySuccess(@Param("taskId") long taskId, @Param("now") Instant now);

    /** Records one failed engine query; {@code updated_at} is deliberately left alone. */
    @Update("UPDATE node_task SET query_failures = query_failures + 1"
        + " WHERE id = #{taskId} AND status = 'WAITING_EXTERNAL'")
    int recordQueryFailure(@Param("taskId") long taskId);
}