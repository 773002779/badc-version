package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.AuditLogCriteria;
import com.sf.release.infra.repo.po.AuditLogPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code audit_log} (V27, audit design sections 2 and 4).
 *
 * <p><strong>Append-only.</strong> There is a single insert and no update/delete statement: the
 * design's guarantee that the application never rewrites an audit row is visible in the mapper's
 * surface, not just in the service's usage.
 *
 * <p><strong>Scope is SQL.</strong> The three read statements share {@link AuditLogCriteria}, whose
 * scope predicate is ANDed with every user filter, so the count, the page and the export cannot
 * disagree about who may read what. The export statement takes a limit one row larger than the
 * design's 5000 so the service can distinguish "exactly 5000" from "truncated".
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogPO> {

    /** Appends one row; the service has already serialized and truncated every column. */
    @Insert("""
        INSERT INTO audit_log (actor, action, object_type, object_id, object_label, summary, reason,
            before_json, after_json, scope_unit_id, scope_subsystem_id, trace_id, created_at)
        VALUES (#{actor}, #{action}, #{objectType}, #{objectId}, #{objectLabel}, #{summary},
            #{reason}, #{beforeJson}, #{afterJson}, #{scopeUnitId}, #{scopeSubsystemId}, #{traceId},
            #{createdAt})
        """)
    int insertAudit(AuditLogPO po);

    /** The full match count of the page and the export filters. */
    @Select("""
        <script>
        SELECT COUNT(*) FROM audit_log a
        <where>
          <if test="criteria.objectType != null">
            AND a.object_type = #{criteria.objectType}
          </if>
          <if test="criteria.objectId != null">
            AND a.object_id = #{criteria.objectId}
          </if>
          <if test="criteria.action != null">
            AND a.action = #{criteria.action}
          </if>
          <if test="criteria.actor != null">
            AND a.actor = #{criteria.actor}
          </if>
          <if test="criteria.from != null">
            AND a.created_at &gt;= #{criteria.from}
          </if>
          <if test="criteria.to != null">
            AND a.created_at &lt;= #{criteria.to}
          </if>
          <if test="criteria.unitId != null">
            AND a.scope_unit_id = #{criteria.unitId}
          </if>
          <if test="!criteria.allUnits">
            AND (a.scope_unit_id IN
              <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
              OR (a.scope_unit_id IS NULL AND a.scope_subsystem_id IN
                (SELECT du.subsystem_id FROM deploy_unit du WHERE du.id IN
                  <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="("
                           separator="," close=")">
                    #{scopeUnitId}
                  </foreach>
                )))
          </if>
        </where>
        </script>
        """)
    long countLogs(@Param("criteria") AuditLogCriteria criteria);

    /** One page, newest first ({@code created_at DESC, id DESC}). */
    @Select("""
        <script>
        SELECT a.* FROM audit_log a
        <where>
          <if test="criteria.objectType != null">
            AND a.object_type = #{criteria.objectType}
          </if>
          <if test="criteria.objectId != null">
            AND a.object_id = #{criteria.objectId}
          </if>
          <if test="criteria.action != null">
            AND a.action = #{criteria.action}
          </if>
          <if test="criteria.actor != null">
            AND a.actor = #{criteria.actor}
          </if>
          <if test="criteria.from != null">
            AND a.created_at &gt;= #{criteria.from}
          </if>
          <if test="criteria.to != null">
            AND a.created_at &lt;= #{criteria.to}
          </if>
          <if test="criteria.unitId != null">
            AND a.scope_unit_id = #{criteria.unitId}
          </if>
          <if test="!criteria.allUnits">
            AND (a.scope_unit_id IN
              <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
              OR (a.scope_unit_id IS NULL AND a.scope_subsystem_id IN
                (SELECT du.subsystem_id FROM deploy_unit du WHERE du.id IN
                  <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="("
                           separator="," close=")">
                    #{scopeUnitId}
                  </foreach>
                )))
          </if>
        </where>
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT #{offset}, #{size}
        </script>
        """)
    List<AuditLogPO> selectPage(@Param("criteria") AuditLogCriteria criteria,
                                @Param("offset") long offset, @Param("size") int size);

    /**
     * The export rows, newest first. The caller asks for 5001 rows: the extra row is how the service
     * tells "the result is exactly 5000" from "there is more".
     */
    @Select("""
        <script>
        SELECT a.* FROM audit_log a
        <where>
          <if test="criteria.objectType != null">
            AND a.object_type = #{criteria.objectType}
          </if>
          <if test="criteria.objectId != null">
            AND a.object_id = #{criteria.objectId}
          </if>
          <if test="criteria.action != null">
            AND a.action = #{criteria.action}
          </if>
          <if test="criteria.actor != null">
            AND a.actor = #{criteria.actor}
          </if>
          <if test="criteria.from != null">
            AND a.created_at &gt;= #{criteria.from}
          </if>
          <if test="criteria.to != null">
            AND a.created_at &lt;= #{criteria.to}
          </if>
          <if test="criteria.unitId != null">
            AND a.scope_unit_id = #{criteria.unitId}
          </if>
          <if test="!criteria.allUnits">
            AND (a.scope_unit_id IN
              <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="(" separator=","
                       close=")">
                #{scopeUnitId}
              </foreach>
              OR (a.scope_unit_id IS NULL AND a.scope_subsystem_id IN
                (SELECT du.subsystem_id FROM deploy_unit du WHERE du.id IN
                  <foreach collection="criteria.scopeUnitIds" item="scopeUnitId" open="("
                           separator="," close=")">
                    #{scopeUnitId}
                  </foreach>
                )))
          </if>
        </where>
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AuditLogPO> selectExport(@Param("criteria") AuditLogCriteria criteria,
                                  @Param("limit") int limit);
}