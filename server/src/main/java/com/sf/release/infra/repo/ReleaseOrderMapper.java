package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}