package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.NodeTaskPO;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import com.sf.release.infra.repo.po.PipelineNodePO;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only SQL of the 发布内容 query (Task 16). It is a separate mapper on purpose: the write
 * mappers stay MyBatis-Plus {@code BaseMapper}s and this one owns the few hand-written reads
 * whose statement count is part of the contract.
 *
 * <p><strong>Query budget.</strong> The detail view must be aggregated in at most three
 * statements — one header join, one node read and one task read — so there is no per-node query
 * (no N+1). {@code ReleaseQueryTest} counts the executed statements with a MyBatis interceptor.
 * The history view needs one ownership lookup plus one instance list, i.e. one statement more
 * than the ownership check alone.
 */
@Mapper
public interface ReleaseQueryMapper {

    /**
     * The 发布单 header with the newest pipeline instance of the order. The {@code LEFT JOIN}
     * keeps an order that has no execution readable, and {@code ORDER BY pi.id DESC LIMIT 1}
     * picks the newest one (the order id is the creation order, which is the execution order).
     */
    @Select("""
        SELECT o.publish_no            AS publishNo,
               o.oa_ticket_no         AS oaTicketNo,
               o.planned_release_time AS plannedReleaseTime,
               i.unit_id              AS unitId,
               pi.id                  AS instanceId,
               pi.pipeline_no         AS pipelineNo,
               pi.trigger_type        AS triggerType,
               pi.status              AS status,
               pi.image_digest        AS imageDigest,
               pi.commit_sha          AS commitSha,
               pi.start_at            AS startAt,
               pi.end_at              AS endAt
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        LEFT JOIN pipeline_instance pi ON pi.order_id = o.id
        WHERE o.publish_no = #{publishNo}
        ORDER BY pi.id DESC
        LIMIT 1
        """)
    ReleaseHeadRow selectHeadRow(@Param("publishNo") String publishNo);

    /**
     * The deployment unit owning the 发布单, or {@code null} when the publish number is unknown.
     * Used by the instance history so the data-scope guard runs before the list is read.
     */
    @Select("""
        SELECT i.unit_id
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        WHERE o.publish_no = #{publishNo}
        """)
    Long selectUnitId(@Param("publishNo") String publishNo);

    /** The nodes of one instance in pipeline order. */
    @Select("""
        SELECT id, instance_id, node_type, seq_no, status, started_at, finished_at
        FROM pipeline_node
        WHERE instance_id = #{instanceId}
        ORDER BY seq_no
        """)
    List<PipelineNodePO> selectNodes(@Param("instanceId") long instanceId);

    /**
     * The tasks of one instance, grouped in memory by node type. Ordered by id, which is the
     * pre-generation order of {@code PipelineFactory} (build task kinds, then deploy batches).
     */
    @Select("""
        SELECT id, instance_id, node_type, task_type, batch_no, target_id, status, attempt,
               external_id, external_url, result_summary, deadline_at, started_at, finished_at,
               duration_ms
        FROM node_task
        WHERE instance_id = #{instanceId}
        ORDER BY id
        """)
    List<NodeTaskPO> selectTasks(@Param("instanceId") long instanceId);

    /** Every execution of a 发布单, newest first (the instance id grows with creation). */
    @Select("""
        SELECT pi.id, pi.order_id, pi.pipeline_no, pi.trigger_type, pi.status,
               pi.start_at, pi.end_at
        FROM pipeline_instance pi
        JOIN release_order o ON o.id = pi.order_id
        WHERE o.publish_no = #{publishNo}
        ORDER BY pi.id DESC
        """)
    List<PipelineInstancePO> selectInstances(@Param("publishNo") String publishNo);
}