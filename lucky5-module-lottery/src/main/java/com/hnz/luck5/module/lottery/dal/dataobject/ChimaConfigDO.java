package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_chima_config")
@Data
@EqualsAndHashCode(callSuper = true)
public class ChimaConfigDO extends LotteryUserBaseDO {

    private Long id;
    private BigDecimal siZiXian;
    private BigDecimal sanZiXian;
    private BigDecimal erZiXian;
    private BigDecimal danZiXian;
    private BigDecimal siDingWei;
    private BigDecimal sanDingWei;
    private BigDecimal erDingWei;
    private BigDecimal yiDingWei;
    private BigDecimal yinKuiMax;
    private BigDecimal yinKuiMin;

}
