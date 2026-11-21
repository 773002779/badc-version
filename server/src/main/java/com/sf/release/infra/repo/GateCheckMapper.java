package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.GateCheckPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** MyBatis-Plus mapper for {@code gate_check} (V17). */
@Mapper
public interface GateCheckMapper extends BaseMapper<GateCheckPO> {

    /** Every check row of one 变更项, in pipeline (stage, kind) order. */
    @Select("SELECT * FROM gate_check WHERE item_id = #{itemId}"
        + " ORDER BY FIELD(stage, 'IMAGE', 'ENV'), id")
    List<GateCheckPO> selectByItem(@Param("itemId") long itemId);

    /** The check rows of one pipeline instance (the VERIFY node's own view). */
    @Select("SELECT * FROM gate_check WHERE instance_id = #{instanceId} ORDER BY id")
    List<GateCheckPO> selectByInstance(@Param("instanceId") long instanceId);

    /**
     * The checks the reconcile loop must poll: an engine id exists and either the result has not
     * been observed for {@code staleBefore} or its per-check deadline passed. {@code UNKNOWN} is
     * included on purpose — an unreachable engine must be retried, not frozen into a "not passed"
     * verdict forever.
     */
    @Select("SELECT * FROM gate_check WHERE status IN ('PENDING', 'RUNNING', 'UNKNOWN')"
        + " AND external_id IS NOT NULL"
        + " AND (updated_at IS NULL OR updated_at < #{staleBefore}"
        + "   OR (deadline_at IS NOT NULL AND deadline_at < #{now}))"
        + " ORDER BY id LIMIT #{limit}")
    List<GateCheckPO> selectDue(@Param("staleBefore") Instant staleBefore,
                                @Param("now") Instant now,
                                @Param("limit") int limit);
}