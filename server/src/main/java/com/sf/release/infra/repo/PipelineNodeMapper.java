package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineNodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/** MyBatis-Plus mapper for {@code pipeline_node}. */
@Mapper
public interface PipelineNodeMapper extends BaseMapper<PipelineNodePO> {

    /**
     * Completes a node only when every counted task is {@code SUCCESS} (or {@code SKIPPED}) at
     * the instant of the update (tech design §5.2 rule 2). Two rules are mirrored from the
     * domain fold:
     * <ul>
     *   <li><strong>latest attempt only</strong>: a retained older {@code FAILED}/{@code TIMEOUT}
     *       row cannot block a node whose retried row succeeded (design §5.4 keeps old rows);</li>
     *   <li><strong>{@code CHECK} tasks are excluded</strong>: gate results arrive asynchronously
     *       and must not block {@code VERIFY} completion (design §5.2 rule 4).</li>
     * </ul>
     *
     * <p>The guard is SQL on purpose: task completions are persisted outside the instance
     * lock, so an in-memory "all succeeded" check could be stale by the time the node is
     * finished. A non-success latest attempt makes the update match zero rows.
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
    int completeIfAllTasksSucceeded(@Param("nodeId") long nodeId,
                                    @Param("now") Instant now);
}