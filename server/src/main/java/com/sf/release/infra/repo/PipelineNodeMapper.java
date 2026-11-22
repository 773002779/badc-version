package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineNodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** MyBatis-Plus mapper for {@code pipeline_node}. */
@Mapper
public interface PipelineNodeMapper extends BaseMapper<PipelineNodePO> {

    /**
     * Claims one task for an engine trigger: {@code PENDING → RUNNING} in a single compare-and-set,
     * bumping {@code attempt} as the "this row was started" marker.
     *
     * <p><strong>Why a claim, not a status check.</strong> A node handler runs outside the instance
     * lock (the orchestrator holds it only for the entry and the visit), so two completion callbacks
     * can decide "the batch succeeded" at the same time and both start the next batch. Claiming each
     * task <em>before</em> the engine call makes the trigger exactly-once: only the caller whose
     * update matched may call {@code triggerDeploy}, the loser skips the task.
     *
     * <p>The {@code attempt} counter is bumped for the row-liveness marker; the command keeps the
     * attempt number the task was generated with (a retry is a new row, design §5.4).
     *
     * @return 1 when this caller claimed the task, 0 when it was not {@code PENDING} any more
     */
    @Update("UPDATE node_task SET status = 'RUNNING', attempt = attempt + 1, started_at = #{now}"
        + " WHERE id = #{taskId} AND status = 'PENDING'")
    int claimTaskStart(@Param("taskId") long taskId, @Param("now") Instant now);

    /**
     * Completes a node only when every counted task is {@code SUCCESS} (or {@code SKIPPED}) at
     * the instant of the update (tech design §5.2 rule 2), and only when at least one counted
     * task exists. Two rules are mirrored from the domain fold:
     * <ul>
     *   <li><strong>latest attempt only</strong>: a retained older {@code FAILED}/{@code TIMEOUT}
     *       row cannot block a node whose retried row succeeded (design §5.4 keeps old rows);</li>
     *   <li><strong>{@code CHECK} tasks are excluded</strong>: gate results arrive asynchronously
     *       and must not block {@code VERIFY} completion (design §5.2 rule 4).</li>
     * </ul>
     *
     * <p><strong>No empty node completes.</strong> The {@code EXISTS} clause requires at least
     * one counted (non-{@code CHECK}) task, so a node with no tasks at all — or a check-only
     * {@code VERIFY} node — is never auto-completed; skipping such a node would silently bypass
     * its gate step.
     *
     * <p>The guard is SQL on purpose: task completions are persisted outside the instance
     * lock, so an in-memory "all succeeded" check could be stale by the time the node is
     * finished. A non-success latest attempt, or a node without a counted task, makes the
     * update match zero rows.
     *
     * @return 1 when the node transitioned to {@code SUCCESS}, 0 otherwise
     */
    @Update("UPDATE pipeline_node SET status = 'SUCCESS', finished_at = #{now}"
        + " WHERE id = #{nodeId} AND status = 'RUNNING'"
        + " AND EXISTS (SELECT 1 FROM node_task t0"
        + "   WHERE t0.instance_id = pipeline_node.instance_id"
        + "   AND t0.node_type = pipeline_node.node_type"
        + "   AND t0.task_type <> 'CHECK'"
        + "   AND NOT EXISTS (SELECT 1 FROM node_task t3"
        + "     WHERE t3.instance_id = t0.instance_id"
        + "     AND t3.node_type = t0.node_type"
        + "     AND t3.task_type = t0.task_type"
        + "     AND t3.batch_no = t0.batch_no"
        + "     AND t3.target_id = t0.target_id"
        + "     AND t3.attempt > t0.attempt))"
        + " AND NOT EXISTS (SELECT 1 FROM node_task t"
        + "   WHERE t.instance_id = pipeline_node.instance_id"
        + "   AND t.node_type = pipeline_node.node_type"
        + "   AND t.task_type <> 'CHECK'"
        + "   AND t.status NOT IN ('SUCCESS', 'SKIPPED')"
        + "   AND NOT EXISTS (SELECT 1 FROM node_task t2"
        + "     WHERE t2.instance_id = t.instance_id"
        + "     AND t2.node_type = t.node_type"
        + "     AND t2.task_type = t.task_type"
        + "     AND t2.batch_no = t.batch_no"
        + "     AND t2.target_id = t.target_id"
        + "     AND t2.attempt > t.attempt))")
    int completeIfAllTasksSucceeded(@Param("nodeId") long nodeId,
                                    @Param("now") Instant now);

