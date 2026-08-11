package com.hnz.luck5.module.lottery.service;

import com.hnz.luck5.module.lottery.dal.dataobject.MemberDO;
import com.hnz.luck5.module.lottery.dal.dataobject.MarketRouteItemDO;
import com.hnz.luck5.module.lottery.dal.dataobject.OrderDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotteryChimaCalculatorTest {

    private final LotteryChimaCalculator calculator = new LotteryChimaCalculator();

    @Test
    void onlyCountsEatOrdersAfterGlobalAndMemberClearBoundaries() {
        LocalDateTime now = LocalDateTime.now();
        MemberDO eatMember = member("M-1", true, now.minusMinutes(2));
        MemberDO normalMember = member("M-2", false, null);
        List<OrderDO> orders = List.of(
                order("M-1", "100", "10", "20260809001", now.minusMinutes(3), "已中奖"),
                order("M-1", "40", "5", "20260809002", now.minusSeconds(30), "已中奖"),
                order("M-1", "20", "0", "20260809002", now.minusSeconds(20), "已退码"),
                order("M-2", "99", "0", "20260809002", now.minusSeconds(10), "未开奖"));

        List<LotteryChimaCalculator.PeriodChima> result = calculator.calculate(orders,
                List.of(eatMember, normalMember), now.minusMinutes(1), false);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.period()).isEqualTo("20260809002");
            assertThat(item.fakeAmount()).isEqualByComparingTo("40.00");
            assertThat(item.totalWin()).isEqualByComparingTo("5.00");
            assertThat(item.net()).isEqualByComparingTo("35.00");
        });
    }

    @Test
    void automaticBotOrdersNeverParticipateInChima() {
        LocalDateTime now = LocalDateTime.now();
        MemberDO bot = member("BOT-1", true, null);
        bot.setMemberType("BOT");
        bot.setAutoProxy(true);
        OrderDO botOrder = order("BOT-1", "100", "20", "20260809003", now, "已中奖");
        botOrder.setOrderType("AUTO_PROXY");

        assertThat(calculator.calculate(List.of(botOrder), List.of(bot), null, true)).isEmpty();
    }

    @Test
    void bossModeCountsAllRealPlayerOrders() {
        LocalDateTime now = LocalDateTime.now();
        MemberDO normalMember = member("M-1", false, null);
        OrderDO settledOrder = order("M-1", "100", "30", "20260809004", now, "已中奖");

        List<LotteryChimaCalculator.PeriodChima> result = calculator.calculate(List.of(settledOrder),
                List.of(normalMember), null, true);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.fakeAmount()).isEqualByComparingTo("100.00");
            assertThat(item.totalWin()).isEqualByComparingTo("30.00");
            assertThat(item.net()).isEqualByComparingTo("70.00");
        });
    }

    @Test
    void realMarketModeCountsOnlyLocallyEatenRouteAmountAndPayout() {
        LocalDateTime now = LocalDateTime.now();
        MemberDO member = member("M-1", true, null);
        OrderDO order = order("M-1", "100", "90", "20260811001", now, "已中奖");
        order.setId("O-1");
        MarketRouteItemDO localRoute = route("O-1", "40", "80");
        MarketRouteItemDO marketRoute = route("O-1", "0", "0");

        List<LotteryChimaCalculator.PeriodChima> result = calculator.calculateRoutes(List.of(order),
                List.of(member), List.of(localRoute, marketRoute), null);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.fakeAmount()).isEqualByComparingTo("40.00");
            assertThat(item.totalWin()).isEqualByComparingTo("80.00");
            assertThat(item.net()).isEqualByComparingTo("-40.00");
        });
    }

    private MemberDO member(String id, boolean eatEnabled, LocalDateTime flowClearedAt) {
        MemberDO member = new MemberDO();
        member.setId(id);
        member.setEatEnabled(eatEnabled);
        member.setFlowClearedAt(flowClearedAt);
        return member;
    }

    private OrderDO order(String memberId, String amount, String win, String period, LocalDateTime createdAt,
                          String status) {
        OrderDO order = new OrderDO();
        order.setMemberId(memberId);
        order.setAmount(new BigDecimal(amount));
        order.setWin(new BigDecimal(win));
        order.setPeriod(period);
        order.setCreateTime(createdAt);
        order.setStatus(status);
        return order;
    }

    private MarketRouteItemDO route(String orderId, String localAmount, String localPayout) {
        MarketRouteItemDO route = new MarketRouteItemDO();
        route.setOrderId(orderId);
        route.setLocalAmount(new BigDecimal(localAmount));
        route.setLocalPayout(new BigDecimal(localPayout));
        return route;
    }

}
