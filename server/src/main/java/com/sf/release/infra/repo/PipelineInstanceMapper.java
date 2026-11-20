package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/** MyBatis-Plus mapper for {@code pipeline_instance}. */
@Mapper
public interface PipelineInstanceMapper extends BaseMapper<PipelineInstancePO> {

    /**
     * Durably claims the once-only TEST environment ready publication (Task 13): the first
     * update sets {@code test_ready_at}, all later calls match no row because the column is no
     * longer {@code NULL}. The compare-and-set is in the SQL (not a read-then-write) so two
     * application instances cannot both win.
     *
     * @return 1 when this call claimed the publication, 0 otherwise
     */
    @Update("UPDATE pipeline_instance SET test_ready_at = #{readyAt}"
        + " WHERE id = #{instanceId} AND test_ready_at IS NULL")
    int claimTestReady(@Param("instanceId") long instanceId,
                       @Param("readyAt") Instant readyAt);
}