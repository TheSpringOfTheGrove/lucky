package com.hnz.luck5.module.lottery.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lucky5_market_route_item")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketRouteItemDO extends LotteryUserBaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String orderId;
    private String betItemId;
    private String period;
    private String play;
    private String selection;
    private String routeType;
    private BigDecimal localAmount;
    private BigDecimal simulatedAmount;
    private BigDecimal odds;
    private BigDecimal localPayout;
    private BigDecimal simulatedPayout;
    private String status;
    private LocalDateTime settledAt;
    private LocalDateTime cancelledAt;

}
