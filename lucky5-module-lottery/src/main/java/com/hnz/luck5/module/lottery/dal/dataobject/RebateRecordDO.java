package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_rebate_record")
@Data
@EqualsAndHashCode(callSuper = true)
public class RebateRecordDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String memberId;
    private BigDecimal normalBet;
    private BigDecimal dragonBet;
    private BigDecimal normalAmount;
    private BigDecimal dragonAmount;
    private BigDecimal totalAmount;

}
