package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseWindowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code release_window} (V21). The natural key is
 * {@code (scope, subsystem_id)} with the sentinel {@code 0} for the GLOBAL row, so "the window of
 * a scope" is one lookup instead of a search.
 */
@Mapper
public interface ReleaseWindowMapper extends BaseMapper<ReleaseWindowPO> {

    /** The window of one scope; {@code GLOBAL} passes {@code subsystemId = 0}. */
    @Select("SELECT * FROM release_window WHERE scope = #{scope} AND subsystem_id = #{subsystemId}")
    ReleaseWindowPO selectByScope(@Param("scope") String scope,
                                  @Param("subsystemId") long subsystemId);

    /** Every configured window, GLOBAL first (settings screen). */
    @Select("SELECT * FROM release_window ORDER BY FIELD(scope, 'GLOBAL', 'SUBSYSTEM'), subsystem_id")
    List<ReleaseWindowPO> selectAll();
}