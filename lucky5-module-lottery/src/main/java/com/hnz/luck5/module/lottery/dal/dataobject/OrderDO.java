package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_order")
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String memberId;
    private String memberName;
    private String period;
    private String content;
    private BigDecimal amount;
    private BigDecimal win;
    private String status;
    private String source;
    private String orderType;
    private String deliveryMode;
    private String marketStatus;
    private String marketOrderId;
    private String marketError;
    private Integer marketAttempts;
    private Integer itemCount;
    private Integer periodSequence;
    private Integer version;
    private LocalDateTime settledAt;
    private LocalDateTime cancelledAt;

}
