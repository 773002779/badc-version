package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.WindowExceptionPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis-Plus mapper for {@code window_exception} (V21): the date exceptions of one release
 * window. One exception per window per date is a unique key, so a save can replace the whole set.
 */
@Mapper
public interface WindowExceptionMapper extends BaseMapper<WindowExceptionPO> {

    /** Every exception of one window, in date order. */
    @Select("SELECT * FROM window_exception WHERE window_id = #{windowId} ORDER BY the_date, id")
    List<WindowExceptionPO> selectByWindow(@Param("windowId") long windowId);

    /** Removes every exception of a window; a save replaces the set instead of diffing it. */
    @Delete("DELETE FROM window_exception WHERE window_id = #{windowId}")
    int deleteByWindow(@Param("windowId") long windowId);
}