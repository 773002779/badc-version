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
     * Completes a node only when every one of its tasks is {@code SUCCESS} or {@code SKIPPED}
     * at the instant of the update (tech design §5.2 rule 2).
     *
     * <p>This guard is SQL on purpose: task completions are persisted outside the instance
     * lock, so an in-memory "all succeeded" check could be stale by the time the node is
     * finished. A {@code FAILED}/{@code TIMEOUT} task makes the update match zero rows.
     *
     * @return 1 when the node transitioned to {@code SUCCESS}, 0 otherwise
     */
    @Update("UPDATE pipeline_node SET status = 'SUCCESS', finished_at = #{now}"
        + " WHERE id = #{nodeId} AND status = 'RUNNING'"
        + " AND NOT EXISTS (SELECT 1 FROM node_task t"
        + "   WHERE t.instance_id = pipeline_node.instance_id"
        + "   AND t.node_type = pipeline_node.node_type"
        + "   AND t.status IN ('FAILED', 'TIMEOUT'))"
        + " AND NOT EXISTS (SELECT 1 FROM node_task t"
        + "   WHERE t.instance_id = pipeline_node.instance_id"
        + "   AND t.node_type = pipeline_node.node_type"
        + "   AND t.status NOT IN ('SUCCESS', 'SKIPPED'))")
    int completeIfAllTasksSucceeded(@Param("nodeId") long nodeId,
                                    @Param("now") Instant now);
}