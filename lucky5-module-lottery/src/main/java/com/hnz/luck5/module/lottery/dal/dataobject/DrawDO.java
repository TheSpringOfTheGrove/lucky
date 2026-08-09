package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_draw")
@Data
@EqualsAndHashCode(callSuper = true)
public class DrawDO extends LotteryUserBaseDO {

    private Long id;
    private String period;
    private String result;
    private String bigSmall;
    private String oddEven;
    private String dragonTiger;
    private String status;
    private LocalDateTime settledAt;

}
