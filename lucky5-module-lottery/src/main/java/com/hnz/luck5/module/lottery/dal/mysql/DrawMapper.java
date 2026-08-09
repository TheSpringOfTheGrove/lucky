package com.hnz.luck5.module.lottery.dal.mysql;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.lottery.dal.dataobject.DrawDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DrawMapper extends BaseMapperX<DrawDO> {
}

