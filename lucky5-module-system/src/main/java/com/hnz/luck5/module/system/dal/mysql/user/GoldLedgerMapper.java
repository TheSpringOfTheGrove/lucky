package com.hnz.luck5.module.system.dal.mysql.user;

import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import com.hnz.luck5.module.system.dal.dataobject.user.GoldLedgerDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GoldLedgerMapper extends BaseMapperX<GoldLedgerDO> {

    default GoldLedgerDO selectByOperationKey(String operationKey) {
        return selectOne(GoldLedgerDO::getOperationKey, operationKey);
    }
}
