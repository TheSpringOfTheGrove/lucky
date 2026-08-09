package com.hnz.luck5.module.pay.dal.mysql.notify;

import com.hnz.luck5.module.pay.dal.dataobject.notify.PayNotifyLogDO;
import com.hnz.luck5.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PayNotifyLogMapper extends BaseMapperX<PayNotifyLogDO> {

    default List<PayNotifyLogDO> selectListByTaskId(Long taskId) {
        return selectList(PayNotifyLogDO::getTaskId, taskId);
    }

}
