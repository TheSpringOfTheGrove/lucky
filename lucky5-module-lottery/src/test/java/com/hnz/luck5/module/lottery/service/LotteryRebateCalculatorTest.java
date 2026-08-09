package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.BetItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import com.hnz.luck5.module.lottery.dal.dataobject.RebateRecordDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryRebateCalculatorTest {

    private final LotteryRebateCalculator calculator = new LotteryRebateCalculator();

    @Test
    void calculatesNormalAndDragonRebatesSeparatelyAndSubtractsPaidBases() {
        MemberDO member = member(null);
        OrderDO normal = order("O-1", LocalDateTime.now());
        OrderDO dragon = order("O-2", LocalDateTime.now());
        RebateRecordDO paid = rebate("20", "10", LocalDateTime.now());

        LotteryRebateCalculator.RebateResult result = calculator.calculate(member, List.of(normal, dragon),
                Map.of("O-1", List.of(item("普通", "100")), "O-2", List.of(item("龙虎", "50"))),
                List.of(paid), true);

        assertThat(result.normalBet()).isEqualByComparingTo("100.00");
        assertThat(result.dragonBet()).isEqualByComparingTo("50.00");
        assertThat(result.pendingNormalBet()).isEqualByComparingTo("80.00");
        assertThat(result.pendingDragonBet()).isEqualByComparingTo("40.00");
        assertThat(result.normalAmount()).isEqualByComparingTo("0.80");
        assertThat(result.dragonAmount()).isEqualByComparingTo("0.80");
        assertThat(result.totalAmount()).isEqualByComparingTo("1.60");
    }

    @Test
    void routesDragonBetsToNormalRateWhenSeparateSwitchIsOff() {
        MemberDO member = member(null);
        OrderDO order = order("O-1", LocalDateTime.now());

        LotteryRebateCalculator.RebateResult result = calculator.calculate(member, List.of(order),
                Map.of("O-1", List.of(item("龙虎", "50"))), List.of(), false);

        assertThat(result.normalBet()).isEqualByComparingTo("50.00");
        assertThat(result.dragonBet()).isEqualByComparingTo("0.00");
        assertThat(result.normalAmount()).isEqualByComparingTo("0.50");
        assertThat(result.dragonAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void ignoresOrdersAndRebatesBeforeFlowClearTime() {
        LocalDateTime clearedAt = LocalDateTime.now();
        MemberDO member = member(clearedAt);
        OrderDO oldOrder = order("OLD", clearedAt.minusMinutes(1));
        OrderDO newOrder = order("NEW", clearedAt.plusMinutes(1));
        RebateRecordDO oldPaid = rebate("100", "0", clearedAt.minusSeconds(1));

        LotteryRebateCalculator.RebateResult result = calculator.calculate(member, List.of(oldOrder, newOrder),
                Map.of("OLD", List.of(item("普通", "100")), "NEW", List.of(item("普通", "30"))),
                List.of(oldPaid), false);

        assertThat(result.normalBet()).isEqualByComparingTo("30.00");
        assertThat(result.pendingNormalBet()).isEqualByComparingTo("30.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("0.30");
    }

    private MemberDO member(LocalDateTime clearedAt) {
        MemberDO member = new MemberDO();
        member.setId("M-1");
        member.setNormalRate(new BigDecimal("1"));
        member.setLhhRate(new BigDecimal("2"));
        member.setFlowClearedAt(clearedAt);
        return member;
    }

    private OrderDO order(String id, LocalDateTime createdAt) {
        OrderDO order = new OrderDO();
        order.setId(id);
        order.setMemberId("M-1");
        order.setStatus("已中奖");
        order.setCreateTime(createdAt);
        return order;
    }

    private BetItemDO item(String play, String amount) {
        BetItemDO item = new BetItemDO();
        item.setPlay(play);
        item.setAmount(new BigDecimal(amount));
        return item;
    }

    private RebateRecordDO rebate(String normalBet, String dragonBet, LocalDateTime createdAt) {
        RebateRecordDO record = new RebateRecordDO();
        record.setMemberId("M-1");
        record.setNormalBet(new BigDecimal(normalBet));
        record.setDragonBet(new BigDecimal(dragonBet));
        record.setCreateTime(createdAt);
        return record;
    }

}
