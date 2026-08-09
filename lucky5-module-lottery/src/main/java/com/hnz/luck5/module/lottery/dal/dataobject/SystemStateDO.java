package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_system_state")
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemStateDO extends LotteryUserBaseDO {

    private Long id;
    private String operatorUsername;
    private LocalDateTime expireAt;
    private Boolean roomOpen;
    private Integer online;
    private LocalDateTime chimaClearedAt;

}
