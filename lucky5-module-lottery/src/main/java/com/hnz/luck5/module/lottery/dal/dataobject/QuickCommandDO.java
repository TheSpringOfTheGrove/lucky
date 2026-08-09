package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnz.luck5.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_quick_command")
@Data
@EqualsAndHashCode(callSuper = true)
public class QuickCommandDO extends TenantBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String label;
    private String content;
    private Integer sort;
    private Boolean enabled;

}

