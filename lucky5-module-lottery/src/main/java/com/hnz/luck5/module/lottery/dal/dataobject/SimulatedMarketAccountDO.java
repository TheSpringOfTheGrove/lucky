package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@TableName("lucky5_simulated_market_account")
@Data
@EqualsAndHashCode(callSuper = true)
public class SimulatedMarketAccountDO extends LotteryUserBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private BigDecimal initialBalance;
    private BigDecimal balance;
    private BigDecimal totalStake;
    private BigDecimal totalPayout;
    private BigDecimal totalRefund;
    private Integer version;

}
