package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.WindowRulePO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis-Plus mapper for {@code window_rule} (V21): the weekly slots of one release window. */
@Mapper
public interface WindowRuleMapper extends BaseMapper<WindowRulePO> {

    /** Every slot of one window, in weekday and start-time order (the page renders them as-is). */
    @Select("SELECT * FROM window_rule WHERE window_id = #{windowId}"
        + " ORDER BY day_of_week, start_time, id")
    List<WindowRulePO> selectByWindow(@Param("windowId") long windowId);

    /** Removes every slot of a window; a save replaces the set instead of diffing it. */
    @Delete("DELETE FROM window_rule WHERE window_id = #{windowId}")
    int deleteByWindow(@Param("windowId") long windowId);
}