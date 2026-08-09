package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_market_connection")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketConnectionDO extends LotteryUserBaseDO {

    private Long id;
    private String status;
    private String lineUrl;
    private String displayAccount;
    private BigDecimal balance;
    private String error;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastSyncAt;

}
