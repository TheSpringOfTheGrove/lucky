package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.RebateRecordDO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LotteryRebateCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public RebateResult calculate(MemberDO member, List<OrderDO> orders,
                                  Map<String, List<BetItemDO>> itemsByOrder,
                                  List<RebateRecordDO> rebateRecords, boolean separateDragonRebate) {
        BigDecimal normalBet = ZERO;
        BigDecimal dragonBet = ZERO;
        LocalDateTime clearedAt = member.getFlowClearedAt();
        for (OrderDO order : orders) {
            if (!member.getId().equals(order.getMemberId()) || !Set.of("已中奖", "未中奖").contains(order.getStatus())
                    || beforeClear(order.getCreateTime(), clearedAt)) {
                continue;
            }
            for (BetItemDO item : itemsByOrder.getOrDefault(order.getId(), List.of())) {
                if (separateDragonRebate && "龙虎".equals(item.getPlay())) {
                    dragonBet = dragonBet.add(value(item.getAmount()));
                } else {
                    normalBet = normalBet.add(value(item.getAmount()));
                }
            }
        }
        BigDecimal usedNormalBet = ZERO;
        BigDecimal usedDragonBet = ZERO;
        for (RebateRecordDO record : rebateRecords) {
            if (!member.getId().equals(record.getMemberId()) || beforeClear(record.getCreateTime(), clearedAt)) {
                continue;
            }
            usedNormalBet = usedNormalBet.add(value(record.getNormalBet()));
            usedDragonBet = usedDragonBet.add(value(record.getDragonBet()));
        }
        BigDecimal pendingNormalBet = money(normalBet.subtract(usedNormalBet).max(ZERO));
        BigDecimal pendingDragonBet = money(dragonBet.subtract(usedDragonBet).max(ZERO));
        BigDecimal normalAmount = money(pendingNormalBet.multiply(value(member.getNormalRate())).divide(HUNDRED));
        BigDecimal dragonAmount = money(pendingDragonBet.multiply(value(member.getLhhRate())).divide(HUNDRED));
        return new RebateResult(money(normalBet), money(dragonBet), pendingNormalBet, pendingDragonBet,
                normalAmount, dragonAmount, money(normalAmount.add(dragonAmount)));
    }

    private boolean beforeClear(LocalDateTime createdAt, LocalDateTime clearedAt) {
        return clearedAt != null && createdAt != null && createdAt.isBefore(clearedAt);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public record RebateResult(BigDecimal normalBet, BigDecimal dragonBet, BigDecimal pendingNormalBet,
                               BigDecimal pendingDragonBet, BigDecimal normalAmount, BigDecimal dragonAmount,
                               BigDecimal totalAmount) {
    }

}
