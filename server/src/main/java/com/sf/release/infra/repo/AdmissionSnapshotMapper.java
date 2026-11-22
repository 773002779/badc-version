package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus mapper for {@code admission_snapshot} (V21). The newest row of an order is its
 * current admission decision; a resubmission appends a newer one and leaves the old evidence in
 * place.
 */
@Mapper
public interface AdmissionSnapshotMapper extends BaseMapper<AdmissionSnapshotPO> {

    /** The newest snapshot of one order, or {@code null} when it was never submitted. */
    @Select("SELECT * FROM admission_snapshot WHERE order_id = #{orderId}"
        + " ORDER BY id DESC LIMIT 1")
    AdmissionSnapshotPO selectLatestByOrder(@Param("orderId") long orderId);
}