package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.GateWaiverPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** MyBatis-Plus mapper for {@code gate_waiver} (V17). */
@Mapper
public interface GateWaiverMapper extends BaseMapper<GateWaiverPO> {

    /**
     * The newest approved waiver of one 变更项; the evaluator decides whether it still counts
     * (unexpired and covering the current snapshot). Older approved waivers are historical.
     */
    @Select("SELECT * FROM gate_waiver WHERE item_id = #{itemId} AND status = 'APPROVED'"
        + " ORDER BY id DESC LIMIT 1")
    GateWaiverPO selectApproved(@Param("itemId") long itemId);

    /** The newest waiver of any status (the page shows a pending or rejected one too). */
    @Select("SELECT * FROM gate_waiver WHERE item_id = #{itemId} ORDER BY id DESC LIMIT 1")
    GateWaiverPO selectLatest(@Param("itemId") long itemId);

    /** Approved waivers whose expiry passed; the daily scan expires them (T9). */
    @Select("SELECT * FROM gate_waiver WHERE status = 'APPROVED' AND expire_at <= #{now}"
        + " ORDER BY id LIMIT #{limit}")
    List<GateWaiverPO> selectExpired(@Param("now") Instant now, @Param("limit") int limit);
}