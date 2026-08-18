package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_issue")
@Data
@EqualsAndHashCode(callSuper = true)
public class IssueDO extends LotteryUserBaseDO {

    private Long id;
    private String period;
    private String status;
    private Integer marketStatus;
    private Integer remainingSeconds;
    private LocalDateTime serverTime;
    private LocalDateTime sourceObservedAt;
    private String nextPeriod;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime drawTime;
    private LocalDateTime drawUpdatedAt;
    private String result;
    private Integer drawConfirmations;
    private LocalDateTime drawFirstSeenAt;
    private String source;
    private String rawSnapshot;
    private String error;
    private LocalDateTime settlementStartedAt;
    private LocalDateTime settledAt;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer orderSequence;

}
