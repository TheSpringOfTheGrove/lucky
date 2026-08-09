package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("lucky5_owner_initialization")
@Data
@EqualsAndHashCode(callSuper = true)
public class OwnerInitializationDO extends LotteryUserBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String firstSource;
    private String lastSource;
    private Integer initializationCount;
    private LocalDateTime firstInitializedAt;
    private LocalDateTime lastInitializedAt;
    private Long lastOperatorUserId;

}
