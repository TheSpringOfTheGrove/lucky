package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_auto_proxy_execution")
@Data
@EqualsAndHashCode(callSuper = true)
public class AutoProxyExecutionDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String memberId;
    private String memberName;
    private String period;
    private String status;
    private String presetOrderId;
    private String content;
    private BigDecimal requiredAmount;
    private BigDecimal topUpAmount;
    private String orderId;
    private String error;
    private Integer attemptCount;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer version;

}