    /**
     * Completes a node whose completion is decided by its {@code NodeHandler} rather than by
     * counted task rows ({@code VERIFY}: the gate decides, the check rows are its evidence).
     *
     * <p>The guard is the same fold as {@link #completeIfAllTasksSucceeded} minus the "at least one
     * counted task" requirement: no counted latest attempt may be non-successful. That keeps a node
     * from being completed while, say, a deploy task of the same node is still running — the
     * handler's word alone is not enough.
     *
     * @return 1 when the node transitioned to {@code SUCCESS}, 0 otherwise
     */
    @Update("UPDATE pipeline_node SET status = 'SUCCESS', finished_at = #{now}"
        + " WHERE id = #{nodeId} AND status = 'RUNNING'"
        + " AND NOT EXISTS (SELECT 1 FROM node_task t"
        + "   WHERE t.instance_id = pipeline_node.instance_id"
        + "   AND t.node_type = pipeline_node.node_type"
        + "   AND t.task_type <> 'CHECK'"
        + "   AND t.status NOT IN ('SUCCESS', 'SKIPPED')"
        + "   AND NOT EXISTS (SELECT 1 FROM node_task t2"
        + "     WHERE t2.instance_id = t.instance_id"
        + "     AND t2.node_type = t.node_type"
        + "     AND t2.task_type = t.task_type"
        + "     AND t2.batch_no = t.batch_no"
        + "     AND t2.target_id = t.target_id"
        + "     AND t2.attempt > t.attempt))")
    int completeSatisfiedByHandler(@Param("nodeId") long nodeId, @Param("now") Instant now);

    /**
     * RUNNING nodes that look stuck (I4): the node was entered before {@code startedBefore} and
     * every counted (non-{@code CHECK}) latest attempt of the node is still {@code PENDING}.
     *
     * <p>That is exactly the state a node is left in when its handler never ran (a crash
     * between the node-entry commit and the handler call, or a handler that silently did
     * nothing). Reconcile only <em>reports</em> these rows: restarting a handler is not
     * idempotent and needs a row-level entry claim (P3), so this query changes no state. A node
     * with at least one running/waiting/succeeded task is not returned; a node without any
     * counted task (check-only) is not stuck, it is waiting for its asynchronous results.
     */
    @Select("SELECT n.* FROM pipeline_node n"
        + " WHERE n.status = 'RUNNING'"
        + " AND n.started_at IS NOT NULL AND n.started_at < #{startedBefore}"
        + " AND EXISTS (SELECT 1 FROM node_task t0"
        + "   WHERE t0.instance_id = n.instance_id"
        + "   AND t0.node_type = n.node_type"
        + "   AND t0.task_type <> 'CHECK'"
        + "   AND NOT EXISTS (SELECT 1 FROM node_task t3"
        + "     WHERE t3.instance_id = t0.instance_id"
        + "     AND t3.node_type = t0.node_type"
        + "     AND t3.task_type = t0.task_type"
        + "     AND t3.batch_no = t0.batch_no"
        + "     AND t3.target_id = t0.target_id"
        + "     AND t3.attempt > t0.attempt))"
        + " AND NOT EXISTS (SELECT 1 FROM node_task t"
        + "   WHERE t.instance_id = n.instance_id"
        + "   AND t.node_type = n.node_type"
        + "   AND t.task_type <> 'CHECK'"
        + "   AND t.status <> 'PENDING'"
        + "   AND NOT EXISTS (SELECT 1 FROM node_task t2"
        + "     WHERE t2.instance_id = t.instance_id"
        + "     AND t2.node_type = t.node_type"
        + "     AND t2.task_type = t.task_type"
        + "     AND t2.batch_no = t.batch_no"
        + "     AND t2.target_id = t.target_id"
        + "     AND t2.attempt > t.attempt))"
        + " ORDER BY n.id LIMIT #{limit}")
    List<PipelineNodePO> selectStuckNodes(@Param("startedBefore") Instant startedBefore,
                                          @Param("limit") int limit);
}