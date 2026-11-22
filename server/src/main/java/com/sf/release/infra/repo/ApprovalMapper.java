package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ApprovalPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code approval} (V21/V22): the 分级审批 steps of one admission snapshot.
 *
 * <p>The chain is <em>snapshot-scoped</em> (V22): {@code (snapshot_id, step_no)} is unique, so a new
 * admission snapshot starts its own chain at step 1 while the rejected generation keeps its rows,
 * and an append is still safe without an advisory lock.
 */
@Mapper
public interface ApprovalMapper extends BaseMapper<ApprovalPO> {

    /** The whole chain of one snapshot, in signature order. */
    @Select("SELECT * FROM approval WHERE snapshot_id = #{snapshotId} ORDER BY step_no")
    List<ApprovalPO> selectBySnapshot(@Param("snapshotId") long snapshotId);

    /**
     * The same chain again, as a <em>current read</em> that locks the snapshot's rows.
     *
     * <p>The duplicate-key recovery path of {@code ensureChain} uses this: after the unique key
     * rejected our insert, a plain re-read would still see the pre-collision snapshot (MySQL
     * {@code REPEATABLE READ}) and loop forever on the same step number. A locking read waits for
     * the winner's transaction and returns the committed chain.
     */
    @Select("SELECT * FROM approval WHERE snapshot_id = #{snapshotId} ORDER BY step_no FOR UPDATE")
    List<ApprovalPO> selectBySnapshotForUpdate(@Param("snapshotId") long snapshotId);

    /**
     * Removes the unsigned steps of one role from one snapshot's chain. Decided steps are history
     * and are never touched; a chain that no longer needs a role (the freeze was lifted) drops only
     * what nobody signed.
     */
    @Delete("DELETE FROM approval WHERE snapshot_id = #{snapshotId} AND role = #{role}"
        + " AND status = 'PENDING'")
    int deletePendingBySnapshotRole(@Param("snapshotId") long snapshotId,
                                    @Param("role") String role);
}