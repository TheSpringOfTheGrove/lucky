package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_odd")
@Data
@EqualsAndHashCode(callSuper = true)
public class OddDO extends LotteryUserBaseDO {

    private Long id;
    private String code;
    private String play;
    private String item;
    private BigDecimal rate;
    private BigDecimal secondaryRate;
    private BigDecimal minLimit;
    private BigDecimal maxLimit;
    private String status;

}
