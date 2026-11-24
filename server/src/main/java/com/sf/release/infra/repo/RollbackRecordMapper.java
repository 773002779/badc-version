package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.RollbackEnvKindRow;
import com.sf.release.infra.repo.po.RollbackRecordPO;
import com.sf.release.infra.repo.po.RollbackVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code rollback_record} plus the version queries of the rollback page
 * (V25, rollback design section 2).
 *
 * <p><strong>Where the versions come from.</strong> The platform has no artifact index yet, so a
 * <em>rollback target</em> is a fully deployed forward release: an image digest whose
 * {@code PROD_DEPLOY} node reached {@code SUCCESS} (every batch of every production DE deployed) in
 * an {@code INITIAL}/{@code RERUN_*} instance. A release that failed mid-batch is deliberately not a
 * target: only part of its image is online, so "roll back to it" would mean a partial rollout.
 *
 * <p><strong>Current version.</strong> The current online image is the newest successful production
 * deploy task of any trigger, including a {@code ROLLBACK}: after a rollback the target image is what
 * production runs, so the drawer must say so and a repeated rollback to the same image must be
 * refused. For a rollback instance the publish number/operator come from the rollback record (the
 * source order is the release being undone, not the one that produced the target image).
 *
 * <p><strong>Same digest, several deployments.</strong> The window ranks each digest's deployments
 * by {@code finished_at} and keeps the newest, so the publish number / operator shown are the ones
 * of the deployment that actually put that image online. The batch count and environment kinds are
 * aggregated over the whole digest history by two small companion queries (no per-digest statement,
 * no {@code GROUP_CONCAT} parsing).
 */
@Mapper
public interface RollbackRecordMapper extends BaseMapper<RollbackRecordPO> {

    /**
     * The image production runs now: the newest successful production deploy task of {@code unitId},
     * of any trigger. {@code null} when the unit never deployed to production successfully.
     */
    @Select("""
        SELECT pi.image_digest                    AS digest,
               nt.finished_at                     AS finishedAt,
               COALESCE(rr.target_publish_no, ro.publish_no) AS publishNo,
               COALESCE(rr.requested_by, ro.executed_by)     AS executedBy,
               pi.id                               AS instanceId,
               pi.commit_sha                       AS commitSha
        FROM node_task nt
        JOIN pipeline_instance pi ON pi.id = nt.instance_id
        JOIN release_order ro ON ro.id = pi.order_id
        JOIN release_item ri ON ri.id = ro.item_id
        LEFT JOIN rollback_record rr ON rr.instance_id = pi.id
        WHERE ri.unit_id = #{unitId}
          AND nt.node_type = 'PROD_DEPLOY'
          AND nt.task_type = 'DEPLOY'
          AND nt.status = 'SUCCESS'
          AND pi.image_digest IS NOT NULL
        ORDER BY nt.finished_at DESC, nt.id DESC
        LIMIT 1
        """)
    RollbackVersionRow selectCurrent(@Param("unitId") long unitId);

    /**
     * Up to ten rollback targets of {@code unitId}: fully deployed forward releases, one row per
     * image digest, newest first, excluding {@code excludeDigest} (the current online image).
     */
    @Select("""
        SELECT ranked.digest   AS digest,
               ranked.finishedAt AS finishedAt,
               ranked.publishNo AS publishNo,
               ranked.executedBy AS executedBy,
               ranked.instanceId AS instanceId,
               ranked.commitSha AS commitSha
        FROM (
          SELECT pi.image_digest AS digest,
                 nt.finished_at  AS finishedAt,
                 ro.publish_no   AS publishNo,
                 ro.executed_by  AS executedBy,
                 pi.id           AS instanceId,
                 pi.commit_sha   AS commitSha,
                 ROW_NUMBER() OVER (PARTITION BY pi.image_digest
                                    ORDER BY nt.finished_at DESC, nt.id DESC) AS rn
          FROM node_task nt
          JOIN pipeline_instance pi ON pi.id = nt.instance_id
          JOIN pipeline_node pn ON pn.instance_id = pi.id AND pn.node_type = 'PROD_DEPLOY'
          JOIN release_order ro ON ro.id = pi.order_id
          JOIN release_item ri ON ri.id = ro.item_id
          WHERE ri.unit_id = #{unitId}
            AND nt.node_type = 'PROD_DEPLOY'
            AND nt.task_type = 'DEPLOY'
            AND nt.status = 'SUCCESS'
            AND pi.image_digest IS NOT NULL
            AND pi.trigger_type <> 'ROLLBACK'
            AND pn.status = 'SUCCESS'
            AND pi.image_digest <> #{excludeDigest}
        ) ranked
        WHERE ranked.rn = 1
        ORDER BY ranked.finishedAt DESC
        LIMIT 10
        """)
    List<RollbackVersionRow> selectHistory(@Param("unitId") long unitId,
                                           @Param("excludeDigest") String excludeDigest);

    /**
     * Distinct deploy batches per image digest: the drawer labels this 「批次数」, so it counts the
     * serial batches of the production release (the same {@code deploy_batch_no} the rollback
     * reverses), not the individual DEs inside a batch.
     */
    @Select("""
        SELECT pi.image_digest          AS digest,
               COUNT(DISTINCT nt.batch_no) AS batchCount
        FROM node_task nt
        JOIN pipeline_instance pi ON pi.id = nt.instance_id
        JOIN release_order ro ON ro.id = pi.order_id
        JOIN release_item ri ON ri.id = ro.item_id
        WHERE ri.unit_id = #{unitId}
          AND nt.node_type = 'PROD_DEPLOY'
          AND nt.task_type = 'DEPLOY'
          AND nt.status = 'SUCCESS'
          AND pi.image_digest IS NOT NULL
          AND pi.trigger_type <> 'ROLLBACK'
        GROUP BY pi.image_digest
        """)
    List<RollbackVersionRow> selectVersionBatchCounts(@Param("unitId") long unitId);

    /** The environment kinds each digest was deployed to (PROD / DR / BLUE chips). */
    @Select("""
        SELECT pi.image_digest AS digest,
               ed.env_kind     AS envKind
        FROM node_task nt
        JOIN pipeline_instance pi ON pi.id = nt.instance_id
        JOIN release_order ro ON ro.id = pi.order_id
        JOIN release_item ri ON ri.id = ro.item_id
        JOIN env_de ed ON ed.id = nt.target_id
        WHERE ri.unit_id = #{unitId}
          AND nt.node_type = 'PROD_DEPLOY'
          AND nt.task_type = 'DEPLOY'
          AND nt.status = 'SUCCESS'
          AND pi.image_digest IS NOT NULL
          AND pi.trigger_type <> 'ROLLBACK'
        GROUP BY pi.image_digest, ed.env_kind
        ORDER BY pi.image_digest, ed.env_kind
        """)
    List<RollbackEnvKindRow> selectVersionEnvKinds(@Param("unitId") long unitId);

    /**
     * The record of one pipeline instance, or {@code null} when the instance is not a rollback.
     * One instance can only ever produce one rollback record (the id is written in the creation
     * transaction); the newest row is returned defensively.
     */
    @Select("SELECT * FROM rollback_record WHERE instance_id = #{instanceId}"
        + " ORDER BY id DESC LIMIT 1")
    RollbackRecordPO selectByInstanceId(@Param("instanceId") long instanceId);
}