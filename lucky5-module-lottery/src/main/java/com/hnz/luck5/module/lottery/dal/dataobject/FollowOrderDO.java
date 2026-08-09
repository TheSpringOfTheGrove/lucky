package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_follow_order")
@Data
@EqualsAndHashCode(callSuper = true)
public class FollowOrderDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String source;
    private String target;
    private BigDecimal ratio;
    private Boolean enabled;

}
