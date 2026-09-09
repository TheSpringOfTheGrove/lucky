package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_bet_item")
@Data
@EqualsAndHashCode(callSuper = true)
public class BetItemDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String orderId;
    private String play;
    private String selection;
    /**
     * A compact local-order snapshot. One row represents one original sub-command and contains its
     * fully expanded selections, odds and (after settlement) outcomes. External-market rows keep this null.
     */
    private String snapshotJson;
    private BigDecimal amount;
    private BigDecimal odds;
    private Boolean won;
    private BigDecimal payout;

}
