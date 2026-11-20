package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/** MyBatis-Plus mapper for {@code node_task}. */
@Mapper
public interface NodeTaskMapper extends BaseMapper<NodeTaskPO> {

    /**
     * Skips the still-pending tasks of one node after a sibling failed (tech design §5.2 rule
     * 3). Only {@code PENDING} rows are touched, so an already successful task and a task that
     * is currently running keep their state.
     *
     * @return the number of tasks skipped
     */
    @Update("UPDATE node_task SET status = 'SKIPPED', finished_at = #{now}"
        + " WHERE instance_id = #{instanceId} AND node_type = #{nodeType}"
        + " AND status = 'PENDING'")
    int skipPending(@Param("instanceId") long instanceId,
                    @Param("nodeType") String nodeType,
                    @Param("now") Instant now);
}