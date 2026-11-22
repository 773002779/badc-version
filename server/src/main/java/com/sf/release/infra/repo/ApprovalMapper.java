package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ApprovalPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code approval} (V21): the 分级审批 steps of one 发布单.
 * {@code (order_id, step_no)} is unique, so an append is safe without an advisory lock.
 */
@Mapper
public interface ApprovalMapper extends BaseMapper<ApprovalPO> {

    /** The whole chain of one order, in signature order. */
    @Select("SELECT * FROM approval WHERE order_id = #{orderId} ORDER BY step_no")
    List<ApprovalPO> selectByOrder(@Param("orderId") long orderId);

    /**
     * The whole chain again, as a <em>current read</em> that locks the order's rows.
     *
     * <p>The duplicate-key recovery path of {@code ensureChain} uses this: after the unique key
     * rejected our insert, a plain re-read would still see the pre-collision snapshot (MySQL
     * {@code REPEATABLE READ}) and loop forever on the same step number. A locking read waits for
     * the winner's transaction and returns the committed chain.
     */
    @Select("SELECT * FROM approval WHERE order_id = #{orderId} ORDER BY step_no FOR UPDATE")
    List<ApprovalPO> selectByOrderForUpdate(@Param("orderId") long orderId);

    /**
     * Removes the unsigned steps of one role. Decided steps are history and are never touched; a
     * chain that no longer needs a role (the freeze was lifted) drops only what nobody signed.
     */
    @Delete("DELETE FROM approval WHERE order_id = #{orderId} AND role = #{role}"
        + " AND status = 'PENDING'")
    int deletePendingByRole(@Param("orderId") long orderId, @Param("role") String role);
}