package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /**
     * The newest snapshot of each of {@code orderIds}, in <strong>one</strong> statement, so the
     * freeze in-flight handling does not run a query per order (the batch is capped by
     * {@code FreezeService.IN_FLIGHT_LIMIT}). Callers must not pass an empty list.
     */
    @Select("""
        <script>
        SELECT s.* FROM admission_snapshot s
        JOIN (
            SELECT MAX(id) AS max_id
            FROM admission_snapshot
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY order_id
        ) newest ON newest.max_id = s.id
        </script>
        """)
    List<AdmissionSnapshotPO> selectLatestByOrderIds(@Param("orderIds") List<Long> orderIds);
}