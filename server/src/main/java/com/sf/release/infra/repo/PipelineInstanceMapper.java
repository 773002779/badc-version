package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.PipelineInstancePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

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

    /**
     * The in-flight instances of one 发布单 (P5-B design §2 step 1): {@code PENDING}, {@code RUNNING}
     * or {@code PAUSED}. At most one online instance of an order can be live at a time (the unit lock
     * serialises publishes), so the caller treats the newest row as <em>the</em> active instance and
     * still tolerates a list (a leaked older row must not hide the newest one).
     */
    @Select("SELECT * FROM pipeline_instance WHERE order_id = #{orderId}"
        + " AND status IN ('PENDING', 'RUNNING', 'PAUSED') ORDER BY id DESC")
    List<PipelineInstancePO> selectActiveByOrder(@Param("orderId") long orderId);

    /**
     * Every in-flight instance of one 变更项: the online instances of its release orders plus the
     * daily instances, which carry {@code item_id} directly and have no order (V28). Closing an item
     * must end all of them, not just the online one.
     */
    @Select("SELECT * FROM pipeline_instance pi WHERE pi.status IN ('PENDING', 'RUNNING', 'PAUSED')"
        + " AND (pi.item_id = #{itemId}"
        + "   OR EXISTS (SELECT 1 FROM release_order o WHERE o.id = pi.order_id"
        + "              AND o.item_id = #{itemId}))"
        + " ORDER BY pi.id DESC")
    List<PipelineInstancePO> selectActiveByItem(@Param("itemId") long itemId);

    /**
     * Every id of the instances that belong to one 发布任务 — online instances through
     * {@code order -> item -> task}, daily instances through their own {@code item_id} (P5-B archive:
     * release the whole task's unit locks, including a leaked row of an already finished instance).
     */
    @Select("SELECT pi.id FROM pipeline_instance pi WHERE"
        + " EXISTS (SELECT 1 FROM release_order o JOIN release_item i ON i.id = o.item_id"
        + "         WHERE o.id = pi.order_id AND i.task_id = #{taskId})"
        + " OR EXISTS (SELECT 1 FROM release_item i2 WHERE i2.id = pi.item_id"
        + "            AND i2.task_id = #{taskId})"
        + " ORDER BY pi.id")
    List<Long> selectIdsByTask(@Param("taskId") long taskId);

    /**
     * Every in-flight instance of a 发布任务, including the instances that are not tied to one of the
     * task's unfinished 变更项 (a rollback instance of a {@code DONE} item, a daily release of an item
     * that is still {@code PENDING}). Archiving a task cancels those too (P5-B design §1 cascade).
     */
    @Select("SELECT pi.* FROM pipeline_instance pi WHERE pi.status IN ('PENDING', 'RUNNING', 'PAUSED')"
        + " AND (EXISTS (SELECT 1 FROM release_order o JOIN release_item i ON i.id = o.item_id"
        + "             WHERE o.id = pi.order_id AND i.task_id = #{taskId})"
        + "   OR EXISTS (SELECT 1 FROM release_item i2 WHERE i2.id = pi.item_id"
        + "             AND i2.task_id = #{taskId}))"
        + " ORDER BY pi.id")
    List<PipelineInstancePO> selectActiveByTask(@Param("taskId") long taskId);
}