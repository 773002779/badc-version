package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.ReleaseWindowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * The same row as a <em>current read</em> that locks it.
     *
     * <p>Only the duplicate-key recovery path of a save uses this. Under MySQL's default
     * {@code REPEATABLE READ}, a plain re-read inside the same transaction still sees the snapshot
     * taken at its first consistent read — i.e. it would NOT see the row another transaction just
     * committed, leaving the save to rethrow the unique-key error. A locking read is a current
     * read: it waits for the winner's transaction and then sees its committed row.
     */
    @Select("SELECT * FROM release_window WHERE scope = #{scope}"
        + " AND subsystem_id = #{subsystemId} FOR UPDATE")
    ReleaseWindowPO selectByScopeForUpdate(@Param("scope") String scope,
                                           @Param("subsystemId") long subsystemId);
}