package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_integration")
@Data
@EqualsAndHashCode(callSuper = true)
public class IntegrationDO extends LotteryUserBaseDO {

    private Long id;
    private String integrationKey;
    private String name;
    private String account;
    private String groupName;
    private String status;

}
