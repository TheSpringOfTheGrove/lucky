package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_preset_order")
@Data
@EqualsAndHashCode(callSuper = true)
public class PresetOrderDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String member;
    private String content;
    private Boolean enabled;

}
