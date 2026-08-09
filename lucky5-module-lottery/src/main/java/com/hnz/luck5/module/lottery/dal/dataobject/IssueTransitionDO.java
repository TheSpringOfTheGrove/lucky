package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_issue_transition")
@Data
@EqualsAndHashCode(callSuper = true)
public class IssueTransitionDO extends LotteryUserBaseDO {

    private Long id;
    private Long legacyId;
    private String period;
    private String fromStatus;
    private String toStatus;
    private String source;
    private String detail;

}
