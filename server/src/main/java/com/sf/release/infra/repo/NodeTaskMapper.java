package com.sf.release.infra.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sf.release.infra.repo.po.NodeTaskPO;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for {@code node_task}. */
@Mapper
public interface NodeTaskMapper extends BaseMapper<NodeTaskPO> {
}