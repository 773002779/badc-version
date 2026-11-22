package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.InFlightOrderRow;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code release_order}. The publish number is the unique business key.
 */
@Mapper
public interface ReleaseOrderMapper extends BaseMapper<ReleaseOrderPO> {

    /**
     * Reads one order as a <em>current read</em> that locks its row until the transaction ends.
     *
     * <p>This is the serialization point for "who may extend this order's approval chain": two
     * concurrent submits of the same order take this lock one after the other, so the second one's
     * plain read of {@code approval} is taken after the first transaction committed and sees its
     * steps. A plain read would not (MySQL's {@code REPEATABLE READ} keeps the first snapshot).
     * {@code release_order} always exists by the time a chain is built.
     *
     * @return the locked order row, or {@code null} when the id does not exist
     */
    @Select("SELECT * FROM release_order WHERE id = #{id} FOR UPDATE")
    ReleaseOrderPO selectByIdForUpdate(@Param("id") long id);

    /**
     * One page of the in-flight scan (tech design 4.2, the 封板 in-flight handling), sorted by
     * ascending order id. {@code subsystemId = 0} means "every system", which is what a GLOBAL
     * freeze affects; a positive id restricts the page to that system.
     *
     * <p><strong>Keyset pagination.</strong> The caller starts with {@code afterId = 0} and then
     * passes the last order id of the previous page, so a pass walks the whole set instead of
     * re-reading the first N rows forever. {@code FreezeService} bounds one pass at
     * {@code IN_FLIGHT_PAGE_SIZE * IN_FLIGHT_MAX_PAGES} orders and logs when it stops at the cap.
     */
    @Select("""
        SELECT o.id                   AS orderId,
               o.publish_no           AS publishNo,
               o.row_version          AS orderRowVersion,
               o.status               AS orderStatus,
               o.planned_release_time AS plannedReleaseTime,
               i.id                   AS itemId,
               i.status               AS itemStatus,
               i.unit_id              AS unitId,
               u.subsystem_id         AS subsystemId
        FROM release_order o
        JOIN release_item i ON i.id = o.item_id
        JOIN deploy_unit u ON u.id = i.unit_id
        WHERE i.status IN ('SUBMITTED', 'PENDING_APPROVAL', 'READY')
          AND (#{subsystemId} = 0 OR u.subsystem_id = #{subsystemId})
          AND o.id > #{afterId}
        ORDER BY o.id
        LIMIT #{limit}
        """)
    List<InFlightOrderRow> selectInFlight(@Param("subsystemId") long subsystemId,
                                          @Param("afterId") long afterId,
                                          @Param("limit") int limit);
}