package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.ReleaseRecordRow;
import com.sf.release.infra.repo.po.ReleaseRecordsCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only SQL of the global release-records page (GAP-10, P4-A Task 2).
 *
 * <p><strong>Query budget.</strong> The page is exactly one count statement and one page statement;
 * the newest {@code pipeline_instance} of every order is resolved inside the page statement by a
 * correlated {@code MAX(id)} join, so there is no per-order query (no N+1). The controllers test
 * counts the executed statements.
 *
 * <p><strong>Filters.</strong> {@code keyword} matches the publish number or the CRID; {@code status}
 * matches the release order status; {@code subsystemId}/{@code unitId} narrow by system and unit. The
 * caller's data scope ({@code unitId IN (...)}) is ANDed with all of them, so no filter can widen
 * it. The count and the page share one {@link ReleaseRecordsCriteria} so their predicates cannot
 * drift apart.
 */
@Mapper
public interface ReleaseRecordsMapper {

    /** Total release orders matching the same filter as the page query. */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        <where>
          <if test="criteria.keyword != null">
            AND (o.publish_no LIKE CONCAT('%', #{criteria.keyword}, '%')
                 OR i.crid LIKE CONCAT('%', #{criteria.keyword}, '%'))
          </if>
          <if test="criteria.status != null">
            AND o.status = #{criteria.status}
          </if>
          <if test="criteria.subsystemId != null">
            AND u.subsystem_id = #{criteria.subsystemId}
          </if>
          <if test="criteria.unitId != null">
            AND i.unit_id = #{criteria.unitId}
          </if>
          <if test="!criteria.allUnits">
            AND i.unit_id IN
            <foreach collection="criteria.scopeUnitIds" item="scopeUnitId"
                     open="(" separator="," close=")">
              #{scopeUnitId}
            </foreach>
          </if>
        </where>
        </script>
        """)
    long countRecords(@Param("criteria") ReleaseRecordsCriteria criteria);

    /**
     * One page of release orders, newest first ({@code created_at DESC, id DESC}). The joined
     * pipeline is the instance with the largest id of the order (the newest execution); it is
     * {@code null} for an order without one.
     */
    @Select("""
        <script>
        SELECT o.publish_no            AS publishNo,
               i.id                    AS itemId,
               i.crid                  AS crid,
               i.version               AS version,
               u.subsystem_id          AS subsystemId,
               s.name                  AS subsystemName,
               u.id                    AS unitId,
               u.name                  AS unitName,
               o.status                AS orderStatus,
               i.status                AS itemStatus,
               o.planned_release_time  AS plannedReleaseTime,
               o.created_at            AS createdAt,
               o.executed_by           AS executedBy,
               o.emergency_release     AS emergencyRelease,
               o.emergency_reason      AS emergencyReason,
               o.emergency_by          AS emergencyBy,
               o.emergency_at          AS emergencyAt,
               pi.pipeline_no          AS pipelineNo,
               pi.status               AS pipelineStatus
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        JOIN subsystem s ON s.id = u.subsystem_id
        LEFT JOIN pipeline_instance pi ON pi.id = (
          SELECT MAX(latest.id) FROM pipeline_instance latest WHERE latest.order_id = o.id)
        <where>
          <if test="criteria.keyword != null">
            AND (o.publish_no LIKE CONCAT('%', #{criteria.keyword}, '%')
                 OR i.crid LIKE CONCAT('%', #{criteria.keyword}, '%'))
          </if>
          <if test="criteria.status != null">
            AND o.status = #{criteria.status}
          </if>
          <if test="criteria.subsystemId != null">
            AND u.subsystem_id = #{criteria.subsystemId}
          </if>
          <if test="criteria.unitId != null">
            AND i.unit_id = #{criteria.unitId}
          </if>
          <if test="!criteria.allUnits">
            AND i.unit_id IN
            <foreach collection="criteria.scopeUnitIds" item="scopeUnitId"
                     open="(" separator="," close=")">
              #{scopeUnitId}
            </foreach>
          </if>
        </where>
        ORDER BY o.created_at DESC, o.id DESC
        LIMIT #{offset}, #{size}
        </script>
        """)
    List<ReleaseRecordRow> selectRecords(@Param("criteria") ReleaseRecordsCriteria criteria,
                                         @Param("offset") long offset,
                                         @Param("size") int size);
}