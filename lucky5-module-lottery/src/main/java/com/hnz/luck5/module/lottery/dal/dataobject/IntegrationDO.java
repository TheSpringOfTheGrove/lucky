package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_integration")
@Data
@EqualsAndHashCode(callSuper = true)
public class IntegrationDO extends TenantBaseDO {

    private Long id;
    private String integrationKey;
    private String name;
    private String account;
    private String groupName;
    private String status;

}

