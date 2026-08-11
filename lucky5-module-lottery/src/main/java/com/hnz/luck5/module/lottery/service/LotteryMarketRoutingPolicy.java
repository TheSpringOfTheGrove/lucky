package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.ChimaConfigDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按当前老板、期号和玩法，把真实玩家订单拆分为本地吃入和真实盘口两部分。 */
@Service
public class LotteryMarketRoutingPolicy {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public List<Allocation> allocate(MemberDO member, ChimaConfigDO config, List<BetItemDO> items,
                                     Map<String, BigDecimal> alreadyRetained) {
        Map<String, BigDecimal> used = new HashMap<>(alreadyRetained);
        List<Allocation> result = new ArrayList<>(items.size());
        boolean eatEnabled = Boolean.TRUE.equals(member.getEatEnabled());
        for (BetItemDO item : items) {
            BigDecimal amount = money(item.getAmount());
            BigDecimal localAmount = ZERO;
            if (eatEnabled) {
                BigDecimal remaining = cap(config, item.getPlay())
                        .subtract(value(used.get(item.getPlay()))).max(ZERO);
                localAmount = amount.min(remaining);
                used.merge(item.getPlay(), localAmount, BigDecimal::add);
            }
            BigDecimal marketAmount = money(amount.subtract(localAmount));
            String routeType = localAmount.signum() == 0 ? "REAL_MARKET"
                    : marketAmount.signum() == 0 ? "LOCAL_EAT" : "MIXED_REAL";
            result.add(new Allocation(item, money(localAmount), marketAmount, routeType));
        }
        return result;
    }

    BigDecimal cap(ChimaConfigDO config, String play) {
        if (config == null || play == null) return ZERO;
        return money(switch (play) {
            case "四字现" -> config.getSiZiXian();
            case "三字现" -> config.getSanZiXian();
            case "二字现" -> config.getErZiXian();
            case "一字现" -> config.getDanZiXian();
            case "四定位", "四条" -> config.getSiDingWei();
            case "三定位" -> config.getSanDingWei();
            case "二定位", "五位二定" -> config.getErDingWei();
            case "一定位" -> config.getYiDingWei();
            default -> ZERO;
        }).max(ZERO);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value(value).setScale(2, RoundingMode.HALF_UP);
    }

    public record Allocation(BetItemDO item, BigDecimal localAmount, BigDecimal marketAmount, String routeType) {}
}
