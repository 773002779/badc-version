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
 * <p><strong>Where the versions come from.</strong> The platform has no artifact index yet, so the
 * version list is derived from successful production deployments: one row per image digest, ordered
 * by the latest successful {@code PROD_DEPLOY/DEPLOY} task. Only successful tasks count, the
 * instance must carry an image digest, and rollback instances are excluded — a rolled-back image is
 * a deployment result, not a deployable release version. {@code LIMIT 11} yields the current version
 * plus at most ten historical ones.
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
     * The current version plus up to ten historical ones of {@code unitId}, newest first. Each row
     * is the latest successful production deployment of one image digest.
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
          JOIN release_order ro ON ro.id = pi.order_id
          JOIN release_item ri ON ri.id = ro.item_id
          WHERE ri.unit_id = #{unitId}
            AND nt.node_type = 'PROD_DEPLOY'
            AND nt.task_type = 'DEPLOY'
            AND nt.status = 'SUCCESS'
            AND pi.image_digest IS NOT NULL
            AND pi.trigger_type <> 'ROLLBACK'
        ) ranked
        WHERE ranked.rn = 1
        ORDER BY ranked.finishedAt DESC
        LIMIT 11
        """)
    List<RollbackVersionRow> selectVersions(@Param("unitId") long unitId);

    /** Distinct successful deploy targets (DEs) per image digest: the page's batch count. */
    @Select("""
        SELECT pi.image_digest          AS digest,
               COUNT(DISTINCT nt.target_id) AS batchCount
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